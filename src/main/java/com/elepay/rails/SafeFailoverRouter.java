package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.common.OpsQueue;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 015: Safe failover routing.
 * Switches rail ONLY when the primary route is definitively unsubmitted or
 * confirmed failed without funds movement.
 *
 * <p><strong>Mandatory Financial Invariant:</strong>
 * If the primary rail's outcome is {@code UNKNOWN}, {@code PENDING}, or timed out mid-flight,
 * automatic failover is STRICTLY FORBIDDEN. Rerouting an unknown transaction to a second rail
 * could double-charge the customer or duplicate a payout.
 */
@Service
public class SafeFailoverRouter {
    private static final Logger log = LoggerFactory.getLogger(SafeFailoverRouter.class);

    private final JdbcClient db;
    private final NetworkHealthDetector healthDetector;
    private final OpsQueue ops;

    public SafeFailoverRouter(JdbcClient db, NetworkHealthDetector healthDetector, OpsQueue ops) {
        this.db = db;
        this.healthDetector = healthDetector;
        this.ops = ops;
    }

    public record FailoverDecision(
            boolean allowed,
            String primaryRail,
            String targetRail,
            String reason
    ) {}

    public static class UnsafeFailoverException extends RuntimeException {
        public UnsafeFailoverException(String message) {
            super(message);
        }
    }

    /**
     * Determines whether failover from primaryRail to secondaryRail is safe given
     * the primary transaction's status.
     *
     * @param reference ELEPAY transaction reference
     * @param primaryRail name of the primary rail that was attempted
     * @param secondaryRail candidate backup rail
     * @param primaryStatus status returned or recorded for the primary attempt
     * @param partnerReceived whether the partner network acknowledged receipt
     */
    @Transactional
    public FailoverDecision evaluateFailover(
            String reference,
            String primaryRail,
            String secondaryRail,
            PaymentRail.Status primaryStatus,
            boolean partnerReceived
    ) {
        if (secondaryRail == null || secondaryRail.isBlank()) {
            return new FailoverDecision(false, primaryRail, null, "no_secondary_rail_configured");
        }

        // Rule 1: If primary succeeded, failover is strictly forbidden
        if (primaryStatus == PaymentRail.Status.SUCCEEDED) {
            throw new UnsafeFailoverException("Cannot fail over: primary rail " + primaryRail + " succeeded for " + reference);
        }

        // Rule 2: If primary outcome is UNKNOWN or PENDING, failover is STRICTLY FORBIDDEN
        if (primaryStatus == PaymentRail.Status.UNKNOWN || primaryStatus == PaymentRail.Status.PENDING) {
            ops.raise("unsafe_failover_blocked", "high", "rail_tx:" + reference, Map.of(
                    "reference", reference,
                    "primaryRail", primaryRail,
                    "primaryStatus", primaryStatus.name(),
                    "secondaryRail", secondaryRail
            ));
            throw new UnsafeFailoverException(
                    "Unsafe failover blocked: primary rail " + primaryRail + " outcome is " + primaryStatus +
                    ". Funds may have moved; recovery must query primary rail instead of resubmitting."
            );
        }

        // Rule 3: If partner actually received request and outcome is not definitively zero-movement
        if (partnerReceived && primaryStatus != PaymentRail.Status.NOT_FOUND && primaryStatus != PaymentRail.Status.FAILED) {
            throw new UnsafeFailoverException("Cannot fail over: partner received request with uncertain funds movement");
        }

        // Rule 4: Definitively NOT_FOUND at partner -> safe to failover (partner has no record)
        if (primaryStatus == PaymentRail.Status.NOT_FOUND) {
            recordFailover(reference, primaryRail, secondaryRail, "partner_confirmed_not_found");
            return new FailoverDecision(true, primaryRail, secondaryRail, "partner_confirmed_not_found");
        }

        // Rule 5: Circuit breaker OPEN on primary prior to submission -> safe to failover
        if (!healthDetector.isRailAvailable(primaryRail)) {
            recordFailover(reference, primaryRail, secondaryRail, "primary_circuit_open_pre_submission");
            return new FailoverDecision(true, primaryRail, secondaryRail, "primary_circuit_open_pre_submission");
        }

        // Rule 6: Pre-submission validation reject (e.g. before outbound call) -> safe to failover
        if (primaryStatus == PaymentRail.Status.FAILED && !partnerReceived) {
            recordFailover(reference, primaryRail, secondaryRail, "pre_submission_failure_no_funds_movement");
            return new FailoverDecision(true, primaryRail, secondaryRail, "pre_submission_failure_no_funds_movement");
        }

        // Rule 7: Primary rail unconfigured or unavailable before funds movement -> safe to failover
        if (primaryStatus == PaymentRail.Status.UNAVAILABLE && !partnerReceived) {
            recordFailover(reference, primaryRail, secondaryRail, "primary_rail_unavailable_no_funds_movement");
            return new FailoverDecision(true, primaryRail, secondaryRail, "primary_rail_unavailable_no_funds_movement");
        }

        return new FailoverDecision(false, primaryRail, secondaryRail, "unsupported_failover_condition");
    }

    private void recordFailover(String reference, String primaryRail, String secondaryRail, String reason) {
        log.info("Safe failover executed for {}: {} -> {} (reason: {})", reference, primaryRail, secondaryRail, reason);
        try {
            db.sql("""
                    INSERT INTO rail_failover_log (id, reference, primary_rail, failover_rail, reason, fund_movement_verified_none)
                    VALUES (:id, :ref, :prim, :sec, :reason, true)""")
                    .param("id", UUID.randomUUID())
                    .param("ref", reference)
                    .param("prim", primaryRail)
                    .param("sec", secondaryRail)
                    .param("reason", reason)
                    .update();
        } catch (Exception e) {
            log.warn("Could not log failover to rail_failover_log: {}", e.getMessage());
        }
    }
}
