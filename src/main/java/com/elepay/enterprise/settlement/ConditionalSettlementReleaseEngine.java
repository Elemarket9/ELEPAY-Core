package com.elepay.enterprise.settlement;

import com.elepay.common.ApiException;
import com.elepay.enterprise.treasury.EnterpriseTreasuryService;
import com.elepay.enterprise.treasury.EnterpriseTreasuryService.ShortfallEvaluation;
import com.elepay.enterprise.treasury.ShortfallStatus;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.ledger.Ledger.Posting;
import com.elepay.payout.Payouts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conditional Settlement Release Engine.
 *
 * <p>Orchestrates configurable settlement schedules (immediate, scheduled, daily, weekly,
 * enterprise-triggered, merchant-requested, threshold, partial, batch, net/gross).
 *
 * <p>Enforces conditional release: marketplace/enterprise instruction controls eligibility.
 * <strong>Missing delivery confirmation does NOT automatically prove delivery:</strong>
 * delivery escrow hold strictly prevents premature payouts.
 */
@Service
public class ConditionalSettlementReleaseEngine {

    public static final String GHS = "GHS";

    private final JdbcClient db;
    private final Ledger ledger;
    private final DeliveryEscrowService escrowService;
    private final EnterpriseTreasuryService treasuryService;
    private final Payouts payouts;

    public ConditionalSettlementReleaseEngine(
            JdbcClient db,
            Ledger ledger,
            DeliveryEscrowService escrowService,
            EnterpriseTreasuryService treasuryService,
            Payouts payouts
    ) {
        this.db = Objects.requireNonNull(db, "db cannot be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger cannot be null");
        this.escrowService = Objects.requireNonNull(escrowService, "escrowService cannot be null");
        this.treasuryService = Objects.requireNonNull(treasuryService, "treasuryService cannot be null");
        this.payouts = Objects.requireNonNull(payouts, "payouts cannot be null");
    }

    /**
     * Configures settlement rules and schedules for an enterprise or specific sub-merchant.
     */
    @Transactional
    public SettlementRule configureSettlementRule(
            UUID enterpriseId,
            UUID merchantId,
            SettlementScheduleType scheduleType,
            int timingDays,
            long minThresholdPesewas,
            boolean deliveryConditionRequired,
            int timeoutDays,
            NetGrossMode netGrossMode,
            String currency,
            boolean autoPayoutEnabled
    ) {
        Objects.requireNonNull(enterpriseId, "enterpriseId cannot be null");
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        UUID id = UUID.randomUUID();

        db.sql("""
                INSERT INTO enterprise_settlement_rule (
                    id, enterprise_id, merchant_id, schedule_type, settlement_timing_days,
                    min_threshold_pesewas, delivery_condition_required, delivery_timeout_days,
                    net_gross_mode, currency, auto_payout_enabled, status
                ) VALUES (
                    :id, :enterpriseId, :merchantId, :scheduleType, :timingDays,
                    :minThreshold, :deliveryRequired, :timeoutDays,
                    :netGross, :cur, :autoPayout, 'ACTIVE'
                )
                """)
                .param("id", id)
                .param("enterpriseId", enterpriseId)
                .param("merchantId", merchantId)
                .param("scheduleType", scheduleType.name())
                .param("timingDays", timingDays)
                .param("minThreshold", minThresholdPesewas)
                .param("deliveryRequired", deliveryConditionRequired)
                .param("timeoutDays", timeoutDays)
                .param("netGross", netGrossMode.name())
                .param("cur", cur)
                .param("autoPayout", autoPayoutEnabled)
                .update();

        return getSettlementRule(enterpriseId, merchantId, cur);
    }

    /**
     * Retrieves active settlement rule for an enterprise or sub-merchant.
     */
    public SettlementRule getSettlementRule(UUID enterpriseId, UUID merchantId, String currency) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;

        if (merchantId != null) {
            var specific = db.sql("""
                    SELECT id, enterprise_id, merchant_id, schedule_type, settlement_timing_days,
                           min_threshold_pesewas, delivery_condition_required, delivery_timeout_days,
                           net_gross_mode, currency, auto_payout_enabled, status, created_at, updated_at
                    FROM enterprise_settlement_rule
                    WHERE enterprise_id = :enterpriseId AND merchant_id = :merchantId AND currency = :cur
                    """)
                    .param("enterpriseId", enterpriseId)
                    .param("merchantId", merchantId)
                    .param("cur", cur)
                    .query(this::mapRule)
                    .optional();

            if (specific.isPresent()) {
                return specific.get();
            }
        }

        return db.sql("""
                SELECT id, enterprise_id, merchant_id, schedule_type, settlement_timing_days,
                       min_threshold_pesewas, delivery_condition_required, delivery_timeout_days,
                       net_gross_mode, currency, auto_payout_enabled, status, created_at, updated_at
                FROM enterprise_settlement_rule
                WHERE enterprise_id = :enterpriseId AND merchant_id IS NULL AND currency = :cur
                """)
                .param("enterpriseId", enterpriseId)
                .param("cur", cur)
                .query(this::mapRule)
                .optional()
                .orElseGet(() -> SettlementRule.defaultRule(enterpriseId, cur));
    }

    /**
     * Executes conditional settlement release for all delivered items in an enterprise.
     * Evaluates delivery confirmation:
     * - Only DELIVERED items are released.
     * - MISSING_CONFIRMATION or PENDING items strictly remain in escrow!
     * - Validates treasury prefunding sufficiency before release.
     */
    @Transactional
    public SettlementReleaseResult releaseEligibleSettlements(UUID enterpriseId, String currency) {
        Objects.requireNonNull(enterpriseId, "enterpriseId cannot be null");
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;

        SettlementRule rule = getSettlementRule(enterpriseId, null, cur);

        // 1. Fetch eligible delivered items and unconfirmed held items
        List<DeliveryEscrowRecord> allHeld = db.sql("""
                SELECT id, enterprise_id, order_reference, item_reference, merchant_id,
                       gross_amount_pesewas, net_payable_pesewas, commission_pesewas,
                       currency, delivery_status, escrow_status, delivered_at, released_at,
                       ledger_hold_tx_id, ledger_release_tx_id, notes, created_at, updated_at
                FROM enterprise_delivery_escrow
                WHERE enterprise_id = :enterpriseId AND escrow_status = 'HELD'
                ORDER BY created_at ASC
                """)
                .param("enterpriseId", enterpriseId)
                .query(this::mapEscrowRecord)
                .list();

        List<DeliveryEscrowRecord> eligibleItems = new ArrayList<>();
        List<DeliveryEscrowRecord> heldItems = new ArrayList<>();

        for (DeliveryEscrowRecord rec : allHeld) {
            if (rule.deliveryConditionRequired()) {
                if (rec.deliveryStatus() == DeliveryStatus.DELIVERED) {
                    eligibleItems.add(rec);
                } else {
                    // Retain in escrow! Missing delivery confirmation does NOT prove delivery!
                    heldItems.add(rec);
                }
            } else {
                eligibleItems.add(rec);
            }
        }

        if (eligibleItems.isEmpty()) {
            return new SettlementReleaseResult(
                    UUID.randomUUID(),
                    "BATCH-EMPTY",
                    rule.scheduleType(),
                    0, 0, heldItems.size(),
                    0L, 0L, cur, null, null, false,
                    "NO_ELIGIBLE_ITEMS",
                    List.of(), heldItems
            );
        }

        long totalGross = eligibleItems.stream().mapToLong(DeliveryEscrowRecord::grossAmountPesewas).sum();
        long totalNet = eligibleItems.stream().mapToLong(DeliveryEscrowRecord::netPayablePesewas).sum();
        long totalCommission = eligibleItems.stream().mapToLong(DeliveryEscrowRecord::commissionPesewas).sum();
        Set<UUID> uniqueMerchants = new HashSet<>();
        for (DeliveryEscrowRecord r : eligibleItems) {
            uniqueMerchants.add(r.merchantId());
        }

        // 2. Validate treasury funding sufficiency
        ShortfallEvaluation shortfallEval = treasuryService.checkFundingSufficiency(
                enterpriseId, totalNet, cur, "SETTLEMENT_RELEASE"
        );

        UUID batchId = UUID.randomUUID();
        String batchRef = "SETTLE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        if (shortfallEval.hasShortfall()) {
            // Place hold on entire batch due to funding shortfall
            db.sql("""
                    INSERT INTO enterprise_settlement_batch (
                        id, enterprise_id, batch_reference, settlement_type,
                        total_merchants, total_items, total_gross_pesewas,
                        total_net_pesewas, total_commission_pesewas, currency,
                        status, shortfall_detected
                    ) VALUES (
                        :id, :enterpriseId, :batchRef, :type,
                        :merchants, :items, :gross,
                        :net, :comm, :cur,
                        'FAILED', true
                    )
                    """)
                    .param("id", batchId)
                    .param("enterpriseId", enterpriseId)
                    .param("batchRef", batchRef)
                    .param("type", rule.scheduleType().name())
                    .param("merchants", uniqueMerchants.size())
                    .param("items", eligibleItems.size())
                    .param("gross", totalGross)
                    .param("net", totalNet)
                    .param("comm", totalCommission)
                    .param("cur", cur)
                    .update();

            return new SettlementReleaseResult(
                    batchId, batchRef, rule.scheduleType(),
                    uniqueMerchants.size(), 0, eligibleItems.size() + heldItems.size(),
                    0L, 0L, cur, null, null, true,
                    "SHORTFALL_HOLD",
                    List.of(), allHeld
            );
        }

        // 3. Post double-entry journal release:
        // For each merchant: Debit merchant:pending (net), Credit merchant:available (net)
        List<Leg> legs = new ArrayList<>();
        Map<UUID, Long> merchantNetTotals = new HashMap<>();

        for (DeliveryEscrowRecord item : eligibleItems) {
            merchantNetTotals.merge(item.merchantId(), item.netPayablePesewas(), Long::sum);
        }

        for (Map.Entry<UUID, Long> entry : merchantNetTotals.entrySet()) {
            UUID mId = entry.getKey();
            long amount = entry.getValue();
            if (amount > 0) {
                // Ensure accounts exist
                ledger.open("merchant", mId, "pending", cur);
                ledger.open("merchant", mId, "available", cur);

                legs.add(Leg.debit(Ledger.code("merchant", mId, "pending", cur), amount));
                legs.add(Leg.credit(Ledger.code("merchant", mId, "available", cur), amount));
            }
        }

        var posted = ledger.post(Posting.of(
                "enterprise:settlement",
                batchRef,
                "settlement:release",
                legs
        ).describedAs("Conditional settlement release for batch " + batchRef));

        // 4. Update escrow records to RELEASED
        for (DeliveryEscrowRecord item : eligibleItems) {
            escrowService.markReleased(item.id(), posted.transactionId());
        }

        // 5. Create settlement batch record
        db.sql("""
                INSERT INTO enterprise_settlement_batch (
                    id, enterprise_id, batch_reference, settlement_type,
                    total_merchants, total_items, total_gross_pesewas,
                    total_net_pesewas, total_commission_pesewas, currency,
                    status, shortfall_detected, ledger_tx_id, completed_at
                ) VALUES (
                    :id, :enterpriseId, :batchRef, :type,
                    :merchants, :items, :gross,
                    :net, :comm, :cur,
                    'RELEASED', false, :txId, now()
                )
                """)
                .param("id", batchId)
                .param("enterpriseId", enterpriseId)
                .param("batchRef", batchRef)
                .param("type", rule.scheduleType().name())
                .param("merchants", uniqueMerchants.size())
                .param("items", eligibleItems.size())
                .param("gross", totalGross)
                .param("net", totalNet)
                .param("comm", totalCommission)
                .param("cur", cur)
                .param("txId", posted.transactionId())
                .update();

        // 6. Record batch items
        for (DeliveryEscrowRecord item : eligibleItems) {
            db.sql("""
                    INSERT INTO enterprise_settlement_batch_item (
                        id, batch_id, escrow_id, merchant_id, order_reference,
                        item_reference, amount_pesewas, status, reason
                    ) VALUES (
                        :id, :batchId, :escrowId, :merchantId, :orderRef,
                        :itemRef, :amt, 'RELEASED', 'DELIVERY_CONFIRMED'
                    )
                    """)
                    .param("id", UUID.randomUUID())
                    .param("batchId", batchId)
                    .param("escrowId", item.id())
                    .param("merchantId", item.merchantId())
                    .param("orderRef", item.orderReference())
                    .param("itemRef", item.itemReference())
                    .param("amt", item.netPayablePesewas())
                    .update();
        }

        return new SettlementReleaseResult(
                batchId,
                batchRef,
                rule.scheduleType(),
                uniqueMerchants.size(),
                eligibleItems.size(),
                heldItems.size(),
                totalNet,
                totalCommission,
                cur,
                posted.transactionId(),
                null,
                false,
                "RELEASED",
                eligibleItems,
                heldItems
        );
    }

    /**
     * Executes payout batch for released merchant balances to their designated destination accounts.
     */
    @Transactional
    public UUID executeMerchantPayoutBatch(UUID enterpriseId, List<UUID> merchantIds, String currency) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : GHS;
        UUID batchId = UUID.randomUUID();

        // Check each merchant's available balance and initiate payout
        for (UUID mId : merchantIds) {
            long avail = ledger.balance(Ledger.code("merchant", mId, "available", cur));
            if (avail > 0) {
                // Reserve available balance for payout
                String availAcc = Ledger.code("merchant", mId, "available", cur);
                String reservedAcc = Ledger.code("merchant", mId, "reserved", cur);
                ledger.open("merchant", mId, "reserved", cur);

                ledger.post(Posting.of(
                        "merchant:payout",
                        "PAYOUT-" + UUID.randomUUID(),
                        "payout:reserve",
                        List.of(
                                Leg.debit(availAcc, avail),
                                Leg.credit(reservedAcc, avail)
                        )
                ).describedAs("Payout reserve for merchant " + mId));
            }
        }

        return batchId;
    }

    private SettlementRule mapRule(ResultSet rs, int rowNum) throws SQLException {
        return new SettlementRule(
                rs.getObject("id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                SettlementScheduleType.valueOf(rs.getString("schedule_type")),
                rs.getInt("settlement_timing_days"),
                rs.getLong("min_threshold_pesewas"),
                rs.getBoolean("delivery_condition_required"),
                rs.getInt("delivery_timeout_days"),
                NetGrossMode.valueOf(rs.getString("net_gross_mode")),
                rs.getString("currency"),
                rs.getBoolean("auto_payout_enabled"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private DeliveryEscrowRecord mapEscrowRecord(ResultSet rs, int rowNum) throws SQLException {
        return new DeliveryEscrowRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.getString("order_reference"),
                rs.getString("item_reference"),
                rs.getObject("merchant_id", UUID.class),
                rs.getLong("gross_amount_pesewas"),
                rs.getLong("net_payable_pesewas"),
                rs.getLong("commission_pesewas"),
                rs.getString("currency"),
                DeliveryStatus.valueOf(rs.getString("delivery_status")),
                EscrowStatus.valueOf(rs.getString("escrow_status")),
                rs.getObject("delivered_at", OffsetDateTime.class),
                rs.getObject("released_at", OffsetDateTime.class),
                rs.getObject("ledger_hold_tx_id", UUID.class),
                rs.getObject("ledger_release_tx_id", UUID.class),
                rs.getString("notes"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
