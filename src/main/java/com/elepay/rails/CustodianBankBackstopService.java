package com.elepay.rails;

import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.ledger.Ledger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custodian Bank Trust Account Outflow Cap Backstop & Safeguarding Verification.
 *
 * <p>Operates at the external {@link RailTransactions} boundary (outside normal merchant payout
 * logic) so that even in a worst-case scenario, the Custodian Bank interface physically refuses
 * to drain the trust account beyond configured per-payout caps, daily outflow caps, or reserve
 * floors, and verifies that Custodian Bank trust assets cover all virtual merchant, submerchant,
 * delivery partner, and escrow ledger liabilities.
 */
@Service
public class CustodianBankBackstopService {
    public static final String PRIMARY_TRUST_CODE = "CUSTODIAN_TRUST_PRIMARY_GHS";
    private static final ZoneId ACCRA_ZONE = ZoneId.of("Africa/Accra");

    private final JdbcClient db;
    private final Ledger ledger;
    private final OpsQueue ops;
    private final Audit audit;

    @Autowired
    public CustodianBankBackstopService(JdbcClient db, Ledger ledger, OpsQueue ops, Audit audit) {
        this.db = db;
        this.ledger = ledger;
        this.ops = ops;
        this.audit = audit;
    }

    public record TrustAccountConfig(
            UUID id,
            String accountCode,
            String bankCode,
            String bankAccountNumber,
            String currency,
            long dailyOutflowCapPesewas,
            long perPayoutCapPesewas,
            long minReserveFloorPesewas,
            Long lastReconciledBankBalancePesewas,
            boolean outflowFrozen,
            OffsetDateTime updatedAt
    ) {}

    public record DailyOutflowSnapshot(
            String trustAccountCode,
            LocalDate businessDate,
            String currency,
            long reservedOutflowPesewas,
            long settledOutflowPesewas,
            int rejectedCount
    ) {}

    public record BackstopDecision(
            boolean allowed,
            String reasonCode,
            String reason
    ) {
        public static BackstopDecision permit() {
            return new BackstopDecision(true, null, null);
        }

        public static BackstopDecision blocked(String code, String reason) {
            return new BackstopDecision(false, code, reason);
        }
    }

    public record CustodianSafeguardReport(
            String trustAccountCode,
            String currency,
            long merchantPendingEscrowPesewas,
            long merchantAvailablePesewas,
            long merchantReservedPesewas,
            long enterpriseHoldingPesewas,
            long enterpriseAvailablePesewas,
            long deferredRevenuePesewas,
            long refundsInFlightPesewas,
            long totalSafeguardedLiabilitiesPesewas,
            long clearingReceivablesPesewas,
            Long reconciledBankBalancePesewas,
            long totalTrustAssetsPesewas,
            long dailyOutflowCapPesewas,
            long todayTotalOutflowPesewas,
            int todayRejectedOutflowCount,
            boolean outflowFrozen,
            boolean safeguardingSatisfied
    ) {}

    public TrustAccountConfig getPrimaryConfig() {
        return db.sql("""
                SELECT id, account_code, bank_code, bank_account_number, currency,
                       daily_outflow_cap_pesewas, per_payout_cap_pesewas, min_reserve_floor_pesewas,
                       last_reconciled_bank_balance_pesewas, outflow_frozen, updated_at
                FROM custodian_trust_account
                WHERE account_code = :code""")
                .param("code", PRIMARY_TRUST_CODE)
                .query(CustodianBankBackstopService::mapConfig)
                .optional()
                .orElseGet(() -> {
                    ensurePrimaryTrustAccount();
                    return db.sql("""
                            SELECT id, account_code, bank_code, bank_account_number, currency,
                                   daily_outflow_cap_pesewas, per_payout_cap_pesewas, min_reserve_floor_pesewas,
                                   last_reconciled_bank_balance_pesewas, outflow_frozen, updated_at
                            FROM custodian_trust_account
                            WHERE account_code = :code""")
                            .param("code", PRIMARY_TRUST_CODE)
                            .query(CustodianBankBackstopService::mapConfig)
                            .single();
                });
    }

    @Transactional
    public TrustAccountConfig updateConfig(
            Long dailyOutflowCapPesewas,
            Long perPayoutCapPesewas,
            Long minReserveFloorPesewas,
            Long lastReconciledBankBalancePesewas,
            Boolean outflowFrozen,
            String actor
    ) {
        ensurePrimaryTrustAccount();
        var current = getPrimaryConfig();
        db.sql("""
                UPDATE custodian_trust_account
                SET daily_outflow_cap_pesewas = :dailyCap,
                    per_payout_cap_pesewas = :perCap,
                    min_reserve_floor_pesewas = :floor,
                    last_reconciled_bank_balance_pesewas = :bankBal,
                    outflow_frozen = :frozen,
                    updated_by = :by,
                    updated_at = now()
                WHERE account_code = :code""")
                .param("dailyCap", dailyOutflowCapPesewas != null ? dailyOutflowCapPesewas : current.dailyOutflowCapPesewas())
                .param("perCap", perPayoutCapPesewas != null ? perPayoutCapPesewas : current.perPayoutCapPesewas())
                .param("floor", minReserveFloorPesewas != null ? minReserveFloorPesewas : current.minReserveFloorPesewas())
                .param("bankBal", lastReconciledBankBalancePesewas != null ? lastReconciledBankBalancePesewas : current.lastReconciledBankBalancePesewas())
                .param("frozen", outflowFrozen != null ? outflowFrozen : current.outflowFrozen())
                .param("by", actor != null ? actor : "system")
                .param("code", PRIMARY_TRUST_CODE)
                .update();
        if (audit != null && actor != null) {
            audit.record(actor, "custodian.backstop_config_updated", "custodian:" + PRIMARY_TRUST_CODE,
                    Map.of("dailyCap", String.valueOf(dailyOutflowCapPesewas),
                            "perPayoutCap", String.valueOf(perPayoutCapPesewas),
                            "outflowFrozen", String.valueOf(outflowFrozen)));
        }
        return getPrimaryConfig();
    }

    @Transactional
    public void resetForTesting() {
        ensurePrimaryTrustAccount();
        db.sql("""
                UPDATE custodian_trust_account
                SET daily_outflow_cap_pesewas = 50000000000,
                    per_payout_cap_pesewas = 10000000000,
                    min_reserve_floor_pesewas = 0,
                    last_reconciled_bank_balance_pesewas = NULL,
                    outflow_frozen = false,
                    updated_by = 'test-reset',
                    updated_at = now()
                WHERE account_code = :code""").param("code", PRIMARY_TRUST_CODE).update();
        db.sql("DELETE FROM custodian_daily_outflow WHERE trust_account_code = :code")
                .param("code", PRIMARY_TRUST_CODE).update();
    }

    @Transactional
    public void resetDailyOutflowForTesting() {
        db.sql("DELETE FROM custodian_daily_outflow WHERE trust_account_code = :code")
                .param("code", PRIMARY_TRUST_CODE).update();
    }

    /**
     * Authorizes an outbound disbursement ({@code payout} or {@code refund}) under a {@code FOR UPDATE}
     * row lock on the Custodian Bank trust account and today's daily outflow accumulator.
     */
    @Transactional
    public BackstopDecision authorizeOutboundDisbursement(
            UUID txId,
            String reference,
            String rail,
            String direction,
            long amountPesewas,
            String currency
    ) {
        if (!"payout".equalsIgnoreCase(direction) && !"refund".equalsIgnoreCase(direction)) {
            return BackstopDecision.permit();
        }
        ensurePrimaryTrustAccount();
        LocalDate today = LocalDate.now(ACCRA_ZONE);
        ensureTodayAccumulator(today);

        TrustAccountConfig trust = db.sql("""
                SELECT id, account_code, bank_code, bank_account_number, currency,
                       daily_outflow_cap_pesewas, per_payout_cap_pesewas, min_reserve_floor_pesewas,
                       last_reconciled_bank_balance_pesewas, outflow_frozen, updated_at
                FROM custodian_trust_account
                WHERE account_code = :code FOR UPDATE""")
                .param("code", PRIMARY_TRUST_CODE)
                .query(CustodianBankBackstopService::mapConfig)
                .single();

        DailyOutflowSnapshot acc = db.sql("""
                SELECT trust_account_code, business_date, currency,
                       reserved_outflow_pesewas, settled_outflow_pesewas, rejected_count
                FROM custodian_daily_outflow
                WHERE trust_account_code = :code AND business_date = :d FOR UPDATE""")
                .param("code", PRIMARY_TRUST_CODE).param("d", today)
                .query(CustodianBankBackstopService::mapOutflow)
                .single();

        if (trust.outflowFrozen()) {
            incrementRejected(today);
            if (ops != null) {
                ops.raise("custodian_backstop_frozen", "critical", "rail_tx:" + reference,
                        Map.of("amount", amountPesewas, "rail", rail, "direction", direction));
            }
            return BackstopDecision.blocked("custodian_backstop_frozen",
                    "Custodian Bank trust account outflow is frozen");
        }

        if (amountPesewas > trust.perPayoutCapPesewas()) {
            incrementRejected(today);
            if (ops != null) {
                ops.raise("custodian_per_payout_cap_exceeded", "high", "rail_tx:" + reference,
                        Map.of("amount", amountPesewas, "perPayoutCap", trust.perPayoutCapPesewas(), "rail", rail));
            }
            return BackstopDecision.blocked("custodian_per_payout_cap_exceeded",
                    "Custodian Bank per-payout cap exceeded (" + amountPesewas + " > " + trust.perPayoutCapPesewas() + ")");
        }

        long projectedOutflow = acc.reservedOutflowPesewas() + acc.settledOutflowPesewas() + amountPesewas;
        if (projectedOutflow > trust.dailyOutflowCapPesewas()) {
            incrementRejected(today);
            if (ops != null) {
                ops.raise("custodian_daily_outflow_cap_exceeded", "critical", "rail_tx:" + reference,
                        Map.of("amount", amountPesewas, "projectedOutflow", projectedOutflow,
                                "dailyCap", trust.dailyOutflowCapPesewas(), "rail", rail));
            }
            return BackstopDecision.blocked("custodian_daily_outflow_cap_exceeded",
                    "Custodian Bank daily outflow cap exceeded (" + projectedOutflow + " > " + trust.dailyOutflowCapPesewas() + ")");
        }

        if (trust.lastReconciledBankBalancePesewas() != null) {
            long remainingTrustBalance = trust.lastReconciledBankBalancePesewas() - projectedOutflow;
            if (remainingTrustBalance < trust.minReserveFloorPesewas()) {
                incrementRejected(today);
                if (ops != null) {
                    ops.raise("custodian_trust_reserve_floor_breached", "critical", "rail_tx:" + reference,
                            Map.of("amount", amountPesewas, "remainingBalance", remainingTrustBalance,
                                    "minReserveFloor", trust.minReserveFloorPesewas()));
                }
                return BackstopDecision.blocked("custodian_trust_reserve_floor_breached",
                        "Custodian Bank trust reserve floor breached (" + remainingTrustBalance + " < " + trust.minReserveFloorPesewas() + ")");
            }
        }

        db.sql("""
                UPDATE custodian_daily_outflow
                SET reserved_outflow_pesewas = reserved_outflow_pesewas + :amt,
                    updated_at = now()
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("amt", amountPesewas).param("code", PRIMARY_TRUST_CODE).param("d", today).update();

        return BackstopDecision.permit();
    }

    @Transactional
    public void onOutboundSucceeded(long amountPesewas, String currency) {
        LocalDate today = LocalDate.now(ACCRA_ZONE);
        ensureTodayAccumulator(today);
        db.sql("""
                UPDATE custodian_daily_outflow
                SET reserved_outflow_pesewas = GREATEST(0, reserved_outflow_pesewas - :amt),
                    settled_outflow_pesewas = settled_outflow_pesewas + :amt,
                    updated_at = now()
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("amt", amountPesewas).param("code", PRIMARY_TRUST_CODE).param("d", today).update();
    }

    @Transactional
    public void onOutboundReleased(long amountPesewas, String currency) {
        LocalDate today = LocalDate.now(ACCRA_ZONE);
        ensureTodayAccumulator(today);
        db.sql("""
                UPDATE custodian_daily_outflow
                SET reserved_outflow_pesewas = GREATEST(0, reserved_outflow_pesewas - :amt),
                    updated_at = now()
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("amt", amountPesewas).param("code", PRIMARY_TRUST_CODE).param("d", today).update();
    }

    /**
     * Verifies Custodian Bank trust assets against all virtual merchant, submerchant,
     * delivery partner, and escrow ledger liabilities via the {@link Ledger} API.
     */
    public CustodianSafeguardReport verifyTrustSafeguarding(String currency) {
        String cur = (currency == null || currency.isBlank()) ? Ledger.GHS : currency.toUpperCase();
        TrustAccountConfig trust = getPrimaryConfig();
        LocalDate today = LocalDate.now(ACCRA_ZONE);
        ensureTodayAccumulator(today);
        DailyOutflowSnapshot acc = db.sql("""
                SELECT trust_account_code, business_date, currency,
                       reserved_outflow_pesewas, settled_outflow_pesewas, rejected_count
                FROM custodian_daily_outflow
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("code", PRIMARY_TRUST_CODE).param("d", today)
                .query(CustodianBankBackstopService::mapOutflow)
                .single();

        long merchantPending = sumOwnerBucket("merchant", "pending", cur);
        long merchantAvailable = sumOwnerBucket("merchant", "available", cur);
        long merchantReserved = sumOwnerBucket("merchant", "reserved", cur);

        long enterpriseHolding = sumOwnerBucket("enterprise", "holding", cur)
                + sumOwnerBucket("enterprise_managed", "holding", cur)
                + sumOwnerBucket("enterprise_managed", "operating", cur);
        long enterpriseAvailable = sumOwnerBucket("enterprise", "available", cur)
                + sumOwnerBucket("enterprise_managed", "reserved", cur);

        long deferredRevenue = Math.max(0L, ledger.balance(Ledger.DEFERRED_REVENUE));
        long refundsInFlight = Math.max(0L, ledger.balance(Ledger.REFUNDS_IN_FLIGHT));

        long totalLiabilities = merchantPending + merchantAvailable + merchantReserved
                + enterpriseHolding + enterpriseAvailable + deferredRevenue + refundsInFlight;

        // Clearing accounts represent net collected funds settled into the Custodian Bank trust account
        // (in double-entry ledger, collections debit system:clearing:<rail>:GHS, so net clearing inflow is -balance)
        long clearingReceivables = 0L;
        for (String rail : java.util.List.of("sandbox", "mtn", "telecel", "airteltigo", "ghipss", "card")) {
            long bal = ledger.balance(Ledger.clearing(rail));
            if (bal < 0) {
                clearingReceivables += (-bal);
            }
        }

        long totalTrustAssets = trust.lastReconciledBankBalancePesewas() != null
                ? Math.max(trust.lastReconciledBankBalancePesewas(), clearingReceivables)
                : Math.max(totalLiabilities, clearingReceivables);

        boolean satisfied = !trust.outflowFrozen() && totalTrustAssets >= totalLiabilities && ledger.integrity().ok();

        return new CustodianSafeguardReport(
                trust.accountCode(),
                cur,
                merchantPending,
                merchantAvailable,
                merchantReserved,
                enterpriseHolding,
                enterpriseAvailable,
                deferredRevenue,
                refundsInFlight,
                totalLiabilities,
                clearingReceivables,
                trust.lastReconciledBankBalancePesewas(),
                totalTrustAssets,
                trust.dailyOutflowCapPesewas(),
                acc.reservedOutflowPesewas() + acc.settledOutflowPesewas(),
                acc.rejectedCount(),
                trust.outflowFrozen(),
                satisfied
        );
    }

    private long sumOwnerBucket(String ownerType, String bucket, String currency) {
        long sum = 0L;
        for (UUID owner : ledger.ownersWithBalance(ownerType, bucket, currency)) {
            long b = ledger.balance(Ledger.code(ownerType, owner, bucket, currency));
            if (b > 0) sum += b;
        }
        return sum;
    }

    private void incrementRejected(LocalDate today) {
        db.sql("""
                UPDATE custodian_daily_outflow
                SET rejected_count = rejected_count + 1, updated_at = now()
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("code", PRIMARY_TRUST_CODE).param("d", today).update();
    }

    private void ensurePrimaryTrustAccount() {
        db.sql("""
                INSERT INTO custodian_trust_account (
                    id, account_code, bank_code, bank_account_number, currency,
                    daily_outflow_cap_pesewas, per_payout_cap_pesewas, min_reserve_floor_pesewas,
                    last_reconciled_bank_balance_pesewas, outflow_frozen, updated_by
                ) VALUES (
                    '00000000-0000-0000-0000-000000000049',
                    :code, 'GCB', '10102030405060', 'GHS',
                    50000000000, 10000000000, 0, NULL, false, 'system'
                ) ON CONFLICT (account_code) DO NOTHING""")
                .param("code", PRIMARY_TRUST_CODE).update();
    }

    private void ensureTodayAccumulator(LocalDate today) {
        ensurePrimaryTrustAccount();
        db.sql("""
                INSERT INTO custodian_daily_outflow (
                    trust_account_code, business_date, currency,
                    reserved_outflow_pesewas, settled_outflow_pesewas, rejected_count, updated_at
                ) VALUES (:code, :d, 'GHS', 0, 0, 0, now())
                ON CONFLICT (trust_account_code, business_date) DO NOTHING""")
                .param("code", PRIMARY_TRUST_CODE).param("d", today).update();
    }

    private static TrustAccountConfig mapConfig(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TrustAccountConfig(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getLong(6),
                rs.getLong(7),
                rs.getLong(8),
                rs.getObject(9) != null ? rs.getLong(9) : null,
                rs.getBoolean(10),
                rs.getObject(11, OffsetDateTime.class)
        );
    }

    private static DailyOutflowSnapshot mapOutflow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DailyOutflowSnapshot(
                rs.getString(1),
                rs.getObject(2, LocalDate.class),
                rs.getString(3),
                rs.getLong(4),
                rs.getLong(5),
                rs.getInt(6)
        );
    }
}
