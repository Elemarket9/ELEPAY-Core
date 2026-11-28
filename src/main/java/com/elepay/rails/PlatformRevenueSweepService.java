package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    private static final Logger log = LoggerFactory.getLogger(PlatformRevenueSweepService.class);
    private static final ZoneId ACCRA_ZONE = ZoneId.of("Africa/Accra");

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
        long previous = this.clawbackBufferPesewas;
        this.clawbackBufferPesewas = clawbackBufferPesewas;
        if (audit != null && previous != clawbackBufferPesewas) {
            audit.record("system:revenue-sweep", "revenue_sweep.clawback_buffer_updated", "platform_revenue:GHS",
                    Map.of("previousBufferPesewas", previous, "newBufferPesewas", clawbackBufferPesewas));
        }
    }

    public record OperatingAccount(
            UUID id,
            String bankCode,
            String accountNumber,
            String accountName,
            String currency,
            String status,
            String verifiedBy,
            OffsetDateTime createdAt,
            String verifiedByEmail,
            String approvedBy
    ) {
        public OperatingAccount(
                UUID id,
                String bankCode,
                String accountNumber,
                String accountName,
                String currency,
                String status,
                String verifiedBy,
                OffsetDateTime createdAt
        ) {
            this(id, bankCode, accountNumber, accountName, currency, status, verifiedBy, createdAt, null, null);
        }
    }

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

    static void validateOperatingAccountInput(
            String bankCode,
            String accountNumber,
            String accountName,
            String currency
    ) {
        normalizeCurrency(currency);
        if (bankCode == null || !bankCode.trim().matches("^[A-Z0-9]{2,20}$")) {
            throw ApiException.badRequest("invalid_bank_code", "Bank code must be 2-20 uppercase alphanumeric characters");
        }
        if (accountNumber == null || !accountNumber.trim().matches("^[0-9]{6,34}$")) {
            throw ApiException.badRequest("invalid_account_number", "Account number must be 6-34 digits");
        }
        if (accountName == null || accountName.isBlank()) {
            throw ApiException.badRequest("invalid_account_name", "Account name is required");
        }
    }

    /**
     * Extracts normalized (trimmed, lowercase) identity tokens from explicit strings to compare
     * operators across NativeAuth HS256 ({@code sub=email}) and OIDC RS256 ({@code sub=UUID}).
     */
    static Set<String> canonicalIdentityTokens(String... rawValues) {
        Set<String> tokens = new LinkedHashSet<>();
        if (rawValues != null) {
            for (String v : rawValues) {
                if (v != null && !v.isBlank()) {
                    tokens.add(v.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return tokens;
    }

    /**
     * Collects all normalized identity tokens for the current approver, combining the explicit
     * {@code approverSubject} with {@code sub}, {@code email}, {@code preferred_username}, and
     * {@code uid} claims from the active JWT in {@link SecurityContextHolder}.
     */
    static Set<String> currentApproverIdentityTokens(String approverSubject) {
        Set<String> tokens = canonicalIdentityTokens(approverSubject);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            var token = jwt.getToken();
            addNormalized(tokens, token.getSubject());
            addNormalized(tokens, token.getClaimAsString("email"));
            addNormalized(tokens, token.getClaimAsString("preferred_username"));
            addNormalized(tokens, token.getClaimAsString("uid"));
        }
        return tokens;
    }

    static String currentJwtClaim(String claimName) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String value = jwt.getToken().getClaimAsString(claimName);
            if (value != null && !value.isBlank()) {
                return value.trim().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    static boolean identitiesOverlap(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        for (String token : a) {
            if (b.contains(token)) return true;
        }
        return false;
    }

    private static void addNormalized(Set<String> out, String value) {
        if (value != null && !value.isBlank()) {
            out.add(value.trim().toLowerCase(Locale.ROOT));
        }
    }

    @Transactional
    public OperatingAccount registerOperatingAccount(
            String bankCode,
            String accountNumber,
            String accountName,
            String currency,
            String verifiedBy
    ) {
        return registerOperatingAccount(bankCode, accountNumber, accountName, currency, verifiedBy, null, null);
    }

    @Transactional
    public OperatingAccount registerOperatingAccount(
            String bankCode,
            String accountNumber,
            String accountName,
            String currency,
            String verifiedBy,
            String verifiedByEmail,
            String approvedBy
    ) {
        String cur = normalizeCurrency(currency);
        validateOperatingAccountInput(bankCode, accountNumber, accountName, cur);
        String cleanBank = bankCode.trim();
        String cleanNum = accountNumber.trim();
        String cleanEmail = (verifiedByEmail != null && !verifiedByEmail.isBlank())
                ? verifiedByEmail.trim().toLowerCase(Locale.ROOT) : null;
        UUID id = UUID.randomUUID();
        try {
            db.sql("""
                    INSERT INTO platform_operating_account
                        (id, bank_code, account_number, account_name, currency, status, verified_by, verified_by_email, approved_by)
                    VALUES (:id, :bank, :num, :name, :cur, 'active', :by, :byEmail, :appBy)""")
                    .param("id", id)
                    .param("bank", cleanBank)
                    .param("num", cleanNum)
                    .param("name", accountName.trim())
                    .param("cur", cur)
                    .param("by", verifiedBy != null ? verifiedBy : "system")
                    .param("byEmail", cleanEmail)
                    .param("appBy", approvedBy)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            throw ApiException.conflict("operating_account_exists",
                    "Operating account " + cleanBank + "/" + cleanNum + " (" + cur + ") is already registered");
        }
        if (audit != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("bankCode", cleanBank);
            details.put("currency", cur);
            if (approvedBy != null) details.put("approvedBy", approvedBy);
            audit.record(verifiedBy != null ? verifiedBy : "system",
                    "revenue_sweep.operating_account_registered",
                    "operating_account:" + id,
                    details);
        }
        return findOperatingAccount(id).orElseThrow();
    }

    private static final String SELECT_OPERATING_ACCOUNT = """
            SELECT id, bank_code, account_number, account_name, currency, status, verified_by, created_at,
                   verified_by_email, approved_by
            FROM platform_operating_account""";

    public Optional<OperatingAccount> findOperatingAccount(UUID id) {
        return db.sql(SELECT_OPERATING_ACCOUNT + " WHERE id = :id")
                .param("id", id)
                .query(PlatformRevenueSweepService::mapOperatingAccount)
                .optional();
    }

    public List<OperatingAccount> listOperatingAccounts(String currency) {
        String cur = normalizeCurrency(currency);
        return db.sql(SELECT_OPERATING_ACCOUNT + " WHERE currency = :cur ORDER BY created_at DESC")
                .param("cur", cur)
                .query(PlatformRevenueSweepService::mapOperatingAccount)
                .list();
    }

    private long unsubmittedRevenueSweepPesewas(String cur) {
        return db.sql("""
                SELECT COALESCE(SUM(s.amount_pesewas), 0)::bigint
                FROM platform_revenue_sweep s
                JOIN rail_transaction rt ON rt.id = s.rail_tx
                WHERE s.currency = :cur
                  AND s.status IN ('reserved', 'submitted')
                  AND rt.reservation_date IS NULL""")
                .param("cur", cur)
                .query(Long.class)
                .single();
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
        long unsubmittedSweep = unsubmittedRevenueSweepPesewas(cur);
        long buffer = this.clawbackBufferPesewas;
        long maxByRevenue = Math.max(0L, earnedRevenue - buffer);

        long trustHeadroom = 0L;
        String blockReason = null;
        if (config.outflowFrozen()) {
            blockReason = "Custodian Bank trust account outflow is frozen";
        } else if (report.reconciledBankBalancePesewas() == null) {
            blockReason = "Custodian Bank trust balance has not been reconciled";
        } else if (!custodianBackstop.isBalanceFresh(config)) {
            blockReason = "Custodian Bank trust balance is stale and must be re-reconciled";
        } else if (!report.safeguardingSatisfied()) {
            blockReason = "Custodian Bank safeguarding check is not satisfied";
        } else {
            long unreconciledOutflows = custodianBackstop.unreconciledOutflowsPesewas(config, cur);
            long committed = Math.addExact(
                    Math.addExact(report.totalSafeguardedLiabilitiesPesewas(), unreconciledOutflows),
                    Math.addExact(config.minReserveFloorPesewas(), unsubmittedSweep));
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

        // Separation of duties (case-insensitive & cross-issuer NativeAuth HS256 vs OIDC RS256):
        // the approver of a dual-controlled revenue sweep cannot be the same canonical operator
        // who proposed the sweep or registered the destination operating account.
        if (proposalId != null) {
            Set<String> approverIds = currentApproverIdentityTokens(approvedBy);
            Set<String> requesterIds = canonicalIdentityTokens(requestedBy);
            if (identitiesOverlap(requesterIds, approverIds)) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "dual_control_identity_conflict",
                        "Revenue sweep approver cannot be the same canonical administrator who proposed the sweep"
                );
            }
            Set<String> destCreatorIds = canonicalIdentityTokens(dest.verifiedBy(), dest.verifiedByEmail());
            if (identitiesOverlap(destCreatorIds, approverIds)) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "operating_account_approver_conflict",
                        "Revenue sweep approver cannot be the same administrator who registered the operating account"
                );
            }
        }

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
        if (!custodianBackstop.isBalanceFresh(config)) {
            throw ApiException.conflict("custodian_trust_balance_stale",
                    "Custodian Bank trust balance is stale and must be re-reconciled before sweeping platform revenue");
        }
        if (!report.safeguardingSatisfied()) {
            throw ApiException.conflict("custodian_safeguarding_unsatisfied",
                    "Custodian Bank safeguarding invariants are not satisfied; revenue sweep blocked");
        }

        // Lock all system:revenue:GHS base + stripe rows in id order via Ledger (prevents deadlocks)
        long lockedEarnedRevenue = Math.max(0L, ledger.lockStripedBalance(Ledger.REVENUE));

        long buffer = this.clawbackBufferPesewas;
        long maxByRevenue = Math.max(0L, lockedEarnedRevenue - buffer);
        if (amountPesewas > maxByRevenue) {
            throw ApiException.conflict("revenue_clawback_buffer_breached",
                    "Requested sweep (" + amountPesewas + ") exceeds sweepable earned revenue ("
                            + maxByRevenue + ", retaining clawback buffer of " + buffer + ")");
        }

        long unsubmittedSweep = unsubmittedRevenueSweepPesewas(cur);
        long unreconciledOutflows = custodianBackstop.unreconciledOutflowsPesewas(config, cur);

        long committed = Math.addExact(
                Math.addExact(report.totalSafeguardedLiabilitiesPesewas(), unreconciledOutflows),
                Math.addExact(config.minReserveFloorPesewas(), unsubmittedSweep));
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
            try {
                if (rails.find(railTxId).map(t -> "created".equals(t.status())).orElse(false)) {
                    rails.submit(railTxId);
                }
                boolean sent = rails.find(railTxId).map(t -> !"created".equals(t.status())).orElse(false);
                if (sent) {
                    db.sql("UPDATE platform_revenue_sweep SET status = 'submitted' WHERE id = :id AND status = 'reserved'")
                            .param("id", sweepId).update();
                }
            } catch (RuntimeException e) {
                log.error("Post-commit rail submission failed for revenue sweep {} (rail_tx {}); recovery will retry: {}",
                        sweepId, railTxId, e.getMessage());
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
        if (audit != null) {
            audit.record("system:revenue-sweep", "revenue_sweep.failed", "revenue_sweep:" + s.reference(),
                    Map.of("amount", s.amountPesewas(), "reason", String.valueOf(event.reason())));
        }
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
        if (audit != null) {
            audit.record("system:revenue-sweep", "revenue_sweep.returned", "revenue_sweep:" + s.reference(),
                    Map.of("amount", s.amountPesewas(), "reason", String.valueOf(event.reason())));
        }
        ops.raise("revenue_sweep_returned", "high", "revenue_sweep:" + s.reference(), Map.of(
                "amount", s.amountPesewas(),
                "reason", String.valueOf(event.reason()),
                "note", "Returned platform revenue restored to system:revenue:GHS"));
    }

    @EventListener
    @Transactional
    void onMismatch(RailEvents.Mismatch event) {
        if (!"revenue_sweep".equals(event.purpose())) return;
        var sweepOpt = lockSweep(event.purposeId());
        if (sweepOpt.isEmpty()) return;
        var s = sweepOpt.get();
        if (!Set.of("reserved", "submitted").contains(s.status())) return;

        db.sql("""
                UPDATE platform_revenue_sweep
                SET status = 'mismatch', failure_reason = :reason
                WHERE id = :id""")
                .param("reason", event.reason())
                .param("id", s.id())
                .update();
        if (audit != null) {
            audit.record("system:revenue-sweep", "revenue_sweep.mismatch", "revenue_sweep:" + s.reference(),
                    Map.of("amount", s.amountPesewas(), "reason", String.valueOf(event.reason())));
        }
        ops.raise("revenue_sweep_mismatch", "high", "revenue_sweep:" + s.reference(), Map.of(
                "sweepId", s.id().toString(),
                "expectedAmount", s.amountPesewas(),
                "reportedAmount", String.valueOf(event.reportedAmount()),
                "reason", String.valueOf(event.reason()),
                "note", "Call POST /v1/ops/revenue-sweeps/" + s.id() + "/reconcile-mismatch with resolution=release or settle"));
    }

    /**
     * Operator-controlled reconciliation for a revenue sweep stuck in terminal rail {@code mismatch}.
     * Prevents {@code system:revenue_sweep_reserved:GHS} and custodian daily outflow reservations from
     * being permanently stranded.
     *
     * @param resolution {@code "release"} to return reserved revenue to {@code system:revenue:GHS} and
     *                   release the custodian outflow reservation, or {@code "settle"} if bank statements
     *                   confirm full settlement.
     */
    @Transactional
    public RevenueSweep reconcileMismatch(UUID sweepId, String resolution, String actor, String reason) {
        if (resolution == null || (!"release".equalsIgnoreCase(resolution.trim()) && !"settle".equalsIgnoreCase(resolution.trim()))) {
            throw ApiException.badRequest("invalid_resolution", "Resolution must be 'release' or 'settle'");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("missing_reason", "Reconciliation reason is required");
        }
        var s = lockSweep(sweepId).orElseThrow(() -> ApiException.notFound("Revenue sweep not found"));
        if (!"mismatch".equals(s.status())) {
            throw ApiException.conflict("sweep_not_in_mismatch",
                    "Only revenue sweeps in 'mismatch' status can be reconciled (current: " + s.status() + ")");
        }
        String effectiveActor = (actor != null && !actor.isBlank()) ? actor : "system:ops";
        LocalDate fallbackDate = s.createdAt() != null
                ? s.createdAt().atZoneSameInstant(ACCRA_ZONE).toLocalDate()
                : LocalDate.now(ACCRA_ZONE);

        if ("release".equalsIgnoreCase(resolution.trim())) {
            var posted = ledger.post(Ledger.Posting.of(
                    "revenue-sweep-release",
                    s.id().toString(),
                    "revenue_sweep_mismatch_release",
                    List.of(
                            Leg.debit(Ledger.REVENUE_SWEEP_RESERVED, s.amountPesewas()),
                            Leg.credit(Ledger.REVENUE, s.amountPesewas())
                    )
            ).describedAs(s.reference() + " mismatch release: " + reason));
            custodianBackstop.onOutboundReleased(s.railTx(), s.amountPesewas(), s.currency(), fallbackDate);
            db.sql("""
                    UPDATE platform_revenue_sweep
                    SET status = 'failed', completion_tx = :tx, completed_at = now(), failure_reason = :reason
                    WHERE id = :id""")
                    .param("tx", posted.transactionId())
                    .param("reason", "Ops mismatch release: " + reason)
                    .param("id", s.id())
                    .update();
            if (audit != null) {
                audit.record(effectiveActor, "revenue_sweep.mismatch_released", "revenue_sweep:" + s.reference(),
                        Map.of("amount", s.amountPesewas(), "reason", reason));
            }
        } else {
            String railName = rails.find(s.railTx()).map(RailTransactions.RailTx::rail).orElse("custodian");
            String clearing = ledger.openStripedSystem(Ledger.clearing(railName), "clearing", Ledger.GHS, true, s.id());
            var posted = ledger.post(Ledger.Posting.of(
                    "revenue-sweep-complete",
                    s.id().toString(),
                    "revenue_sweep_mismatch_settlement",
                    List.of(
                            Leg.debit(Ledger.REVENUE_SWEEP_RESERVED, s.amountPesewas()),
                            Leg.credit(clearing, s.amountPesewas())
                    )
            ).describedAs(s.reference() + " mismatch settle: " + reason));
            custodianBackstop.onOutboundSucceeded(s.railTx(), s.amountPesewas(), s.currency(), fallbackDate);
            if (s.railTx() != null) {
                db.sql("""
                        UPDATE rail_transaction
                        SET status = 'succeeded', updated_at = now(), completed_at = now()
                        WHERE id = :id AND status = 'mismatch'""")
                        .param("id", s.railTx())
                        .update();
            }
            db.sql("""
                    UPDATE platform_revenue_sweep
                    SET status = 'succeeded', completion_tx = :tx, completed_at = now(), failure_reason = :reason
                    WHERE id = :id""")
                    .param("tx", posted.transactionId())
                    .param("reason", "Ops mismatch settled: " + reason)
                    .param("id", s.id())
                    .update();
            if (audit != null) {
                audit.record(effectiveActor, "revenue_sweep.mismatch_settled", "revenue_sweep:" + s.reference(),
                        Map.of("amount", s.amountPesewas(), "reason", reason));
            }
        }
        return findSweep(s.id()).orElseThrow();
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

    /**
     * Sum of in-flight platform revenue sweeps ({@code reserved} or {@code submitted}) in minor units.
     * Should equal the ledger balance of {@link Ledger#REVENUE_SWEEP_RESERVED}.
     */
    public long inFlightSweepAmountPesewas(String currency) {
        String cur = normalizeCurrency(currency);
        Long sum = db.sql("""
                SELECT COALESCE(SUM(amount_pesewas), 0)
                FROM platform_revenue_sweep
                WHERE currency = :cur AND status IN ('reserved', 'submitted')""")
                .param("cur", cur)
                .query(Long.class)
                .single();
        return sum == null ? 0L : sum;
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
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("verified_by_email"),
                rs.getString("approved_by")
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
