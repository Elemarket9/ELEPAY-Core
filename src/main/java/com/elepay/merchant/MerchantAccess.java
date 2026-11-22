package com.elepay.merchant;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Server-side merchant authorization. Every merchant-scoped endpoint calls
 * {@link #require}; a caller with no membership receives 404 so merchant ids
 * cannot be probed.
 */
@Service
public class MerchantAccess {

    /** WITHDRAW sends to the merchant's own registered destination; PAYOUT pays other parties. */
    public enum Permission { VIEW, FINANCE_VIEW, COLLECT, REFUND, WITHDRAW, PAYOUT, MANAGE }

    private static final Map<String, Set<Permission>> ROLES = Map.of(
            "owner", EnumSet.allOf(Permission.class),
            "admin", EnumSet.allOf(Permission.class),
            "finance", EnumSet.of(Permission.VIEW, Permission.FINANCE_VIEW, Permission.REFUND, Permission.WITHDRAW,
                    Permission.PAYOUT, Permission.COLLECT),
            "cashier", EnumSet.of(Permission.VIEW, Permission.COLLECT));
    private static final Map<Permission, String> SCOPE = Map.of(
            Permission.VIEW, "view", Permission.FINANCE_VIEW, "view", Permission.COLLECT, "collect",
            Permission.REFUND, "refund", Permission.WITHDRAW, "withdraw", Permission.PAYOUT, "payouts");
    private static final Set<Permission> OPS_READ = EnumSet.of(Permission.VIEW, Permission.FINANCE_VIEW);
    private static final Set<Permission> REQUIRE_ACTIVE = EnumSet.of(Permission.COLLECT, Permission.WITHDRAW);

    private final JdbcClient db;

    MerchantAccess(JdbcClient db) {
        this.db = db;
    }

    /** {@code branchId} is set for cashiers, who are limited to their branch. */
    public record Access(UUID merchantId, String role, UUID branchId, Actor actor) {
        public boolean branchScoped() { return branchId != null; }
    }

    public record Merchant(UUID id, String tradingName, String kind, String status, String merchantCode,
                           int feeBps, long feeFixed, int settlementDelayHours) {}

    public Access require(UUID merchantId, Permission permission) {
        Actor actor = Actor.current();
        Merchant merchant = find(merchantId).orElseThrow(() -> ApiException.notFound("Merchant not found"));
        Access access;
        if (actor.isApiKey()) {
            String scope = SCOPE.get(permission);
            if (!actor.apiKeyMerchant().equals(merchantId)) {
                // A platform credential may act for another merchant only with that merchant's delegation.
                boolean delegated = scope != null && db.sql("""
                        SELECT count(*) FROM merchant_delegation
                        WHERE merchant_id = :m AND delegate_id = :d AND permission = :p""")
                        .param("m", merchantId).param("d", actor.apiKeyMerchant()).param("p", scope)
                        .query(Long.class).single() > 0;
                if (!delegated) throw ApiException.notFound("Merchant not found");
            }
            if (scope == null || !actor.scopes().contains(scope)) {
                throw ApiException.forbidden("This API key is not scoped for " + permission.name().toLowerCase());
            }
            access = new Access(merchantId, "api_key", null, actor);
        } else {
            var membership = db.sql("SELECT role, branch_id FROM merchant_member WHERE merchant_id = :m AND subject = :s")
                    .param("m", merchantId).param("s", actor.subject())
                    .query((rs, i) -> new Access(merchantId, rs.getString(1), rs.getObject(2, UUID.class), actor))
                    .optional();
            if (membership.isPresent()) {
                access = membership.get();
                if (!ROLES.get(access.role()).contains(permission)) {
                    throw ApiException.forbidden("Your " + access.role() + " role cannot perform this action");
                }
            } else if (actor.isOps() && OPS_READ.contains(permission)) {
                access = new Access(merchantId, "ops", null, actor);
            } else {
                throw ApiException.notFound("Merchant not found");
            }
        }
        if (REQUIRE_ACTIVE.contains(permission) && !merchant.status().equals("active")) {
            throw ApiException.conflict("merchant_not_active", "The merchant account is " + merchant.status());
        }
        return access;
    }

    public Optional<Merchant> find(UUID id) {
        return db.sql("""
                SELECT id, trading_name, kind, status, merchant_code, fee_bps, fee_fixed, settlement_delay_hours
                FROM merchant WHERE id = :id""").param("id", id).query(MerchantAccess::map).optional();
    }

    /** The merchant's contact phone, for notifications. */
    public Optional<String> contactPhone(UUID merchant) {
        return db.sql("SELECT contact_phone FROM merchant WHERE id = :id").param("id", merchant)
                .query(String.class).optional();
    }

    /** Marketplaces in which this merchant is an active seller or delivery partner. */
    public List<UUID> activeMarketplacesOf(UUID seller) {
        return db.sql("""
                SELECT marketplace_id FROM marketplace_seller WHERE seller_id = :s AND status = 'active'
                ORDER BY marketplace_id""").param("s", seller).query(UUID.class).list();
    }

    /** The status of a marketplace-seller relationship (invited, active, removed), if one exists. */
    public Optional<String> relationship(UUID marketplace, UUID seller) {
        return db.sql("SELECT status FROM marketplace_seller WHERE marketplace_id = :m AND seller_id = :s")
                .param("m", marketplace).param("s", seller).query(String.class).optional();
    }

    /** A merchant's own bank or MoMo account for settlement and withdrawals. */
    public record SettlementDestination(UUID id, String type, String network, String accountNumber, String accountName,
                                        String verification, String verifiedName) {
        public boolean verified() { return "verified".equals(verification); }
    }

    private static SettlementDestination destination(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SettlementDestination(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7));
    }

    /** An active destination owned by this merchant. */
    public Optional<SettlementDestination> activeDestination(UUID merchant, UUID id) {
        return db.sql("""
                SELECT id, type, network, account_number, account_name, verification, verified_name
                FROM settlement_destination
                WHERE id = :id AND owner_type = 'merchant' AND owner_id = :m AND status = 'active'""")
                .param("id", id).param("m", merchant).query(MerchantAccess::destination).optional();
    }

    public List<SettlementDestination> verifiedDestinations(UUID merchant) {
        return db.sql("""
                SELECT id, type, network, account_number, account_name, verification, verified_name
                FROM settlement_destination
                WHERE owner_type = 'merchant' AND owner_id = :m AND status = 'active' AND verification = 'verified'
                ORDER BY created_at""").param("m", merchant).query(MerchantAccess::destination).list();
    }

    public Optional<Merchant> findByCode(String code) {
        return db.sql("""
                SELECT id, trading_name, kind, status, merchant_code, fee_bps, fee_fixed, settlement_delay_hours
                FROM merchant WHERE merchant_code = :c""").param("c", code).query(MerchantAccess::map).optional();
    }

    private static Merchant map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Merchant(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), rs.getLong(7), rs.getInt(8));
    }

    /** True if {@code seller} is an active seller of {@code marketplace}. */
    public boolean isActiveSeller(UUID marketplace, UUID seller) {
        return db.sql("SELECT count(*) FROM marketplace_seller WHERE marketplace_id = :m AND seller_id = :s AND status = 'active'")
                .param("m", marketplace).param("s", seller).query(Long.class).single() > 0;
    }

    public boolean branchBelongs(UUID merchantId, UUID branchId) {
        return db.sql("SELECT count(*) FROM merchant_branch WHERE id = :b AND merchant_id = :m AND active")
                .param("b", branchId).param("m", merchantId).query(Long.class).single() > 0;
    }

    public List<UUID> membershipsOf(String subject) {
        return db.sql("SELECT merchant_id FROM merchant_member WHERE subject = :s").param("s", subject)
                .query(UUID.class).list();
    }

    /** Fee agreed with the merchant: basis points plus a fixed amount, capped at the allocation. */
    public static long fee(Merchant merchant, long amount) {
        long fee = Math.addExact(Math.multiplyExact(amount, merchant.feeBps()) / 10_000, merchant.feeFixed());
        return Math.min(fee, amount);
    }
}
