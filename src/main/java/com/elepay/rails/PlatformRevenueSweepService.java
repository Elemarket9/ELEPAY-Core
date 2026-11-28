package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dual-controlled ELEPAY Platform Revenue Sweep Service.
 *
 * <p>Sweeps <strong>only earned platform revenue</strong> ({@code system:revenue:GHS}) from the
 * Custodian Bank Trust Account ({@code CUSTODIAN_TRUST_PRIMARY_GHS}) to a pre-approved ELEPAY
 * Corporate Operating Bank Account.
 *
 * <p>Hard safeguarding invariants enforced fail-closed under row locks:
 * <ol>
 *   <li>Never sweeps {@code system:deferred_revenue:GHS} (unearned fees remain safeguarded).</li>
 *   <li>Retains a configurable {@code clawbackBufferPesewas} in {@code system:revenue:GHS} for
 *       post-settlement refund fee clawbacks.</li>
 *   <li>Requires {@link CustodianBankBackstopService#verifyTrustSafeguarding} to pass (reconciled
 *       trust balance, not frozen, {@code ledger.integrity().ok()}) and enforces
 *       {@code sweepAmount <= trustHeadroom}.</li>
 *   <li>Disburses only to an active, pre-verified {@code platform_operating_account} through
 *       {@link RailTransactions#submit} and {@link CustodianBankBackstopService}.</li>
 * </ol>
 */
@Service
public class PlatformRevenueSweepService {

    private final JdbcClient db;
    private final Ledger ledger;
    private final RailTransactions rails;
    private final CustodianBankBackstopService custodianBackstop;
    private final Idempotency idempotency;
    private final Audit audit;
    private final OpsQueue ops;
    private final TransactionTemplate tx;
    private final KillSwitchService killSwitch;

    private volatile long clawbackBufferPesewas;

    @Autowired
    public PlatformRevenueSweepService(
            JdbcClient db,
            Ledger ledger,
            RailTransactions rails,
            CustodianBankBackstopService custodianBackstop,
            Idempotency idempotency,
            Audit audit,
            OpsQueue ops,
            PlatformTransactionManager tm,
            @Autowired(required = false) KillSwitchService killSwitch,
            @Value("${elepay.revenue-sweep.clawback-buffer-pesewas:500000}") long clawbackBufferPesewas
    ) {
        this.db = db;
        this.ledger = ledger;
        this.rails = rails;
        this.custodianBackstop = custodianBackstop;
        this.idempotency = idempotency;
        this.audit = audit;
        this.ops = ops;
        this.tx = new TransactionTemplate(tm);
        this.killSwitch = killSwitch;
        this.clawbackBufferPesewas = Math.max(0L, clawbackBufferPesewas);
    }

    public long getClawbackBufferPesewas() {
        return clawbackBufferPesewas;
    }

    public void setClawbackBufferPesewas(long clawbackBufferPesewas) {
        if (clawbackBufferPesewas < 0) {
            throw ApiException.badRequest("invalid_buffer", "Clawback buffer cannot be negative");
        }
        this.clawbackBufferPesewas = clawbackBufferPesewas;
    }

    public record OperatingAccount(
            UUID id,
            String bankCode,
            String accountNumber,
            String accountName,
            String currency,
            String status,
            String verifiedBy,
            OffsetDateTime createdAt
    ) {}

    public record SweepPreview(
            String currency,
            long earnedRevenuePesewas,
            long deferredRevenuePesewas,
            long inFlightRevenueSweepPesewas,
            long clawbackBufferPesewas,
            long maxByRevenuePesewas,
            Long reconciledTrustBalancePesewas,
            long totalSafeguardedLiabilitiesPesewas,
            long todayCustodianOutflowPesewas,
            long minReserveFloorPesewas,
            long trustHeadroomPesewas,
            long maxSweepablePesewas,
            boolean safeguardingSatisfied,
            String blockReason
    ) {}

    public record RevenueSweep(
            UUID id,
            String reference,
            UUID operatingAccountId,
            String currency,
            long amountPesewas,
            long revenueBufferPesewas,
            long trustHeadroomPesewas,
            String status,
            UUID reservationTx,
            UUID completionTx,
            UUID returnTx,
            UUID railTx,
            UUID proposalId,
            String requestedBy,
            String approvedBy,
            String failureReason,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt
    ) {}

    @Transactional
    public OperatingAccount registerOperatingAccount(
            String bankCode,
            String accountNumber,
            String accountName,
            String currency,
            String verifiedBy
    ) {
        String cur = normalizeCurrency(currency);
        if (bankCode == null || !bankCode.matches("^[A-Z0-9]{2,20}$")) {
            throw ApiException.badRequest("invalid_bank_code", "Bank code must be 2-20 uppercase alphanumeric characters");
        }
        if (accountNumber == null || !accountNumber.matches("^[0-9]{6,34}$")) {
            throw ApiException.badRequest("invalid_account_number", "Account number must be 6-34 digits");
        }
        if (accountName == null || accountName.isBlank()) {
            throw ApiException.badRequest("invalid_account_name", "Account name is required");
        }
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO platform_operating_account
                    (id, bank_code, account_number, account_name, currency, status, verified_by)
                VALUES (:id, :bank, :num, :name, :cur, 'active', :by)""")
                .param("id", id)
                .param("bank", bankCode)
                .param("num", accountNumber)
                .param("name", accountName.trim())
                .param("cur", cur)
                .param("by", verifiedBy != null ? verifiedBy : "system")
                .update();
        if (audit != null) {
            audit.record(verifiedBy != null ? verifiedBy : "system",
                    "revenue_sweep.operating_account_registered",
                    "operating_account:" + id,
                    Map.of("bankCode", bankCode, "currency", cur));
        }
        return findOperatingAccount(id).orElseThrow();
    }

    public Optional<OperatingAccount> findOperatingAccount(UUID id) {
        return db.sql("""
                SELECT id, bank_code, account_number, account_name, currency, status, verified_by, created_at
                FROM platform_operating_account WHERE id = :id""")
                .param("id", id)
                .query(PlatformRevenueSweepService::mapOperatingAccount)
                .optional();
    }

    public List<OperatingAccount> listOperatingAccounts(String currency) {
        String cur = normalizeCurrency(currency);
        return db.sql("""
                SELECT id, bank_code, account_number, account_name, currency, status, verified_by, created_at
                FROM platform_operating_account
                WHERE currency = :cur
                ORDER BY created_at DESC""")
                .param("cur", cur)
                .query(PlatformRevenueSweepService::mapOperatingAccount)
                .list();
    }

    /**
     * Computes the exact sweepable earned-revenue ceiling under current safeguarding & buffer rules.
     */
    public SweepPreview previewSweepable(String currency) {
        String cur = normalizeCurrency(currency);
        var report = custodianBackstop.verifyTrustSafeguarding(cur);
        var config = custodianBackstop.getPrimaryConfig();

        long earnedRevenue = Math.max(0L, ledger.balance(Ledger.REVENUE));
        long deferredRevenue = Math.max(0L, ledger.balance(Ledger.DEFERRED_REVENUE));
        long inFlightSweep = Math.max(0L, ledger.balance(Ledger.REVENUE_SWEEP_RESERVED));
        long buffer = this.clawbackBufferPesewas;
        long maxByRevenue = Math.max(0L, earnedRevenue - buffer);

        long trustHeadroom = 0L;
        String blockReason = null;
        if (config.outflowFrozen()) {
            blockReason = "Custodian Bank trust account outflow is frozen";
        } else if (report.reconciledBankBalancePesewas() == null) {
            blockReason = "Custodian Bank trust balance has not been reconciled";
        } else if (!report.safeguardingSatisfied()) {
            blockReason = "Custodian Bank safeguarding check is not satisfied";
        } else {
            long committed = Math.addExact(
                    Math.addExact(report.totalSafeguardedLiabilitiesPesewas(), report.todayTotalOutflowPesewas()),
                    Math.addExact(config.minReserveFloorPesewas(), inFlightSweep));
            trustHeadroom = Math.max(0L, report.reconciledBankBalancePesewas() - committed);
            if (trustHeadroom == 0L) {
                blockReason = "No surplus trust headroom above safeguarded liabilities, outflow, and reserve floor";
            }
        }

        long maxSweepable = (blockReason == null) ? Math.min(maxByRevenue, trustHeadroom) : 0L;
        return new SweepPreview(
                cur,
                earnedRevenue,
                deferredRevenue,
                inFlightSweep,
                buffer,
                maxByRevenue,
                report.reconciledBankBalancePesewas(),
                report.totalSafeguardedLiabilitiesPesewas(),
                report.todayTotalOutflowPesewas(),
                config.minReserveFloorPesewas(),
                trustHeadroom,
                maxSweepable,
                report.safeguardingSatisfied(),
                blockReason
        );
    }

    /**
     * Executes an approved platform earned-revenue sweep from Custodian Trust to ELEPAY's operating bank account.
     * Reserves earned revenue inside a transaction and submits to the payment rail after commit.
     */
    public RevenueSweep executeSweep(
            UUID operatingAccountId,
            long amountPesewas,
            String currency,
            String idempotencyKey,
            String requestedBy,
            String approvedBy,
            UUID proposalId
    ) {
        String cur = normalizeCurrency(currency);
        if (operatingAccountId == null) {
            throw ApiException.badRequest("invalid_operating_account", "Operating account ID is required");
        }
        if (amountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Sweep amount must be strictly positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("missing_idempotency_key", "Idempotency key is required");
        }

        Map<String, Object> fingerprint = Map.of(
                "operatingAccountId", operatingAccountId.toString(),
                "amountPesewas", amountPesewas,
                "currency", cur
        );

        RevenueSweep reserved = tx.execute(s -> idempotency.execute(
                "revenue-sweep:" + cur,
                idempotencyKey,
                fingerprint,
                RevenueSweep.class,
                () -> reserveSweepLocked(operatingAccountId, amountPesewas, cur, requestedBy, approvedBy, proposalId)
        ));

        submitAfterCommit(reserved.id(), reserved.railTx());
        return findSweep(reserved.id()).orElse(reserved);
    }

    private RevenueSweep reserveSweepLocked(
            UUID operatingAccountId,
            long amountPesewas,
            String cur,
            String requestedBy,
            String approvedBy,
            UUID proposalId
    ) {
        if (killSwitch != null) {
            killSwitch.requirePayoutsAllowed(null);
        }

        OperatingAccount dest = findOperatingAccount(operatingAccountId)
                .filter(a -> "active".equals(a.status()) && cur.equals(a.currency().trim()))
                .orElseThrow(() -> ApiException.notFound("Active operating account not found for currency " + cur));

        // Lock custodian_trust_account FOR UPDATE to serialize against concurrent config changes or sweeps
        db.sql("SELECT id FROM custodian_trust_account WHERE account_code = :code FOR UPDATE")
                .param("code", CustodianBankBackstopService.PRIMARY_TRUST_CODE)
                .query(UUID.class)
                .optional();

        var report = custodianBackstop.verifyTrustSafeguarding(cur);
        var config = custodianBackstop.getPrimaryConfig();

        if (config.outflowFrozen()) {
            throw ApiException.conflict("custodian_backstop_frozen", "Custodian Bank trust account outflow is frozen");
        }
        if (report.reconciledBankBalancePesewas() == null) {
            throw ApiException.conflict("custodian_trust_balance_unreconciled",
                    "Custodian Bank trust balance must be reconciled before sweeping platform revenue");
        }
        if (!report.safeguardingSatisfied()) {
            throw ApiException.conflict("custodian_safeguarding_unsatisfied",
                    "Custodian Bank safeguarding invariants are not satisfied; revenue sweep blocked");
        }

        // Lock all system:revenue:GHS base + stripe rows in id order (matches Ledger.lock to prevent deadlocks)
        List<Long> lockedRevenueBalances = db.sql("""
                SELECT balance FROM ledger_account
                WHERE code = :base OR starts_with(code, :prefix)
                ORDER BY id FOR UPDATE""")
                .param("base", Ledger.REVENUE)
                .param("prefix", Ledger.REVENUE + ":s")
                .query(Long.class)
                .list();
        long lockedEarnedRevenue = 0L;
        for (long b : lockedRevenueBalances) {
            if (b > 0) lockedEarnedRevenue = Math.addExact(lockedEarnedRevenue, b);
        }

        long buffer = this.clawbackBufferPesewas;
        long maxByRevenue = Math.max(0L, lockedEarnedRevenue - buffer);
        if (amountPesewas > maxByRevenue) {
            throw ApiException.conflict("revenue_clawback_buffer_breached",
                    "Requested sweep (" + amountPesewas + ") exceeds sweepable earned revenue ("
                            + maxByRevenue + ", retaining clawback buffer of " + buffer + ")");
        }

        long inFlightSweep = db.sql("SELECT balance FROM ledger_account WHERE code = :code FOR UPDATE")
                .param("code", Ledger.REVENUE_SWEEP_RESERVED)
                .query(Long.class)
                .optional()
                .orElse(0L);

        long committed = Math.addExact(
                Math.addExact(report.totalSafeguardedLiabilitiesPesewas(), report.todayTotalOutflowPesewas()),
                Math.addExact(config.minReserveFloorPesewas(), Math.max(0L, inFlightSweep)));
        long trustHeadroom = Math.max(0L, report.reconciledBankBalancePesewas() - committed);
        if (amountPesewas > trustHeadroom) {
            throw ApiException.conflict("insufficient_trust_headroom",
                    "Requested sweep (" + amountPesewas + ") exceeds Custodian trust headroom (" + trustHeadroom + ")");
        }

        UUID sweepId = UUID.randomUUID();
        String reference = References.next("RS");

        // Drain earned revenue across stripes into system:revenue_sweep_reserved:GHS
        List<Leg> legs = new ArrayList<>(ledger.debitAcrossStripes(Ledger.REVENUE, amountPesewas));
        legs.add(Leg.credit(Ledger.REVENUE_SWEEP_RESERVED, amountPesewas));
        var reservedTx = ledger.post(Ledger.Posting.of(
                "revenue-sweep-reserve",
                sweepId.toString(),
                "revenue_sweep_reservation",
                legs
        ).describedAs("Platform revenue sweep " + reference));

        var railTx = rails.create(
                rails.defaultRail(),
                "payout",
                "revenue_sweep",
                sweepId,
                amountPesewas,
                new RailTransactions.Request(
                        "bank",
                        null,
                        dest.bankCode(),
                        null,
                        null,
                        "ELEPAY revenue sweep " + reference,
                        null,
                        dest.accountName(),
                        dest.accountNumber(),
                        "bank",
                        null,
                        null
                )
        );

        db.sql("""
                INSERT INTO platform_revenue_sweep (
                    id, reference, operating_account_id, currency, amount_pesewas,
                    revenue_buffer_pesewas, trust_headroom_pesewas, status,
                    reservation_tx, rail_tx, proposal_id, requested_by, approved_by
                ) VALUES (
                    :id, :ref, :opAcc, :cur, :amt,
                    :buf, :headroom, 'reserved',
                    :rtx, :railTx, :propId, :reqBy, :appBy
                )""")
                .param("id", sweepId)
                .param("ref", reference)
                .param("opAcc", dest.id())
                .param("cur", cur)
                .param("amt", amountPesewas)
                .param("buf", buffer)
                .param("headroom", trustHeadroom)
                .param("rtx", reservedTx.transactionId())
                .param("railTx", railTx.id())
                .param("propId", proposalId)
                .param("reqBy", requestedBy != null ? requestedBy : "system")
                .param("appBy", approvedBy != null ? approvedBy : (requestedBy != null ? requestedBy : "system"))
                .update();

        if (audit != null) {
            audit.record(approvedBy != null ? approvedBy : "system",
                    "revenue_sweep.reserved",
                    "revenue_sweep:" + reference,
                    Map.of("amount", amountPesewas,
                            "operatingAccountId", dest.id().toString(),
                            "trustHeadroom", trustHeadroom,
                            "clawbackBuffer", buffer));
        }
        return findSweep(sweepId).orElseThrow();
    }

    private void submitAfterCommit(UUID sweepId, UUID railTxId) {
        Runnable submit = () -> {
            if (rails.find(railTxId).map(t -> "created".equals(t.status())).orElse(false)) {
                rails.submit(railTxId);
            }
            boolean sent = rails.find(railTxId).map(t -> !"created".equals(t.status())).orElse(false);
            if (sent) {
                db.sql("UPDATE platform_revenue_sweep SET status = 'submitted' WHERE id = :id AND status = 'reserved'")
                        .param("id", sweepId).update();
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    // ---- Rail finality & reversal listeners --------------------------------------------------

    @EventListener
    @Transactional
    void onSucceeded(RailEvents.Succeeded event) {
        if (!"revenue_sweep".equals(event.purpose())) return;
        var sweepOpt = lockSweep(event.purposeId());
        if (sweepOpt.isEmpty()) return;
        var s = sweepOpt.get();
        if (!Set.of("reserved", "submitted").contains(s.status())) return;

        String clearing = ledger.openStripedSystem(Ledger.clearing(event.rail()), "clearing", Ledger.GHS, true, s.id());
        var posted = ledger.post(Ledger.Posting.of(
                "revenue-sweep-complete",
                s.id().toString(),
                "revenue_sweep_completion",
                List.of(
                        Leg.debit(Ledger.REVENUE_SWEEP_RESERVED, s.amountPesewas()),
                        Leg.credit(clearing, s.amountPesewas())
                )
        ).describedAs(s.reference()));

        db.sql("UPDATE platform_revenue_sweep SET status = 'succeeded', completion_tx = :tx, completed_at = now() WHERE id = :id")
                .param("tx", posted.transactionId()).param("id", s.id()).update();
        if (audit != null) {
            audit.record("system:revenue-sweep", "revenue_sweep.succeeded", "revenue_sweep:" + s.reference(),
                    Map.of("amount", s.amountPesewas(), "rail", event.rail()));
        }
    }

    @EventListener
    @Transactional
    void onFailed(RailEvents.Failed event) {
        if (!"revenue_sweep".equals(event.purpose())) return;
        var sweepOpt = lockSweep(event.purposeId());
        if (sweepOpt.isEmpty()) return;
        var s = sweepOpt.get();
        if (!Set.of("reserved", "submitted").contains(s.status())) return;

        var posted = ledger.post(Ledger.Posting.of(
                "revenue-sweep-release",
                s.id().toString(),
                "revenue_sweep_release",
                List.of(
                        Leg.debit(Ledger.REVENUE_SWEEP_RESERVED, s.amountPesewas()),
                        Leg.credit(Ledger.REVENUE, s.amountPesewas())
                )
        ).describedAs(s.reference()));

        db.sql("""
                UPDATE platform_revenue_sweep
                SET status = 'failed', completion_tx = :tx, completed_at = now(), failure_reason = :reason
                WHERE id = :id""")
                .param("tx", posted.transactionId())
                .param("reason", event.reason())
                .param("id", s.id())
                .update();
    }

    @EventListener
    @Transactional
    void onReturned(RailEvents.Returned event) {
        if (!"revenue_sweep".equals(event.purpose())) return;
        var sweepOpt = lockSweep(event.purposeId());
        if (sweepOpt.isEmpty()) return;
        var s = sweepOpt.get();
        if (!"succeeded".equals(s.status())) {
            ops.raise("revenue_sweep_return_unexpected", "high", "revenue_sweep:" + s.reference(),
                    Map.of("status", s.status()));
            return;
        }

        String clearing = ledger.openStripedSystem(Ledger.clearing(event.rail()), "clearing", Ledger.GHS, true, s.id());
        var posted = ledger.post(Ledger.Posting.of(
                "revenue-sweep-return",
                s.id().toString(),
                "revenue_sweep_return",
                List.of(
                        Leg.debit(clearing, s.amountPesewas()),
                        Leg.credit(Ledger.REVENUE, s.amountPesewas())
                )
        ).describedAs(s.reference()));

        db.sql("""
                UPDATE platform_revenue_sweep
                SET status = 'returned', return_tx = :tx, failure_reason = :reason
                WHERE id = :id""")
                .param("tx", posted.transactionId())
                .param("reason", event.reason())
                .param("id", s.id())
                .update();
        ops.raise("revenue_sweep_returned", "high", "revenue_sweep:" + s.reference(), Map.of(
                "amount", s.amountPesewas(),
                "reason", String.valueOf(event.reason()),
                "note", "Returned platform revenue restored to system:revenue:GHS"));
    }

    // ---- Reads -------------------------------------------------------------------------------

    public Optional<RevenueSweep> findSweep(UUID id) {
        return db.sql(SELECT_SWEEP + " WHERE id = :id")
                .param("id", id)
                .query(PlatformRevenueSweepService::mapSweep)
                .optional();
    }

    public List<RevenueSweep> listSweeps(int limit) {
        return db.sql(SELECT_SWEEP + " ORDER BY created_at DESC LIMIT :l")
                .param("l", Math.max(1, Math.min(limit, 200)))
                .query(PlatformRevenueSweepService::mapSweep)
                .list();
    }

    private Optional<RevenueSweep> lockSweep(UUID id) {
        return db.sql(SELECT_SWEEP + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(PlatformRevenueSweepService::mapSweep)
                .optional();
    }

    private static String normalizeCurrency(String currency) {
        String cur = (currency == null || currency.isBlank()) ? Ledger.GHS : currency.trim().toUpperCase(Locale.ROOT);
        if (!Ledger.GHS.equals(cur)) {
            throw ApiException.badRequest("unsupported_currency",
                    "Platform revenue sweep currently supports GHS only, got: " + cur);
        }
        return cur;
    }

    private static final String SELECT_SWEEP = """
            SELECT id, reference, operating_account_id, currency, amount_pesewas,
                   revenue_buffer_pesewas, trust_headroom_pesewas, status,
                   reservation_tx, completion_tx, return_tx, rail_tx, proposal_id,
                   requested_by, approved_by, failure_reason, created_at, completed_at
            FROM platform_revenue_sweep""";

    private static OperatingAccount mapOperatingAccount(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new OperatingAccount(
                rs.getObject("id", UUID.class),
                rs.getString("bank_code"),
                rs.getString("account_number"),
                rs.getString("account_name"),
                rs.getString("currency").trim(),
                rs.getString("status"),
                rs.getString("verified_by"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private static RevenueSweep mapSweep(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new RevenueSweep(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getObject("operating_account_id", UUID.class),
                rs.getString("currency").trim(),
                rs.getLong("amount_pesewas"),
                rs.getLong("revenue_buffer_pesewas"),
                rs.getLong("trust_headroom_pesewas"),
                rs.getString("status"),
                rs.getObject("reservation_tx", UUID.class),
                rs.getObject("completion_tx", UUID.class),
                rs.getObject("return_tx", UUID.class),
                rs.getObject("rail_tx", UUID.class),
                rs.getObject("proposal_id", UUID.class),
                rs.getString("requested_by"),
                rs.getString("approved_by"),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class)
        );
    }
}
