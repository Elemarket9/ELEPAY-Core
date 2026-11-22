package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Algorithm 026: SIM-swap checks.
 * Integrates mobile subscriber SIM status signals, evaluates IMSI swap age,
 * and enforces holds or adaptive step-up authentication when recent swaps are detected.
 */
@Service
public class SimSwapService {

    private final JdbcClient db;

    public SimSwapService(JdbcClient db) {
        this.db = db;
    }

    public enum SimSignalStatus {
        VERIFIED_RECENT_SWAP,  // < 48h
        VERIFIED_AGING_SWAP,   // 48h - 7d
        VERIFIED_STABLE,       // > 7d or no swap
        SIGNAL_UNAVAILABLE     // telco timeout / unavailable
    }

    public record SimSwapEvaluation(
            String phoneNumber,
            String telcoProvider,
            boolean swapDetected,
            Integer swapAgeHours,
            SimSignalStatus status,
            RiskDecision decision,
            String explanation
    ) {}

    @Transactional
    public SimSwapEvaluation evaluateSimStatus(
            String phoneNumber,
            String actorId,
            String telcoProvider,
            OffsetDateTime imsiLastSwappedAt,
            boolean isTelcoAvailable,
            long transactionAmountMinorUnits
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID id = UUID.randomUUID();

        if (!isTelcoAvailable) {
            SimSwapEvaluation eval = new SimSwapEvaluation(
                    phoneNumber,
                    telcoProvider,
                    false,
                    null,
                    SimSignalStatus.SIGNAL_UNAVAILABLE,
                    RiskDecision.REVIEW,
                    "Telco SIM status provider unavailable; manual review or secondary factor required"
            );
            persistCheck(id, eval, actorId, null, now);
            return eval;
        }

        if (imsiLastSwappedAt == null) {
            // Stable SIM, never swapped
            SimSwapEvaluation eval = new SimSwapEvaluation(
                    phoneNumber,
                    telcoProvider,
                    false,
                    null,
                    SimSignalStatus.VERIFIED_STABLE,
                    RiskDecision.ALLOW,
                    "Verified stable SIM; no recorded IMSI swap"
            );
            persistCheck(id, eval, actorId, null, now);
            return eval;
        }

        long hoursSinceSwap = Math.max(0, Duration.between(imsiLastSwappedAt, now).toHours());
        boolean swapDetected = hoursSinceSwap <= (7 * 24);

        SimSignalStatus status;
        RiskDecision decision;
        String explanation;

        if (hoursSinceSwap < 48) {
            status = SimSignalStatus.VERIFIED_RECENT_SWAP;
            if (transactionAmountMinorUnits > 50_000L) { // > 500 GHS
                decision = RiskDecision.REJECT;
                explanation = "Critical: SIM swapped " + hoursSinceSwap + "h ago (<48h). High-value transaction blocked to prevent ATO";
            } else {
                decision = RiskDecision.STEP_UP;
                explanation = "Recent SIM swap detected (" + hoursSinceSwap + "h ago). Mandatory biometric step-up challenge required";
            }
        } else if (hoursSinceSwap <= 168) { // 7 days
            status = SimSignalStatus.VERIFIED_AGING_SWAP;
            if (transactionAmountMinorUnits > 200_000L) { // > 2,000 GHS
                decision = RiskDecision.STEP_UP;
                explanation = "SIM swap within 7 days (" + hoursSinceSwap + "h ago). Step-up required for elevated amount";
            } else {
                decision = RiskDecision.ALLOW;
                explanation = "SIM swap within 7 days (" + hoursSinceSwap + "h ago), within normal risk allowance for amount";
            }
        } else {
            status = SimSignalStatus.VERIFIED_STABLE;
            decision = RiskDecision.ALLOW;
            explanation = "SIM swap older than 7 days (" + hoursSinceSwap + "h ago). Low risk";
        }

        SimSwapEvaluation eval = new SimSwapEvaluation(
                phoneNumber,
                telcoProvider,
                swapDetected,
                (int) hoursSinceSwap,
                status,
                decision,
                explanation
        );

        persistCheck(id, eval, actorId, imsiLastSwappedAt, now);
        return eval;
    }

    private void persistCheck(UUID id, SimSwapEvaluation eval, String actorId, OffsetDateTime imsiSwapAt, OffsetDateTime now) {
        db.sql("""
            INSERT INTO risk_sim_swap_checks (
                id, phone_number, actor_id, telco_provider, sim_swap_detected,
                imsi_last_swapped_at, swap_age_hours, signal_status, decision, created_at
            ) VALUES (
                :id, :phone, :actorId, :provider, :detected,
                :swapAt, :ageHours, :status, :decision, :now
            )
        """)
                .param("id", id)
                .param("phone", eval.phoneNumber())
                .param("actorId", actorId)
                .param("provider", eval.telcoProvider())
                .param("detected", eval.swapDetected())
                .param("swapAt", imsiSwapAt)
                .param("ageHours", eval.swapAgeHours())
                .param("status", eval.status().name())
                .param("decision", eval.decision().name())
                .param("now", now)
                .update();
    }
}
