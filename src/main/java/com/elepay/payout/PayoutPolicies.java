package com.elepay.payout;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.ledger.Ledger;
import com.elepay.merchant.MerchantAccess;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payout policy: whether a merchant's settled balance is withdrawn on request or automatically.
 *
 * <p>A marketplace (platform) decides for the sellers and delivery partners in its network, with one default
 * and optional per-seller overrides. A merchant in no governed network decides for itself. If several
 * marketplaces govern one seller and disagree, the seller is on request (the safer choice: nothing leaves
 * without a request). Withdrawals on request stay available in both modes.
 */
@Service
public class PayoutPolicies {
    static final Set<String> MODES = Set.of("on_request", "automatic");
    static final String SYSTEM_ACTOR = "system:auto-withdrawal";

    private final JdbcClient db;
    private final Ledger ledger;
    private final Payouts payouts;
    private final MerchantAccess merchants;
    private final Audit audit;
    private final OpsQueue ops;
    private final TransactionTemplate tx;
    private final boolean autoEnabled;

    PayoutPolicies(JdbcClient db, Ledger ledger, Payouts payouts, MerchantAccess merchants, Audit audit, OpsQueue ops,
                   PlatformTransactionManager tm, @Value("${elepay.payouts.auto-enabled:true}") boolean autoEnabled) {
        this.db = db;
        this.ledger = ledger;
        this.payouts = payouts;
        this.merchants = merchants;
        this.audit = audit;
        this.ops = ops;
        this.tx = new TransactionTemplate(tm);
        this.autoEnabled = autoEnabled;
    }

    public record Rule(String mode, long minAmount) {}

    public record Governor(UUID marketplaceId, String mode, long minAmount, boolean sellerOverride) {}

    /**
     * The policy in force for a merchant. {@code source} is {@code platform} when marketplaces decide,
     * {@code merchant} when the merchant chose, or {@code default}.
     */
    public record Effective(UUID merchantId, String mode, long minAmount, String source, List<Governor> governedBy,
                            UUID destinationId) {}

    static void validate(String mode, long minAmount) {
        if (mode == null || !MODES.contains(mode)) {
            throw ApiException.badRequest("invalid_mode", "Choose on_request or automatic");
        }
        if (minAmount < 0) throw ApiException.badRequest("invalid_min_amount", "Minimum amount cannot be negative");
    }

    List<Governor> governors(UUID merchant) {
        return db.sql("""
                SELECT ms.marketplace_id, coalesce(o.mode, p.mode), coalesce(o.min_amount, p.min_amount), o.mode IS NOT NULL
                FROM marketplace_seller ms
                LEFT JOIN platform_payout_policy p ON p.marketplace_id = ms.marketplace_id
                LEFT JOIN platform_seller_payout_policy o
                       ON o.marketplace_id = ms.marketplace_id AND o.seller_id = ms.seller_id
                WHERE ms.seller_id = :m AND ms.status = 'active' AND coalesce(o.mode, p.mode) IS NOT NULL
                ORDER BY ms.marketplace_id""").param("m", merchant)
                .query((rs, i) -> new Governor(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getBoolean(4))).list();
    }

    public Effective effective(UUID merchant) {
        List<Governor> governedBy = governors(merchant);
        record Own(String mode, long min, UUID destination) {}
        Own own = db.sql("SELECT mode, min_amount, destination_id FROM payout_policy WHERE merchant_id = :m")
                .param("m", merchant)
                .query((rs, i) -> new Own(rs.getString(1), rs.getLong(2), rs.getObject(3, UUID.class)))
                .optional().orElse(null);
        UUID destination = own == null ? null : own.destination();
        if (!governedBy.isEmpty()) {
            boolean allAutomatic = governedBy.stream().allMatch(g -> g.mode().equals("automatic"));
            long min = governedBy.stream().mapToLong(Governor::minAmount).max().orElse(0);
            return new Effective(merchant, allAutomatic ? "automatic" : "on_request", min, "platform", governedBy,
                    destination);
        }
        if (own != null) return new Effective(merchant, own.mode(), own.min(), "merchant", List.of(), destination);
        return new Effective(merchant, "on_request", 0, "default", List.of(), destination);
    }

    /**
     * A merchant's own choice. The mode and minimum apply only while no marketplace governs the merchant;
     * the destination is always the merchant's to choose.
     */
    public Effective saveOwn(UUID merchant, String mode, Long minAmount, UUID destinationId, String actor) {
        return tx.execute(s -> {
            boolean governed = !governors(merchant).isEmpty();
            if (mode != null && governed) {
                throw ApiException.conflict("governed_by_platform",
                        "Your marketplace decides how you are paid out; you can still choose the destination");
            }
            if (destinationId != null) requireVerifiedDestination(merchant, destinationId);
            var current = effective(merchant);
            String newMode = mode != null ? mode : (current.source().equals("merchant") ? current.mode() : "on_request");
            long newMin = minAmount != null ? minAmount : (current.source().equals("merchant") ? current.minAmount() : 0);
            validate(newMode, newMin);
            db.sql("""
                    INSERT INTO payout_policy (merchant_id, mode, min_amount, destination_id, updated_by)
                    VALUES (:m, :mode, :min, :d, :by) ON CONFLICT (merchant_id) DO UPDATE SET
                        mode = :mode, min_amount = :min, destination_id = coalesce(:d, payout_policy.destination_id),
                        updated_by = :by, updated_at = now()""")
                    .param("m", merchant).param("mode", newMode).param("min", newMin).param("d", destinationId)
                    .param("by", actor).update();
            audit.record(actor, "payout.policy_updated", "merchant:" + merchant,
                    Map.of("mode", newMode, "minAmount", newMin, "destination", String.valueOf(destinationId)));
            return effective(merchant);
        });
    }

    public record PlatformPolicy(UUID marketplaceId, Rule defaultRule, Map<UUID, Rule> sellerOverrides) {}

    public PlatformPolicy platform(UUID marketplace) {
        Rule def = db.sql("SELECT mode, min_amount FROM platform_payout_policy WHERE marketplace_id = :m")
                .param("m", marketplace).query((rs, i) -> new Rule(rs.getString(1), rs.getLong(2))).optional()
                .orElse(null);
        Map<UUID, Rule> overrides = new LinkedHashMap<>();
        db.sql("SELECT seller_id, mode, min_amount FROM platform_seller_payout_policy WHERE marketplace_id = :m ORDER BY seller_id")
                .param("m", marketplace).query((rs, i) -> Map.entry(rs.getObject(1, UUID.class),
                        new Rule(rs.getString(2), rs.getLong(3))))
                .list().forEach(e -> overrides.put(e.getKey(), e.getValue()));
        return new PlatformPolicy(marketplace, def, overrides);
    }

    private void requireMarketplace(UUID marketplace) {
        var m = merchants.find(marketplace).orElseThrow(() -> ApiException.notFound("Merchant not found"));
        if (!m.kind().equals("marketplace")) {
            throw ApiException.conflict("not_marketplace", "Only marketplaces set payout policy for sellers");
        }
    }

    /** The platform's default for every seller and delivery partner in its network. */
    public PlatformPolicy savePlatformDefault(UUID marketplace, String mode, long minAmount, String actor) {
        validate(mode, minAmount);
        return tx.execute(s -> {
            requireMarketplace(marketplace);
            db.sql("""
                    INSERT INTO platform_payout_policy (marketplace_id, mode, min_amount, updated_by)
                    VALUES (:m, :mode, :min, :by) ON CONFLICT (marketplace_id) DO UPDATE SET
                        mode = :mode, min_amount = :min, updated_by = :by, updated_at = now()""")
                    .param("m", marketplace).param("mode", mode).param("min", minAmount).param("by", actor).update();
            audit.record(actor, "payout.platform_policy_updated", "merchant:" + marketplace,
                    Map.of("mode", mode, "minAmount", minAmount));
            return platform(marketplace);
        });
    }

    /** A per-seller override; {@code mode == null} removes it so the platform default applies again. */
    public PlatformPolicy saveSellerOverride(UUID marketplace, UUID seller, String mode, long minAmount, String actor) {
        return tx.execute(s -> {
            requireMarketplace(marketplace);
            boolean related = db.sql("""
                    SELECT count(*) FROM marketplace_seller WHERE marketplace_id = :m AND seller_id = :s
                    AND status IN ('invited', 'active')""").param("m", marketplace).param("s", seller)
                    .query(Long.class).single() > 0;
            if (!related) throw ApiException.notFound("Seller not found in this marketplace");
            if (mode == null) {
                db.sql("DELETE FROM platform_seller_payout_policy WHERE marketplace_id = :m AND seller_id = :s")
                        .param("m", marketplace).param("s", seller).update();
            } else {
                validate(mode, minAmount);
                db.sql("""
                        INSERT INTO platform_seller_payout_policy (marketplace_id, seller_id, mode, min_amount, updated_by)
                        VALUES (:m, :s, :mode, :min, :by) ON CONFLICT (marketplace_id, seller_id) DO UPDATE SET
                            mode = :mode, min_amount = :min, updated_by = :by, updated_at = now()""")
                        .param("m", marketplace).param("s", seller).param("mode", mode).param("min", minAmount)
                        .param("by", actor).update();
            }
            audit.record(actor, "payout.seller_policy_updated", "merchant:" + seller,
                    Map.of("marketplace", marketplace.toString(), "mode", String.valueOf(mode)));
            return platform(marketplace);
        });
    }

    // ---- automatic withdrawals ---------------------------------------------------------------

    record DestinationRow(UUID id, Payouts.Destination destination, String verifiedName) {}

    private Optional<DestinationRow> verifiedDestination(UUID merchant, UUID id) {
        return db.sql("""
                SELECT id, type, network, account_number, account_name, verified_name FROM settlement_destination
                WHERE id = :id AND owner_type = 'merchant' AND owner_id = :m AND status = 'active'
                  AND verification = 'verified'""").param("m", merchant).param("id", id)
                .query((rs, i) -> new DestinationRow(rs.getObject(1, UUID.class), new Payouts.Destination(
                        rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), rs.getString(6)))
                .optional();
    }

    private void requireVerifiedDestination(UUID merchant, UUID id) {
        if (verifiedDestination(merchant, id).isEmpty()) {
            throw ApiException.conflict("destination_unverified",
                    "Automatic withdrawals go only to a verified destination owned by this merchant");
        }
    }

    /**
     * The destination for an automatic withdrawal: the one the merchant chose, else its only verified
     * destination. With none (or several and no choice) the funds stay put and operations are told.
     */
    Optional<DestinationRow> autoDestination(UUID merchant, UUID chosen) {
        if (chosen != null) return verifiedDestination(merchant, chosen);
        List<DestinationRow> all = db.sql("""
                SELECT id, type, network, account_number, account_name, verified_name FROM settlement_destination
                WHERE owner_type = 'merchant' AND owner_id = :m AND status = 'active' AND verification = 'verified'""")
                .param("m", merchant)
                .query((rs, i) -> new DestinationRow(rs.getObject(1, UUID.class), new Payouts.Destination(
                        rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), rs.getString(6))).list();
        return all.size() == 1 ? Optional.of(all.get(0)) : Optional.empty();
    }

    /** Pays out the available balance of every merchant on automatic withdrawal. Returns the batches created. */
    public List<Payouts.Batch> runAutomatic() {
        List<UUID> candidates = db.sql("""
                SELECT owner_id FROM ledger_account
                WHERE owner_type = 'merchant' AND bucket = 'available' AND currency = 'GHS' AND balance > 0
                ORDER BY owner_id""").query(UUID.class).list();
        List<Payouts.Batch> created = new ArrayList<>();
        for (UUID merchant : candidates) {
            Effective policy = effective(merchant);
            if (!policy.mode().equals("automatic")) continue;
            var destination = autoDestination(merchant, policy.destinationId());
            if (destination.isEmpty()) {
                ops.raise("auto_withdrawal_no_destination", "medium", "merchant:" + merchant, Map.of(
                        "note", "Automatic withdrawal is on but the merchant has no single verified destination"));
                continue;
            }
            Payouts.Batch batch;
            try {
                batch = tx.execute(s -> {
                    long available = ledger.balance(Ledger.code("merchant", merchant, "available", Ledger.GHS));
                    if (available <= 0 || available < policy.minAmount()) return null;
                    return payouts.create("merchant", merchant, List.of(new Payouts.Item(
                            "auto-withdrawal-" + UUID.randomUUID(), available, destination.get().destination(), null,
                            destination.get().verifiedName())), null, SYSTEM_ACTOR);
                });
            } catch (ApiException e) {
                if (e.code().equals("insufficient_funds")) continue; // a manual withdrawal won the race
                throw e;
            }
            if (batch == null) continue;
            payouts.submit(batch.id());
            created.add(payouts.batch(batch.id()));
        }
        return created;
    }

    @Scheduled(fixedDelayString = "${elepay.payouts.auto-ms:60000}", initialDelayString = "${elepay.payouts.auto-ms:60000}")
    void scheduledRun() {
        if (autoEnabled) runAutomatic();
    }
}
