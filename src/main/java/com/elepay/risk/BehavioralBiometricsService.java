package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Algorithm 034: Behavioral biometrics.
 * Analyzes passive interaction telemetry (keystroke dynamics and pointer cadence)
 * to distinguish human interaction from scripted/bot automation, with documented
 * accessibility safeguards preventing false positives for motor impairment.
 */
@Service
public class BehavioralBiometricsService {

    private final JdbcClient db;

    public BehavioralBiometricsService(JdbcClient db) {
        this.db = db;
    }

    public record BiometricTelemetry(
            String actorId,
            String sessionId,
            double meanDwellTimeMs,        // Duration a key is held down
            double meanFlightTimeMs,       // Interval between key releases and next key press
            double dwellTimeVariance,      // Variance in keystroke timing
            double pointerVelocityAvg,     // Pointer movement speed (pixels/sec)
            double pointerAccelerationAvg, // Pointer acceleration
            double pointerCurvatureScore,  // Ratio of curved arc to straight displacement (humans ~ 1.1 - 1.5, bots ~ 1.0)
            boolean accessibilityAssistiveTechActive
    ) {}

    public record BiometricAssessment(
            String actorId,
            String sessionId,
            boolean isBotCadence,
            int biometricAnomalyScore,
            String accessibilityFlag,
            List<String> signals,
            RiskDecision decision
    ) {}

    @Transactional
    public BiometricAssessment evaluateTelemetry(BiometricTelemetry telemetry) {
        OffsetDateTime now = OffsetDateTime.now();
        List<String> signals = new ArrayList<>();
        int anomalyScore = 0;
        boolean botDetected = false;

        // 1. Accessibility check: do not penalize assistive technologies or tremor patterns
        if (telemetry.accessibilityAssistiveTechActive()) {
            signals.add("ACCESSIBILITY_ASSISTIVE_TECH_ACTIVE: Bypassing strict timing gates");
            persistProfile(telemetry, false, 0, "ASSISTIVE_TECH_ACTIVE", now);
            return new BiometricAssessment(
                    telemetry.actorId(),
                    telemetry.sessionId(),
                    false,
                    0,
                    "ASSISTIVE_TECH_ACTIVE",
                    signals,
                    RiskDecision.ALLOW
            );
        }

        // 2. Keystroke timing anomaly checks:
        // Bot scripts often have near-zero variance or unnaturally fast flight times (< 15ms)
        if (telemetry.meanFlightTimeMs() < 15.0) {
            anomalyScore += 45;
            botDetected = true;
            signals.add("SUPERHUMAN_KEYSTROKE_SPEED: Mean flight time " + String.format("%.1f", telemetry.meanFlightTimeMs()) + "ms (< 15ms)");
        } else if (telemetry.dwellTimeVariance() < 1.0 && telemetry.meanDwellTimeMs() > 0) {
            anomalyScore += 40;
            botDetected = true;
            signals.add("SYNTHETIC_ZERO_VARIANCE_KEYSTROKE: Unnatural fixed cadence");
        }

        // 3. Pointer trajectory checks:
        // Human mouse movements naturally follow curved arcs (curvature > 1.05).
        // Bots and synthetic scripts typically move in strictly Euclidean straight lines (curvature == 1.0).
        if (telemetry.pointerVelocityAvg() > 0) {
            if (telemetry.pointerCurvatureScore() <= 1.001) {
                anomalyScore += 40;
                botDetected = true;
                signals.add("PERFECT_LINEAR_POINTER_TRAJECTORY: Zero human arc curvature");
            } else if (telemetry.pointerVelocityAvg() > 5000.0) {
                anomalyScore += 30;
                signals.add("ABNORMAL_POINTER_VELOCITY (> 5000 px/s)");
            }
        }

        int finalScore = Math.min(100, anomalyScore);
        RiskDecision decision;
        if (botDetected || finalScore >= 70) {
            decision = RiskDecision.REJECT;
        } else if (finalScore >= 35) {
            decision = RiskDecision.STEP_UP;
        } else {
            decision = RiskDecision.ALLOW;
        }

        persistProfile(telemetry, botDetected, finalScore, "STANDARD", now);

        return new BiometricAssessment(
                telemetry.actorId(),
                telemetry.sessionId(),
                botDetected,
                finalScore,
                "STANDARD",
                signals,
                decision
        );
    }

    private void persistProfile(BiometricTelemetry telemetry, boolean isBot, int score, String accessFlag, OffsetDateTime now) {
        if (db == null) {
            return;
        }
        db.sql("""
            INSERT INTO risk_behavioral_profiles (
                id, actor_id, session_id, mean_dwell_time_ms, mean_flight_time_ms,
                dwell_time_variance, pointer_velocity_avg, pointer_acceleration_avg,
                pointer_curvature_score, is_bot_cadence, biometric_anomaly_score,
                accessibility_flag, recorded_at
            ) VALUES (
                :id, :actorId, :sessionId, :dwell, :flight,
                :dwellVar, :vel, :acc,
                :curvature, :isBot, :score,
                :accessFlag, :now
            )
        """)
                .param("id", UUID.randomUUID())
                .param("actorId", telemetry.actorId())
                .param("sessionId", telemetry.sessionId())
                .param("dwell", telemetry.meanDwellTimeMs())
                .param("flight", telemetry.meanFlightTimeMs())
                .param("dwellVar", telemetry.dwellTimeVariance())
                .param("vel", telemetry.pointerVelocityAvg())
                .param("acc", telemetry.pointerAccelerationAvg())
                .param("curvature", telemetry.pointerCurvatureScore())
                .param("isBot", isBot)
                .param("score", score)
                .param("accessFlag", accessFlag)
                .param("now", now)
                .update();
    }
}
