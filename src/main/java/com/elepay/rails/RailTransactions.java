package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.common.FinancialTxRetry;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import com.elepay.rails.PaymentRail.Result;
import com.elepay.rails.PaymentRail.Status;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lifecycle of every external money movement.
 *
 * <ol>
 * <li>{@link #create} records the intent with a unique ELEPAY reference inside the
 *     caller's business transaction (status {@code created}).</li>
 * <li>{@link #submit} calls the rail after that transaction commits. A
 *     transaction is submitted at most once; a crash or timeout leaves it
 *     {@code created}/{@code unknown} for recovery by query.</li>
 * <li>{@link #apply} moves the state machine under a row lock. Terminal states are
 *     final; contradictory or mismatched results go to the operations queue.
 *     Success and failure are published synchronously so listeners post to the
 *     ledger in the same transaction.</li>
 * </ol>
 */
@Service
public class RailTransactions {
    private static final Logger log = LoggerFactory.getLogger(RailTransactions.class);
    private static final int MAX_UNKNOWN_CHECKS = 8;
    private static final ZoneId ACCRA_ZONE = ZoneId.of("Africa/Accra");

    private final JdbcClient db;
    private final ObjectMapper json;
    private final RailRegistry rails;
    private final ApplicationEventPublisher events;
    private final OpsQueue ops;
    private final Ledger ledger;
    private final TransactionTemplate tx;
    private final FinancialTxRetry txRetry;
    private final boolean recoveryEnabled;
    private final CustodianBankBackstopService custodianBackstop;
    private final KillSwitchService killSwitch;
    private com.elepay.common.ScheduledJobLock jobLock;

    @Autowired(required = false)
    void setJobLock(com.elepay.common.ScheduledJobLock jobLock) {
        this.jobLock = jobLock;
    }

    RailTransactions(JdbcClient db, ObjectMapper json, RailRegistry rails, ApplicationEventPublisher events,
                         OpsQueue ops, Ledger ledger, PlatformTransactionManager tm,
                         boolean recoveryEnabled,
                         CustodianBankBackstopService custodianBackstop) {
        this(db, json, rails, events, ops, ledger, tm, recoveryEnabled, custodianBackstop, null);
    }

    @Autowired
    RailTransactions(JdbcClient db, ObjectMapper json, RailRegistry rails, ApplicationEventPublisher events,
                         OpsQueue ops, Ledger ledger, PlatformTransactionManager tm,
                         @Value("${elepay.rails.recovery-enabled:true}") boolean recoveryEnabled,
                         @Autowired(required = false) CustodianBankBackstopService custodianBackstop,
                         @Autowired(required = false) KillSwitchService killSwitch) {
        this.db = db;
        this.json = json;
        this.rails = rails;
        this.events = events;
        this.ops = ops;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(tm);
        this.txRetry = new FinancialTxRetry(tm);
        this.recoveryEnabled = recoveryEnabled;
        this.custodianBackstop = custodianBackstop;
        this.killSwitch = killSwitch;
    }

    public record RailTx(UUID id, String rail, String direction, String reference, String purpose,
                             UUID purposeId, long amount, String currency, String channel, String msisdn,
                             String status, String railReference, String redirectUrl, String failureReason,
                             String request, OffsetDateTime createdAt) {}

    public record Request(String channel, String msisdn, String network, String email, String customerName,
                          String description, String returnUrl, String accountName, String accountNumber,
                          String type, String originalReference, String originalRailReference) {
        public static Request momo(String msisdn, String network, String description) {
            return new Request("momo", msisdn, network, null, null, description, null, null, null, null, null, null);
        }
    }

    public String defaultRail() {
        return rails.defaultName();
    }

    public boolean available(String direction, String channel, String network) {
        try {
            String rail = rails.select(direction, channel, network);
            return killSwitch == null || killSwitch.isRailAllowed(direction, rail);
        }
        catch (ApiException unavailable) { return false; }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RailTx create(String rail, String direction, String purpose, UUID purposeId, long amount,
                             Request request) {
        if (!"refund".equals(direction)) rail = rails.select(direction, request.channel(), request.network());
        if (killSwitch != null && !killSwitch.isRailAllowed(direction, rail)) {
            // Switched off from the admin dashboard: refuse before anything is recorded or reserved.
            throw ApiException.unprocessable("rail_switched_off",
                    "This payment method is temporarily switched off. Please try another method or try again later.");
        }
        if (!rails.get(rail).supports(direction, request.channel(), request.network()))
            throw ApiException.unprocessable("rail_operation_unavailable", "This network does not support the requested operation");
        if (amount <= 0) throw ApiException.badRequest("invalid_amount", "Amount must be positive");
        UUID id = UUID.randomUUID();
        String reference = References.next(switch (direction) {
            case "charge" -> "EPC";
            case "payout" -> "EPP";
            default -> "EPR";
        }).replace("-", "");
        db.sql("""
                INSERT INTO rail_transaction
                    (id, rail, direction, reference, purpose, purpose_id, amount, channel, msisdn, request)
                VALUES (:id, :rail, :direction, :reference, :purpose, :purposeId, :amount, :channel, :msisdn,
                        CAST(:request AS jsonb))""")
                .param("id", id).param("rail", rail).param("direction", direction)
                .param("reference", reference).param("purpose", purpose).param("purposeId", purposeId)
                .param("amount", amount).param("channel", request.channel()).param("msisdn", request.msisdn())
                .param("request", json.writeValueAsString(request)).update();
        return find(id).orElseThrow();
    }

    /**
     * Records an inbound partner callback once per (rail, dedupe key). Returns false for a duplicate, so the
     * caller processes each notification exactly once.
     */
    @Transactional
    public boolean recordCallbackOnce(String rail, String dedupeKey, String reference, String eventType, String payload) {
        return db.sql("""
                INSERT INTO rail_callback (id, rail, dedupe_key, reference, event_type, payload)
                VALUES (:id, :rail, :key, :ref, :type, CAST(:payload AS jsonb)) ON CONFLICT (rail, dedupe_key) DO NOTHING""")
                .param("id", UUID.randomUUID()).param("rail", rail).param("key", dedupeKey).param("ref", reference)
                .param("type", eventType).param("payload", payload).update() == 1;
    }

    public Optional<RailTx> find(UUID id) {
        return db.sql("SELECT * FROM rail_transaction WHERE id = :id").param("id", id)
                .query(RailTransactions::map).optional();
    }

    public Optional<RailTx> findByReference(String reference) {
        return db.sql("SELECT * FROM rail_transaction WHERE reference = :r").param("r", reference)
                .query(RailTransactions::map).optional();
    }

    private static RailTx map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new RailTx(rs.getObject("id", UUID.class), rs.getString("rail"), rs.getString("direction"),
                rs.getString("reference"), rs.getString("purpose"), rs.getObject("purpose_id", UUID.class),
                rs.getLong("amount"), rs.getString("currency"), rs.getString("channel"), rs.getString("msisdn"),
                rs.getString("status"), rs.getString("rail_reference"), rs.getString("redirect_url"),
                rs.getString("failure_reason"), rs.getString("request"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private record SendOutcome(Result result, boolean backstopDenied) {}

    private static LocalDate accraDate(RailTx t) {
        return t.createdAt() != null
                ? t.createdAt().atZoneSameInstant(ACCRA_ZONE).toLocalDate()
                : LocalDate.now(ACCRA_ZONE);
    }

    /**
     * Sends a {@code created} transaction to its rail exactly once. Must be
     * called outside any transaction so the intent is durable before the call.
     */
    public RailTx submit(UUID id) {
        // Claim: only one caller moves created -> unknown before the call. If we crash
        // mid-call the row stays 'unknown' and recovery queries by reference.
        Boolean claimed = tx.execute(s -> db.sql("""
                UPDATE rail_transaction SET status = 'unknown', updated_at = now(),
                       next_check_at = now() + interval '30 seconds'
                WHERE id = :id AND status = 'created'""").param("id", id).update() == 1);
        RailTx current = find(id).orElseThrow();
        if (!Boolean.TRUE.equals(claimed)) return current;
        SendOutcome outcome = send(current);
        String source = outcome.backstopDenied() ? "submit-backstop-denied" : "submit";
        apply(id, outcome.result(), source);
        return find(id).orElseThrow();
    }

    private SendOutcome send(RailTx t) {
        if (custodianBackstop != null && ("payout".equals(t.direction()) || "refund".equals(t.direction()))) {
            var decision = custodianBackstop.authorizeOutboundDisbursement(
                    t.id(), t.reference(), t.rail(), t.direction(), t.amount(), t.currency());
            if (!decision.allowed()) {
                return new SendOutcome(Result.unavailable(decision.reason()), true);
            }
        }
        PaymentRail rail = rails.get(t.rail());
        Request r = json.readValue(t.request(), Request.class);
        try {
            Result res = switch (t.direction()) {
                case "charge" -> rail.charge(new PaymentRail.Charge(t.reference(), t.amount(), t.currency(),
                        r.channel(), r.msisdn(), r.network(), r.email(), r.customerName(), r.description(), r.returnUrl()));
                case "payout" -> rail.payout(new PaymentRail.Payout(t.reference(), t.amount(), t.currency(),
                        r.type() == null ? "momo" : r.type(), r.network(), r.accountNumber() != null ? r.accountNumber() : r.msisdn(),
                        r.accountName(), r.description()));
                case "refund" -> rail.refund(new PaymentRail.Refund(t.reference(), r.originalReference(),
                        r.originalRailReference(), t.amount(), t.currency(), r.msisdn(), r.network()));
                default -> throw new IllegalStateException(t.direction());
            };
            return new SendOutcome(res, false);
        } catch (RuntimeException e) {
            log.warn("Rail {} call for {} failed: {}", t.rail(), t.reference(), e.toString());
            return new SendOutcome(Result.unknown(e.getClass().getSimpleName()), false);
        }
    }

    /** Applies a rail result under a row lock. Safe to call repeatedly and out of order. */
    public void apply(UUID id, Result result, String source) {
        txRetry.execute(() -> {
            applyLocked(id, result, source);
            return null;
        });
    }

    private void applyLocked(UUID id, Result result, String source) {
        RailTx t = db.sql("SELECT * FROM rail_transaction WHERE id = :id FOR UPDATE")
                .param("id", id).query(RailTransactions::map).single();
        boolean terminal = Set.of("succeeded", "failed", "mismatch", "returned").contains(t.status());
        Status status = result.status();
        if (status == Status.RETURNED) {
            if (t.status().equals("succeeded") && !t.direction().equals("charge")) {
                db.sql("""
                        UPDATE rail_transaction SET status = 'returned', updated_at = now(), failure_reason = :reason
                        WHERE id = :id""").param("id", id).param("reason", result.message()).update();
                if (custodianBackstop != null) {
                    custodianBackstop.onOutboundReleased(t.id(), t.amount(), t.currency(), accraDate(t));
                }
                events.publishEvent(new RailEvents.Returned(id, t.rail(), t.direction(), t.purpose(), t.purposeId(),
                        t.amount(), result.message()));
            } else if (!t.status().equals("returned")) {
                ops.raise("rail_unexpected_return", "high", "rail_tx:" + t.reference(), Map.of(
                        "recorded", t.status(), "source", source, "rail", t.rail(), "amount", t.amount()));
            }
            return;
        }
        if (terminal) {
            boolean same = (status == Status.SUCCEEDED && t.status().equals("succeeded"))
                    || (status == Status.FAILED && t.status().equals("failed"));
            boolean contradictory = (status == Status.SUCCEEDED && t.status().equals("failed"))
                    || (status == Status.FAILED && t.status().equals("succeeded"));
            if (contradictory) {
                ops.raise("rail_status_conflict", "high", "rail_tx:" + t.reference(), Map.of(
                        "recorded", t.status(), "reported", status.name(), "source", source,
                        "rail", t.rail(), "direction", t.direction(), "amount", t.amount()));
            } else if (!same) {
                log.debug("Ignoring {} for terminal {} {}", status, t.status(), t.reference());
            }
            return;
        }
        switch (status) {
            case SUCCEEDED -> {
                if (result.amount() == null || result.amount() != t.amount()
                        || result.currency() == null || !result.currency().equalsIgnoreCase(t.currency())
                        || result.railReference() == null || result.railReference().isBlank()
                        || (result.fee() != null && result.fee() < 0)) {
                    db.sql("""
                            UPDATE rail_transaction SET status = 'mismatch', updated_at = now(), completed_at = now(),
                                   rail_reference = coalesce(:pref, rail_reference),
                                   failure_reason = :reason WHERE id = :id""")
                            .param("id", id).param("pref", result.railReference())
                            .param("reason", "Rail reported " + result.amount() + " " + result.currency()).update();
                    ops.raise("rail_amount_mismatch", "high", "rail_tx:" + t.reference(), Map.of(
                            "expected", t.amount(), "reported", String.valueOf(result.amount()),
                            "currency", String.valueOf(result.currency()), "rail", t.rail(),
                            "direction", t.direction()));
                    return;
                }
                long fee = result.fee() == null ? 0 : result.fee();
                db.sql("""
                        UPDATE rail_transaction SET status = 'succeeded', updated_at = now(), completed_at = now(),
                               rail_reference = coalesce(:pref, rail_reference), rail_fee = :fee
                        WHERE id = :id""")
                        .param("id", id).param("pref", result.railReference()).param("fee", fee).update();
                String cur = t.currency() == null || t.currency().isBlank()
                        ? Ledger.GHS : t.currency().toUpperCase(Locale.ROOT);
                String clearingAcct = Ledger.GHS.equals(cur)
                        ? Ledger.clearing(t.rail()) : "system:clearing:" + t.rail() + ":" + cur;
                ledger.openSystem(clearingAcct, "clearing", cur, true);
                if (fee > 0) {
                    String feeAcct = "system:rail_fees:" + cur;
                    ledger.openSystem(feeAcct, "rail_fees", cur, true);
                    ledger.post(Ledger.Posting.of("rail-fee", t.reference(), "rail_fee", List.of(
                            Ledger.Leg.debit(feeAcct, fee),
                            Ledger.Leg.credit(clearingAcct, fee))));
                }
                if (custodianBackstop != null && ("payout".equals(t.direction()) || "refund".equals(t.direction()))) {
                    custodianBackstop.onOutboundSucceeded(t.id(), t.amount(), t.currency(), accraDate(t));
                }
                events.publishEvent(new RailEvents.Succeeded(id, t.rail(), t.direction(), t.purpose(),
                        t.purposeId(), t.amount(), fee));
            }
            case FAILED -> {
                db.sql("""
                        UPDATE rail_transaction SET status = 'failed', updated_at = now(), completed_at = now(),
                               rail_reference = coalesce(:pref, rail_reference), failure_reason = :reason
                        WHERE id = :id""")
                        .param("id", id).param("pref", result.railReference())
                        .param("reason", result.message()).update();
                if (custodianBackstop != null && ("payout".equals(t.direction()) || "refund".equals(t.direction()))) {
                    custodianBackstop.onOutboundReleased(t.id(), t.amount(), t.currency(), accraDate(t));
                }
                events.publishEvent(new RailEvents.Failed(id, t.rail(), t.direction(), t.purpose(),
                        t.purposeId(), t.amount(), result.message()));
            }
            case PENDING -> db.sql("""
                    UPDATE rail_transaction SET status = 'pending', updated_at = now(),
                           rail_reference = coalesce(:pref, rail_reference),
                           redirect_url = coalesce(:url, redirect_url),
                           next_check_at = now() + make_interval(secs => least(600, 15 * power(2, least(check_attempts, 10))))
                    WHERE id = :id""")
                    .param("id", id).param("pref", result.railReference()).param("url", result.redirectUrl()).update();
            case UNKNOWN -> {
                db.sql("""
                        UPDATE rail_transaction SET status = 'unknown', updated_at = now(),
                               next_check_at = now() + make_interval(secs => least(1800, 30 * power(2, least(check_attempts, 10))))
                        WHERE id = :id""").param("id", id).update();
                if (!"submit".equals(source) && checkAttempts(id) >= MAX_UNKNOWN_CHECKS) {
                    ops.raise("rail_result_unknown", "high", "rail_tx:" + t.reference(), Map.of(
                            "rail", t.rail(), "direction", t.direction(), "amount", t.amount()));
                }
            }
            case NOT_FOUND -> handleNotFound(t);
            case UNAVAILABLE -> {
                boolean preSubmission = t.status().equals("created")
                        || "submit".equals(source)
                        || "submit-backstop-denied".equals(source);
                if (preSubmission) {
                    db.sql("""
                            UPDATE rail_transaction SET status = 'failed', updated_at = now(), completed_at = now(),
                                    failure_reason = :reason WHERE id = :id""")
                            .param("id", id).param("reason", result.message()).update();
                    if (custodianBackstop != null && ("payout".equals(t.direction()) || "refund".equals(t.direction()))
                            && !"submit-backstop-denied".equals(source)) {
                        custodianBackstop.onOutboundReleased(t.id(), t.amount(), t.currency(), accraDate(t));
                    }
                    events.publishEvent(new RailEvents.Failed(id, t.rail(), t.direction(), t.purpose(),
                            t.purposeId(), t.amount(), result.message()));
                } else {
                    // Post-submission UNAVAILABLE: keep unknown/pending for retry and NEVER release funds.
                    db.sql("""
                            UPDATE rail_transaction SET updated_at = now(),
                                   next_check_at = now() + make_interval(secs => least(1800, 30 * power(2, least(check_attempts, 10))))
                            WHERE id = :id""").param("id", id).update();
                    if (!"submit".equals(source) && checkAttempts(id) >= MAX_UNKNOWN_CHECKS) {
                        ops.raise("rail_result_unknown", "high", "rail_tx:" + t.reference(), Map.of(
                                "rail", t.rail(), "direction", t.direction(), "amount", t.amount()));
                    }
                }
            }
        }
    }

    private void handleNotFound(RailTx t) {
        boolean graceElapsed = t.createdAt().isBefore(OffsetDateTime.now().minusMinutes(10));
        if (t.direction().equals("charge")) {
            if (graceElapsed) {
                // The rail never registered this reference, so the customer cannot have paid it.
                db.sql("""
                        UPDATE rail_transaction SET status = 'failed', updated_at = now(), completed_at = now(),
                               failure_reason = 'Not found at rail' WHERE id = :id""").param("id", t.id()).update();
                events.publishEvent(new RailEvents.Failed(t.id(), t.rail(), t.direction(), t.purpose(),
                        t.purposeId(), t.amount(), "Not found at rail"));
            } else {
                // Grace period not yet elapsed: back off next_check_at so scheduled recovery does not
                // tight-loop every 15s hammering the partner rail until the 10-minute grace window expires.
                db.sql("""
                        UPDATE rail_transaction SET updated_at = now(),
                               next_check_at = now() + make_interval(secs => least(600, 30 * power(2, least(check_attempts, 10))))
                        WHERE id = :id""").param("id", t.id()).update();
            }
            return;
        }
        // Payouts and refunds that never reached the rail are re-sent with the SAME
        // reference, which rails treat as idempotent. Release custodian reservation
        // before resetting to 'created' so retry does not double-reserve daily outflow.
        if (custodianBackstop != null && ("payout".equals(t.direction()) || "refund".equals(t.direction()))) {
            custodianBackstop.onOutboundReleased(t.id(), t.amount(), t.currency(), accraDate(t));
        }
        db.sql("UPDATE rail_transaction SET status = 'created', updated_at = now() WHERE id = :id")
                .param("id", t.id()).update();
        // A rail that keeps refusing the same request (e.g. a WAF block or a permanent validation error
        // returned as HTTP 5xx) would otherwise be resent forever without anyone being told.
        if (checkAttempts(t.id()) >= MAX_UNKNOWN_CHECKS) {
            ops.raise("rail_resubmission_loop", "high", "rail_tx:" + t.reference(), Map.of(
                    "rail", t.rail(), "direction", t.direction(), "amount", t.amount(),
                    "attempts", checkAttempts(t.id())));
        }
    }

    private int checkAttempts(UUID id) {
        return db.sql("SELECT check_attempts FROM rail_transaction WHERE id = :id").param("id", id)
                .query(Integer.class).single();
    }

    // ---- callbacks ---------------------------------------------------------------------

    public record CallbackOutcome(boolean duplicate, String outcome) {}

    /** Verifies, deduplicates and applies a rail callback. */
    public CallbackOutcome handleCallback(String railName, Map<String, String> headers, String body) {
        PaymentRail rail = rails.get(railName);
        PaymentRail.Callback callback;
        try {
            callback = rail.verifyCallback(headers, body);
        } catch (PaymentRail.InvalidCallbackException e) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_callback", e.getMessage());
        }
        UUID callbackId = UUID.randomUUID();
        JsonNode payload = json.readTree(body);
        String payloadJson = json.writeValueAsString(payload);

        return txRetry.execute(() -> {
            boolean fresh = db.sql("""
                    INSERT INTO rail_callback (id, rail, dedupe_key, reference, event_type, payload)
                    VALUES (:id, :rail, :key, :reference, :type, CAST(:payload AS jsonb))
                    ON CONFLICT (rail, dedupe_key) DO NOTHING""")
                    .param("id", callbackId).param("rail", railName).param("key", callback.dedupeKey())
                    .param("reference", callback.reference()).param("type", callback.eventType())
                    .param("payload", payloadJson).update() == 1;
            if (!fresh) return new CallbackOutcome(true, "duplicate");

            Optional<RailTx> match = Optional.empty();
            if (callback.reference() != null) {
                UUID asUuid = null;
                try {
                    asUuid = UUID.fromString(callback.reference());
                } catch (IllegalArgumentException ignored) {}
                if (asUuid != null) {
                    match = db.sql("""
                            SELECT * FROM rail_transaction
                            WHERE reference = :r OR purpose_id = :uuid
                            ORDER BY created_at DESC LIMIT 1""")
                            .param("r", callback.reference()).param("uuid", asUuid)
                            .query(RailTransactions::map).optional();
                } else {
                    match = findByReference(callback.reference());
                }
            }
            if (match.isEmpty() && callback.railReference() != null) {
                match = db.sql("""
                        SELECT * FROM rail_transaction
                        WHERE rail = :p AND rail_reference = :r
                        ORDER BY created_at DESC LIMIT 1""")
                        .param("p", railName).param("r", callback.railReference())
                        .query(RailTransactions::map).optional();
            }
            String outcome;
            if (match.isEmpty() || !match.get().rail().equals(railName)) {
                ops.raise("callback_unknown_reference", "medium", "callback:" + railName + ":" + callback.dedupeKey(),
                        Map.of("reference", String.valueOf(callback.reference()), "event", String.valueOf(callback.eventType())));
                outcome = "unmatched";
            } else {
                RailTx t = match.get();
                Result result = callback.result();
                if (!rail.callbacksAreAuthoritative() || result == null) {
                    result = safeQuery(rail, t);
                }
                applyLocked(t.id(), result, "callback");
                outcome = "applied:" + result.status();
            }
            db.sql("UPDATE rail_callback SET outcome = :o WHERE id = :id")
                    .param("o", outcome).param("id", callbackId).update();
            return new CallbackOutcome(false, outcome);
        });
    }

    private Result safeQuery(PaymentRail rail, RailTx t) {
        try {
            return rail.query(t.direction(), t.reference(), t.railReference());
        } catch (RuntimeException e) {
            return Result.unknown(e.getClass().getSimpleName());
        }
    }

    // ---- recovery ----------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${elepay.rails.recovery-ms:15000}")
    void scheduledRecovery() {
        if (!recoveryEnabled) return;
        if (jobLock != null) {
            jobLock.runIfLeader("rail-recovery", () -> recover(50, false));
        } else {
            recover(50, false);
        }
    }

    /**
     * Resolves delayed or missing confirmations by querying rails. With
     * {@code force}, ignores the backoff schedule (operator action and tests).
     */
    public int recover(int limit, boolean force) {
        List<UUID> due = db.sql("""
                SELECT id FROM rail_transaction
                WHERE status IN ('created', 'pending', 'unknown') AND (:force OR next_check_at <= now())
                ORDER BY next_check_at LIMIT :limit""")
                .param("force", force).param("limit", limit).query(UUID.class).list();
        int resolved = 0;
        for (UUID id : due) {
            RailTx t = find(id).orElseThrow();
            if (!rails.names().contains(t.rail())) {
                ops.raise("rail_not_configured", "high", "rail_tx:" + t.reference(), Map.of("rail", t.rail()));
                tx.executeWithoutResult(s -> db.sql("""
                        UPDATE rail_transaction SET next_check_at = now() + interval '1 hour', updated_at = now()
                        WHERE id = :id""").param("id", id).update());
                continue; // Historic records remain intact; never reroute without authoritative evidence.
            }
            try {
                if (t.status().equals("created")) {
                    // Either never submitted (crash before call) or reset after NOT_FOUND.
                    boolean stale = t.createdAt().isBefore(OffsetDateTime.now().minusSeconds(30));
                    if (force || stale) {
                        if (t.direction().equals("charge")) {
                            // A charge that was never sent is abandoned rather than sent late.
                            apply(id, safeQuery(rails.get(t.rail()), t), "recovery");
                        } else {
                            if (killSwitch != null && !killSwitch.isRailAllowed(t.direction(), t.rail())) {
                                log.warn("Skipping recovery resubmission of {} {}: rail switched off",
                                        t.direction(), t.reference());
                                continue; // stays 'created'; resent once the switch is back on
                            }
                            if ("payout".equals(t.direction()) && killSwitch != null) {
                                try {
                                    killSwitch.requirePayoutsAllowed(null);
                                } catch (ApiException blocked) {
                                    log.warn("Skipping recovery resubmission of payout {} due to kill switch: {}",
                                            t.reference(), blocked.getMessage());
                                    continue;
                                }
                            }
                            submit(id);
                        }
                        String after = find(id).orElseThrow().status();
                        if (!after.equals(t.status()) && Set.of("succeeded", "failed", "mismatch").contains(after)) {
                            resolved++;
                        }
                    }
                    continue;
                }
                tx.executeWithoutResult(s -> db.sql("UPDATE rail_transaction SET check_attempts = check_attempts + 1 WHERE id = :id")
                        .param("id", id).update());
                Result result = safeQuery(rails.get(t.rail()), t);
                apply(id, result, "recovery");
                String after = find(id).orElseThrow().status();
                if (!after.equals(t.status()) && Set.of("succeeded", "failed", "mismatch").contains(after)) resolved++;
            } catch (RuntimeException e) {
                log.error("Recovery failed for rail_tx {} ({}): {}", t.reference(), id, e.getMessage(), e);
                tx.executeWithoutResult(s -> db.sql("""
                        UPDATE rail_transaction SET next_check_at = now() + interval '5 minutes', updated_at = now()
                        WHERE id = :id""").param("id", id).update());
            }
        }
        return resolved;
    }

    public List<RailTx> open() {
        return db.sql("SELECT * FROM rail_transaction WHERE status IN ('created','pending','unknown') ORDER BY created_at")
                .query(RailTransactions::map).list();
    }
}
