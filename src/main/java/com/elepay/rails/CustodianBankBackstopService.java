package com.elepay.rails;

import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.ledger.Ledger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
 * customer, delivery partner, enterprise, and escrow ledger liabilities.
 */
@Service
public class CustodianBankBackstopService {
    public static final String PRIMARY_TRUST_CODE = "CUSTODIAN_TRUST_PRIMARY_GHS";
    private static final ZoneId ACCRA_ZONE = ZoneId.of("Africa/Accra");
    public static final long DEFAULT_BALANCE_MAX_AGE_SECONDS = 86_400L;

    private final JdbcClient db;
    private final Ledger ledger;
    private final OpsQueue ops;
    private final Audit audit;
    private final Environment env;
    private volatile long balanceMaxAgeSeconds;

    public CustodianBankBackstopService(JdbcClient db, Ledger ledger, OpsQueue ops, Audit audit) {
        this(db, ledger, ops, audit, null, DEFAULT_BALANCE_MAX_AGE_SECONDS);
    }

    @Autowired
    public CustodianBankBackstopService(
            JdbcClient db,
            Ledger ledger,
            OpsQueue ops,
            Audit audit,
            @Autowired(required = false) Environment env,
            @Value("${elepay.custodian.balance-max-age-seconds:86400}") long balanceMaxAgeSeconds
    ) {
        this.db = db;
        this.ledger = ledger;
        this.ops = ops;
        this.audit = audit;
        this.env = env;
        this.balanceMaxAgeSeconds = Math.max(1L, balanceMaxAgeSeconds);
    }

    public long getBalanceMaxAgeSeconds() {
        return balanceMaxAgeSeconds;
    }

    public void setBalanceMaxAgeSeconds(long seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("balanceMaxAgeSeconds must be positive");
        }
        this.balanceMaxAgeSeconds = seconds;
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
            OffsetDateTime updatedAt,
            OffsetDateTime lastReconciledAt,
            String lastStatementReference
    ) {
        public TrustAccountConfig(
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
        ) {
            this(id, accountCode, bankCode, bankAccountNumber, currency,
                    dailyOutflowCapPesewas, perPayoutCapPesewas, minReserveFloorPesewas,
                    lastReconciledBankBalancePesewas, outflowFrozen, updatedAt,
                    lastReconciledBankBalancePesewas != null ? updatedAt : null, null);
        }
    }

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

    private static final String SELECT_TRUST_CONFIG = """
            SELECT id, account_code, bank_code, bank_account_number, currency,
                   daily_outflow_cap_pesewas, per_payout_cap_pesewas, min_reserve_floor_pesewas,
                   last_reconciled_bank_balance_pesewas, outflow_frozen, updated_at,
                   last_reconciled_at, last_statement_reference
            FROM custodian_trust_account
            WHERE account_code = :code""";

    public TrustAccountConfig getPrimaryConfig() {
        return db.sql(SELECT_TRUST_CONFIG)
                .param("code", PRIMARY_TRUST_CODE)
                .query(CustodianBankBackstopService::mapConfig)
                .optional()
                .orElseGet(() -> {
                    ensurePrimaryTrustAccount();
                    return db.sql(SELECT_TRUST_CONFIG)
                            .param("code", PRIMARY_TRUST_CODE)
                            .query(CustodianBankBackstopService::mapConfig)
                            .single();
                });
    }

    /**
     * Returns {@code true} if the primary trust account has a reconciled bank balance whose
     * {@code last_reconciled_at} timestamp is within {@link #balanceMaxAgeSeconds}.
     */
    public boolean isBalanceFresh(TrustAccountConfig trust) {
        if (trust == null || trust.lastReconciledBankBalancePesewas() == null) {
            return false;
        }
        OffsetDateTime reconciledAt = trust.lastReconciledAt() != null ? trust.lastReconciledAt() : trust.updatedAt();
        if (reconciledAt == null) {
            return false;
        }
        long ageSeconds = Duration.between(reconciledAt, OffsetDateTime.now()).getSeconds();
        return ageSeconds <= balanceMaxAgeSeconds;
    }

    /**
     * Computes outbound disbursements not yet reflected in {@code last_reconciled_bank_balance_pesewas}:
     * all currently reserved (in-flight) outflows across all business dates plus any outbound rail
     * disbursements that settled strictly after {@code last_reconciled_at}.
     *
     * <p>This eliminates both (a) double-deduction of outflows already settled before the latest
     * H2H bank balance sync and (b) midnight-rollover phantom headroom when prior-day outflows
     * settled after the last reconciliation.
     */
    public long unreconciledOutflowsPesewas(TrustAccountConfig trust, String currency) {
        String cur = (currency == null || currency.isBlank()) ? Ledger.GHS : currency.toUpperCase(Locale.ROOT);
        Long allReserved = db.sql("""
                SELECT COALESCE(SUM(reserved_outflow_pesewas), 0)::bigint
                FROM custodian_daily_outflow
                WHERE trust_account_code = :code""")
                .param("code", PRIMARY_TRUST_CODE)
                .query(Long.class)
                .single();
        long reserved = allReserved == null ? 0L : Math.max(0L, allReserved);

        OffsetDateTime reconciledAt = trust != null
                ? (trust.lastReconciledAt() != null ? trust.lastReconciledAt() : trust.updatedAt())
                : null;
        if (reconciledAt == null) {
            return reserved;
        }
        Long settledSinceReconcile = db.sql("""
                SELECT COALESCE(SUM(amount), 0)::bigint
                FROM rail_transaction
                WHERE direction IN ('payout', 'refund')
                  AND status = 'succeeded'
                  AND upper(currency) = :cur
                  AND completed_at IS NOT NULL
                  AND completed_at > :reconciledAt""")
                .param("cur", cur)
                .param("reconciledAt", reconciledAt)
                .query(Long.class)
                .single();
        long settledAfter = settledSinceReconcile == null ? 0L : Math.max(0L, settledSinceReconcile);
        return Math.addExact(reserved, settledAfter);
    }

    @Transactional
    public TrustAccountConfig recordReconciledBalance(
            long reconciledBankBalancePesewas,
            String statementReference,
            String actor
    ) {
        if (reconciledBankBalancePesewas < 0) {
            throw com.elepay.common.ApiException.badRequest(
                    "invalid_custodian_config", "Reconciled bank balance cannot be negative");
        }
        ensurePrimaryTrustAccount();
        String effectiveActor = (actor != null && !actor.isBlank()) ? actor : "system";
        db.sql("""
                UPDATE custodian_trust_account
                SET last_reconciled_bank_balance_pesewas = :bankBal,
                    last_reconciled_at = now(),
                    last_statement_reference = COALESCE(:stmtRef, last_statement_reference),
                    updated_by = :by,
                    updated_at = now()
                WHERE account_code = :code""")
                .param("bankBal", reconciledBankBalancePesewas)
                .param("stmtRef", statementReference)
                .param("by", effectiveActor)
                .param("code", PRIMARY_TRUST_CODE)
                .update();
        if (audit != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("lastReconciledBankBalancePesewas", reconciledBankBalancePesewas);
            if (statementReference != null) {
                details.put("statementReference", statementReference);
            }
            audit.record(effectiveActor, "custodian.trust_balance_reconciled",
                    "custodian:" + PRIMARY_TRUST_CODE, details);
        }
        return getPrimaryConfig();
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
        TrustAccountConfig current = db.sql(SELECT_TRUST_CONFIG + " FOR UPDATE")
                .param("code", PRIMARY_TRUST_CODE)
                .query(CustodianBankBackstopService::mapConfig)
                .single();
        long effectiveDailyCap = dailyOutflowCapPesewas != null ? dailyOutflowCapPesewas : current.dailyOutflowCapPesewas();
        long effectivePerPayoutCap = perPayoutCapPesewas != null ? perPayoutCapPesewas : current.perPayoutCapPesewas();
        long effectiveFloor = minReserveFloorPesewas != null ? minReserveFloorPesewas : current.minReserveFloorPesewas();
        boolean balanceUpdated = lastReconciledBankBalancePesewas != null;
        Long effectiveBankBal = balanceUpdated
                ? lastReconciledBankBalancePesewas : current.lastReconciledBankBalancePesewas();
        boolean effectiveFrozen = outflowFrozen != null ? outflowFrozen : current.outflowFrozen();
        if (effectiveDailyCap < 0 || effectivePerPayoutCap < 0 || effectiveFloor < 0
                || (effectiveBankBal != null && effectiveBankBal < 0)) {
            throw com.elepay.common.ApiException.badRequest(
                    "invalid_custodian_config", "Caps, floors and balances cannot be negative");
        }
        if (effectivePerPayoutCap > effectiveDailyCap) {
            throw com.elepay.common.ApiException.badRequest(
                    "invalid_custodian_config", "Per-payout cap cannot exceed the daily outflow cap");
        }
        String effectiveActor = (actor != null && !actor.isBlank()) ? actor : "system";
        db.sql("""
                UPDATE custodian_trust_account
                SET daily_outflow_cap_pesewas = :dailyCap,
                    per_payout_cap_pesewas = :perCap,
                    min_reserve_floor_pesewas = :floor,
                    last_reconciled_bank_balance_pesewas = :bankBal,
                    last_reconciled_at = CASE WHEN :balUpdated THEN now() ELSE last_reconciled_at END,
                    outflow_frozen = :frozen,
                    updated_by = :by,
                    updated_at = now()
                WHERE account_code = :code""")
                .param("dailyCap", effectiveDailyCap)
                .param("perCap", effectivePerPayoutCap)
                .param("floor", effectiveFloor)
                .param("bankBal", effectiveBankBal)
                .param("balUpdated", balanceUpdated)
                .param("frozen", effectiveFrozen)
                .param("by", effectiveActor)
                .param("code", PRIMARY_TRUST_CODE)
                .update();
        if (audit != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("dailyCap", String.valueOf(effectiveDailyCap));
            details.put("perPayoutCap", String.valueOf(effectivePerPayoutCap));
            details.put("minReserveFloor", String.valueOf(effectiveFloor));
            details.put("lastReconciledBankBalance", String.valueOf(effectiveBankBal));
            details.put("outflowFrozen", String.valueOf(effectiveFrozen));
            audit.record(effectiveActor, "custodian.backstop_config_updated",
                    "custodian:" + PRIMARY_TRUST_CODE, details);
        }
        return getPrimaryConfig();
    }

    /** Test-only reset helper; blocked fail-closed in production profiles. */
    @Transactional
    public void resetForTesting() {
        assertNotProduction("resetForTesting");
        ensurePrimaryTrustAccount();
        db.sql("""
                UPDATE custodian_trust_account
                SET daily_outflow_cap_pesewas = 50000000000,
                    per_payout_cap_pesewas = 10000000000,
                    min_reserve_floor_pesewas = 0,
                    last_reconciled_bank_balance_pesewas = NULL,
                    last_reconciled_at = NULL,
                    last_statement_reference = NULL,
                    outflow_frozen = false,
                    updated_by = 'test-reset',
                    updated_at = now()
                WHERE account_code = :code""").param("code", PRIMARY_TRUST_CODE).update();
        db.sql("DELETE FROM custodian_daily_outflow WHERE trust_account_code = :code")
                .param("code", PRIMARY_TRUST_CODE).update();
        this.balanceMaxAgeSeconds = DEFAULT_BALANCE_MAX_AGE_SECONDS;
    }

    /** Test-only daily outflow reset helper; blocked fail-closed in production profiles. */
    @Transactional
    public void resetDailyOutflowForTesting() {
        assertNotProduction("resetDailyOutflowForTesting");
        db.sql("DELETE FROM custodian_daily_outflow WHERE trust_account_code = :code")
                .param("code", PRIMARY_TRUST_CODE).update();
    }

    private void assertNotProduction(String method) {
        if (env != null && env.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new UnsupportedOperationException(method + " is forbidden in production profiles");
        }
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
        if (amountPesewas <= 0) {
            return BackstopDecision.blocked("custodian_invalid_amount",
                    "Outbound disbursement amount must be strictly positive, got: " + amountPesewas);
        }
        String cur = (currency == null || currency.isBlank()) ? Ledger.GHS : currency.toUpperCase(Locale.ROOT);
        if (!Ledger.GHS.equals(cur)) {
            return BackstopDecision.blocked("custodian_unsupported_currency",
                    "Custodian Bank primary trust account only supports GHS, got: " + cur);
        }
        if (txId != null) {
            boolean alreadyReserved = db.sql("SELECT reservation_date FROM rail_transaction WHERE id = :id FOR UPDATE")
                    .param("id", txId)
                    .query((rs, i) -> rs.getObject(1, LocalDate.class) != null)
                    .optional()
                    .orElse(false);
            if (alreadyReserved) {
                return BackstopDecision.permit();
            }
        }
        ensurePrimaryTrustAccount();
        LocalDate today = LocalDate.now(ACCRA_ZONE);
        ensureTodayAccumulator(today);

        TrustAccountConfig trust = db.sql(SELECT_TRUST_CONFIG + " FOR UPDATE")
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

        long projectedDailyOutflow = Math.addExact(
                Math.addExact(acc.reservedOutflowPesewas(), acc.settledOutflowPesewas()),
                amountPesewas);
        if (projectedDailyOutflow > trust.dailyOutflowCapPesewas()) {
            incrementRejected(today);
            if (ops != null) {
                ops.raise("custodian_daily_outflow_cap_exceeded", "critical", "rail_tx:" + reference,
                        Map.of("amount", amountPesewas, "projectedOutflow", projectedDailyOutflow,
                                "dailyCap", trust.dailyOutflowCapPesewas(), "rail", rail));
            }
            return BackstopDecision.blocked("custodian_daily_outflow_cap_exceeded",
                    "Custodian Bank daily outflow cap exceeded (" + projectedDailyOutflow + " > " + trust.dailyOutflowCapPesewas() + ")");
        }

        if (trust.lastReconciledBankBalancePesewas() == null) {
            if (trust.minReserveFloorPesewas() > 0) {
                incrementRejected(today);
                if (ops != null) {
                    ops.raise("custodian_trust_balance_unreconciled", "critical", "rail_tx:" + reference,
                            Map.of("amount", amountPesewas, "minReserveFloor", trust.minReserveFloorPesewas()));
                }
                return BackstopDecision.blocked("custodian_trust_balance_unreconciled",
                        "Custodian Bank trust balance is unreconciled while minReserveFloorPesewas > 0");
            }
        } else {
            if (!isBalanceFresh(trust)) {
                incrementRejected(today);
                if (ops != null) {
                    ops.raise("custodian_trust_balance_stale", "critical", "rail_tx:" + reference,
                            Map.of("amount", amountPesewas, "lastReconciledAt", String.valueOf(trust.lastReconciledAt()),
                                    "maxAgeSeconds", balanceMaxAgeSeconds));
                }
                return BackstopDecision.blocked("custodian_trust_balance_stale",
                        "Custodian Bank trust balance is stale (exceeds max age of " + balanceMaxAgeSeconds + "s)");
            }
            long unreconciled = unreconciledOutflowsPesewas(trust, cur);
            long remainingTrustBalance = trust.lastReconciledBankBalancePesewas()
                    - Math.addExact(unreconciled, amountPesewas);
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

        if (txId != null) {
            db.sql("UPDATE rail_transaction SET reservation_date = :d WHERE id = :id")
                    .param("d", today).param("id", txId).update();
        }
        if (audit != null) {
            audit.record("system:custodian", "custodian.pull_authorized", "rail_tx:" + reference, Map.of(
                    "trustAccount", PRIMARY_TRUST_CODE,
                    "direction", direction,
                    "rail", rail,
                    "amount", amountPesewas,
                    "projectedOutflow", projectedDailyOutflow,
                    "dailyCap", trust.dailyOutflowCapPesewas()));
        }
        return BackstopDecision.permit();
    }

    @Transactional
    public void onOutboundSucceeded(long amountPesewas, String currency) {
        onOutboundSucceeded(null, amountPesewas, currency, LocalDate.now(ACCRA_ZONE));
    }

    @Transactional
    public void onOutboundSucceeded(UUID txId, long amountPesewas, String currency, LocalDate fallbackDate) {
        if (amountPesewas <= 0) return;
        if (txId != null) {
            Optional<LocalDate> recorded = db.sql("""
                    SELECT reservation_date FROM rail_transaction WHERE id = :id FOR UPDATE""")
                    .param("id", txId)
                    .query((rs, i) -> Optional.ofNullable(rs.getObject(1, LocalDate.class)))
                    .optional()
                    .flatMap(o -> o);
            if (recorded.isPresent()) {
                LocalDate bizDate = recorded.get();
                ensureTodayAccumulator(bizDate);
                db.sql("""
                        UPDATE custodian_daily_outflow
                        SET reserved_outflow_pesewas = GREATEST(0, reserved_outflow_pesewas - :amt),
                            settled_outflow_pesewas = settled_outflow_pesewas + :amt,
                            updated_at = now()
                        WHERE trust_account_code = :code AND business_date = :d""")
                        .param("amt", amountPesewas).param("code", PRIMARY_TRUST_CODE).param("d", bizDate).update();
                db.sql("UPDATE rail_transaction SET reservation_date = NULL WHERE id = :id")
                        .param("id", txId).update();
            } else {
                // Transaction was never reserved in custodian_daily_outflow: record settled outflow
                // WITHOUT decrementing reserved_outflow_pesewas (which belongs to other payouts).
                LocalDate bizDate = fallbackDate != null ? fallbackDate : LocalDate.now(ACCRA_ZONE);
                ensureTodayAccumulator(bizDate);
                db.sql("""
                        UPDATE custodian_daily_outflow
                        SET settled_outflow_pesewas = settled_outflow_pesewas + :amt,
                            updated_at = now()
                        WHERE trust_account_code = :code AND business_date = :d""")
                        .param("amt", amountPesewas).param("code", PRIMARY_TRUST_CODE).param("d", bizDate).update();
            }
            return;
        }
        LocalDate bizDate = fallbackDate != null ? fallbackDate : LocalDate.now(ACCRA_ZONE);
        ensureTodayAccumulator(bizDate);
        db.sql("""
                UPDATE custodian_daily_outflow
                SET reserved_outflow_pesewas = GREATEST(0, reserved_outflow_pesewas - :amt),
                    settled_outflow_pesewas = settled_outflow_pesewas + :amt,
                    updated_at = now()
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("amt", amountPesewas).param("code", PRIMARY_TRUST_CODE).param("d", bizDate).update();
    }

    @Transactional
    public void onOutboundReleased(long amountPesewas, String currency) {
        onOutboundReleased(null, amountPesewas, currency, LocalDate.now(ACCRA_ZONE));
    }

    @Transactional
    public void onOutboundReleased(UUID txId, long amountPesewas, String currency, LocalDate fallbackDate) {
        if (amountPesewas <= 0) return;
        if (txId != null) {
            // Atomically claim and clear reservation_date only if this transaction actually reserved
            // daily outflow. Prevents unreserved transactions (reservation_date IS NULL) from
            // decrementing another payout's reserved_outflow_pesewas.
            Optional<LocalDate> reservedDate = db.sql("""
                    SELECT reservation_date FROM rail_transaction WHERE id = :id FOR UPDATE""")
                    .param("id", txId)
                    .query((rs, i) -> Optional.ofNullable(rs.getObject(1, LocalDate.class)))
                    .optional()
                    .flatMap(o -> o);
            if (reservedDate.isEmpty()) {
                return;
            }
            LocalDate bizDate = reservedDate.get();
            ensureTodayAccumulator(bizDate);
            db.sql("""
                    UPDATE custodian_daily_outflow
                    SET reserved_outflow_pesewas = GREATEST(0, reserved_outflow_pesewas - :amt),
                        updated_at = now()
                    WHERE trust_account_code = :code AND business_date = :d""")
                    .param("amt", amountPesewas).param("code", PRIMARY_TRUST_CODE).param("d", bizDate).update();
            db.sql("UPDATE rail_transaction SET reservation_date = NULL WHERE id = :id")
                    .param("id", txId).update();
            return;
        }
        LocalDate bizDate = fallbackDate != null ? fallbackDate : LocalDate.now(ACCRA_ZONE);
        ensureTodayAccumulator(bizDate);
        db.sql("""
                UPDATE custodian_daily_outflow
                SET reserved_outflow_pesewas = GREATEST(0, reserved_outflow_pesewas - :amt),
                    updated_at = now()
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("amt", amountPesewas).param("code", PRIMARY_TRUST_CODE).param("d", bizDate).update();
    }

    /**
     * Reverses a previously settled outbound disbursement (payout/refund returned by the receiving
     * rail). Decrements {@code settled_outflow_pesewas} without touching {@code reserved_outflow_pesewas}
     * so other in-flight disbursements are never affected.
     */
    @Transactional
    public void onOutboundReturned(UUID txId, long amountPesewas, String currency, LocalDate fallbackDate) {
        if (amountPesewas <= 0) return;
        LocalDate bizDate = resolveReservationDate(txId, fallbackDate);
        ensureTodayAccumulator(bizDate);
        db.sql("""
                UPDATE custodian_daily_outflow
                SET settled_outflow_pesewas = GREATEST(0, settled_outflow_pesewas - :amt),
                    updated_at = now()
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("amt", amountPesewas).param("code", PRIMARY_TRUST_CODE).param("d", bizDate).update();
    }

    private LocalDate resolveReservationDate(UUID txId, LocalDate fallbackDate) {
        if (txId != null) {
            Optional<LocalDate> recorded = db.sql("SELECT reservation_date FROM rail_transaction WHERE id = :id")
                    .param("id", txId)
                    .query((rs, i) -> Optional.ofNullable(rs.getObject(1, LocalDate.class)))
                    .optional()
                    .flatMap(o -> o);
            if (recorded.isPresent()) {
                return recorded.get();
            }
        }
        return fallbackDate != null ? fallbackDate : LocalDate.now(ACCRA_ZONE);
    }

    /**
     * Verifies Custodian Bank trust assets against all virtual merchant, submerchant,
     * delivery partner, customer wallet, enterprise managed, suspense, cross-cell, and
     * escrow ledger liabilities via the {@link Ledger} API.
     */
    public CustodianSafeguardReport verifyTrustSafeguarding(String currency) {
        String cur = (currency == null || currency.isBlank()) ? Ledger.GHS : currency.toUpperCase(Locale.ROOT);
        if (!Ledger.GHS.equals(cur)) {
            throw new IllegalArgumentException("Custodian Bank primary trust account only supports GHS, got: " + cur);
        }
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

        long customerPending = sumOwnerBucket("customer", "pending", cur);
        long customerAvailable = sumOwnerBucket("customer", "available", cur);
        long customerReserved = sumOwnerBucket("customer", "reserved", cur);

        long enterpriseHolding = Math.addExact(
                Math.addExact(sumOwnerBucket("enterprise", "holding", cur),
                        sumOwnerBucket("enterprise_managed", "holding", cur)),
                sumOwnerBucket("enterprise_managed", "operating", cur));
        long enterpriseAvailable = 0L;
        for (long component : new long[]{
                sumOwnerBucket("enterprise", "available", cur),
                sumOwnerBucket("enterprise", "reserved", cur),
                sumOwnerBucket("enterprise", "revenue", cur),
                sumOwnerBucket("enterprise_managed", "available", cur),
                sumOwnerBucket("enterprise_managed", "reserved", cur),
                sumOwnerBucket("enterprise_managed", "rewards", cur)
        }) {
            enterpriseAvailable = Math.addExact(enterpriseAvailable, component);
        }

        long deferredRevenue = Math.max(0L, ledger.balance(Ledger.DEFERRED_REVENUE));
        long refundsInFlight = Math.max(0L, ledger.balance(Ledger.REFUNDS_IN_FLIGHT));
        long suspense = Math.max(0L, ledger.balance(Ledger.SUSPENSE));
        long crossCellInTransit = Math.max(0L, ledger.balance("system:cross_cell_in_transit:" + cur));

        long totalLiabilities = 0L;
        for (long component : new long[]{
                merchantPending, merchantAvailable, merchantReserved,
                customerPending, customerAvailable, customerReserved,
                enterpriseHolding, enterpriseAvailable,
                deferredRevenue, refundsInFlight,
                suspense, crossCellInTransit
        }) {
            totalLiabilities = Math.addExact(totalLiabilities, component);
        }

        // Clearing accounts represent net collected funds settled into the Custodian Bank trust account
        // (in double-entry ledger, collections debit system:clearing:<rail>:GHS, so net clearing inflow is -balance)
        long clearingReceivables = 0L;
        for (String rail : java.util.List.of("sandbox", "mtn", "telecel", "airteltigo", "ghipss", "card", "custodian", "bank")) {
            long bal = ledger.balance(Ledger.clearing(rail));
            if (bal < 0) {
                clearingReceivables = Math.addExact(clearingReceivables, -bal);
            }
        }

        long totalTrustAssets = trust.lastReconciledBankBalancePesewas() != null
                ? trust.lastReconciledBankBalancePesewas()
                : clearingReceivables;

        long unreconciledOutflows = trust.lastReconciledBankBalancePesewas() != null
                ? unreconciledOutflowsPesewas(trust, cur)
                : 0L;
        long effectiveTrustAssets = Math.max(0L, totalTrustAssets - unreconciledOutflows);

        boolean satisfied = !trust.outflowFrozen()
                && trust.lastReconciledBankBalancePesewas() != null
                && isBalanceFresh(trust)
                && effectiveTrustAssets >= totalLiabilities
                && ledger.integrity().ok();

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
                Math.addExact(acc.reservedOutflowPesewas(), acc.settledOutflowPesewas()),
                acc.rejectedCount(),
                trust.outflowFrozen(),
                satisfied
        );
    }

    private long sumOwnerBucket(String ownerType, String bucket, String currency) {
        return ledger.sumPositiveOwnerBucket(ownerType, bucket, currency);
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
                rs.getObject(11, OffsetDateTime.class),
                rs.getObject(12, OffsetDateTime.class),
                rs.getString(13)
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
