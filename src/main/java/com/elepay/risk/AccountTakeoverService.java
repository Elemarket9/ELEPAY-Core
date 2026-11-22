package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Algorithm 028: Account takeover detection.
 * Correlates session, credential changes, device fingerprint changes, dormancy reactivation,
 * and high-value withdrawal anomalies into an explainable composite ATO risk score.
 */
@Service
public class AccountTakeoverService {

    private final JdbcClient db;

    public AccountTakeoverService(JdbcClient db) {
        this.db = db;
    }

    public record AtoSignals(
            String actorId,
            String sessionId,
            String deviceIdHash,
            String ipAddress,
            boolean isNewDevice,
            boolean isNewIpOrVpn,
            boolean recentCredentialChange,
            boolean dormancyBroken,
            boolean withdrawalAnomaly,
            boolean simSwapDetected
    ) {}

    public record AtoAssessment(
            String actorId,
            int atoScore,
            RiskDecision decision,
            List<String> anomalyFactors,
            String summary
    ) {}

    @Transactional
    public AtoAssessment assessAtoRisk(AtoSignals signals) {
        OffsetDateTime now = OffsetDateTime.now();
        int score = 0;
        List<String> factors = new ArrayList<>();

        if (signals.isNewDevice()) {
            score += 25;
            factors.add("NEW_OR_UNRECOGNIZED_DEVICE");
        }
        if (signals.isNewIpOrVpn()) {
            score += 20;
            factors.add("ANOMALOUS_IP_OR_VPN_ADDRESS");
        }
        if (signals.recentCredentialChange()) {
            score += 30;
            factors.add("CREDENTIAL_OR_PIN_CHANGED_RECENTLY");
        }
        if (signals.dormancyBroken()) {
            score += 25;
            factors.add("DORMANT_ACCOUNT_SUDDEN_REACTIVATION");
        }
        if (signals.withdrawalAnomaly()) {
            score += 20;
            factors.add("HIGH_VALUE_WITHDRAWAL_ANOMALY");
        }
        if (signals.simSwapDetected()) {
            score += 35;
            factors.add("RECENT_TELCO_SIM_SWAP_ACTIVE");
        }

        int finalScore = Math.min(100, score);
        RiskDecision decision;
        String summary;

        if (finalScore >= 75) {
            decision = RiskDecision.REJECT;
            summary = "Critical account takeover risk: multiple correlated compromise indicators detected";
        } else if (finalScore >= 50) {
            decision = RiskDecision.STEP_UP;
            summary = "Elevated account takeover risk: step-up authentication required to confirm identity";
        } else if (finalScore >= 25) {
            decision = RiskDecision.REVIEW;
            summary = "Low-to-moderate risk: signals noted for review";
        } else {
            decision = RiskDecision.ALLOW;
            summary = "Normal session activity; no significant takeover indicators";
        }

        // Persist assessment
        db.sql("""
            INSERT INTO risk_ato_assessments (
                id, actor_id, session_id, device_id_hash, ip_address,
                recent_credential_change, dormancy_broken, withdrawal_anomaly,
                sim_swap_flag, ato_score, decision, created_at
            ) VALUES (
                :id, :actorId, :sessionId, :deviceHash, :ip,
                :credChange, :dormancy, :withdrawal,
                :simSwap, :score, :decision, :now
            )
        """)
                .param("id", UUID.randomUUID())
                .param("actorId", signals.actorId())
                .param("sessionId", signals.sessionId())
                .param("deviceHash", signals.deviceIdHash())
                .param("ip", signals.ipAddress())
                .param("credChange", signals.recentCredentialChange())
                .param("dormancy", signals.dormancyBroken())
                .param("withdrawal", signals.withdrawalAnomaly())
                .param("simSwap", signals.simSwapDetected())
                .param("score", finalScore)
                .param("decision", decision.name())
                .param("now", now)
                .update();

        return new AtoAssessment(signals.actorId(), finalScore, decision, factors, summary);
    }
}
