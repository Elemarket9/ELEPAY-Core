package com.elepay.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Algorithm 024: Rules engine and risk score.
 * Versioned declarative rule evaluation producing explainable numeric risk scores
 * and auditable decision trees.
 */
@Service
public class RuleEngineService {

    public static final String ENGINE_VERSION = "2.4.0";

    private final JdbcClient db;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RuleEngineService(JdbcClient db) {
        this.db = db;
    }

    public record RiskContext(
            String actorId,
            String referenceId,
            String actionType,
            long amountMinorUnits,
            String currency,
            VelocityService.VelocityCheckResult velocityResult,
            DeviceRiskService.DeviceRiskResult deviceResult,
            GeoVelocityService.GeoVelocityResult geoResult,
            SimSwapService.SimSwapEvaluation simResult,
            AccountTakeoverService.AtoAssessment atoResult,
            BehavioralBiometricsService.BiometricAssessment biometricResult,
            GradientBoostedFraudModel.PredictionResult mlResult
    ) {}

    public record RuleHit(
            String ruleId,
            String ruleName,
            String version,
            int scoreContribution,
            RiskDecision overrideDecision,
            String explanation
    ) {}

    public record DecisionTreeNode(
            String nodeName,
            String conditionEvaluated,
            boolean satisfied,
            String actionTaken
    ) {}

    public record RiskEvaluationResult(
            UUID evaluationId,
            String actorId,
            int overallRiskScore,
            RiskDecision decision,
            String engineVersion,
            List<RuleHit> triggeredRules,
            List<DecisionTreeNode> decisionTreeTrace,
            OffsetDateTime evaluatedAt
    ) {}

    @Transactional
    public RiskEvaluationResult evaluate(RiskContext context) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID evalId = UUID.randomUUID();

        List<RuleHit> hits = new ArrayList<>();
        List<DecisionTreeNode> treeTrace = new ArrayList<>();
        int totalScore = 0;
        RiskDecision worstDecision = RiskDecision.ALLOW;

        // Rule 1: High Single Transaction Amount (Declarative threshold)
        boolean isHighValue = context.amountMinorUnits() >= 200_000L; // 2,000 GHS
        treeTrace.add(new DecisionTreeNode(
                "Check_High_Value",
                "amount >= 200,000 minor units",
                isHighValue,
                isHighValue ? "Add score +20, require STEP_UP" : "Pass"
        ));
        if (isHighValue) {
            hits.add(new RuleHit("RULE_AMT_001", "High Value Transfer Threshold", ENGINE_VERSION, 20, RiskDecision.STEP_UP,
                    "Transaction amount exceeds 2,000.00 GHS threshold"));
            totalScore += 20;
            worstDecision = elevate(worstDecision, RiskDecision.STEP_UP);
        }

        // Rule 2: Velocity Limit Breach
        boolean velocityBreached = context.velocityResult() != null && !context.velocityResult().allowed();
        treeTrace.add(new DecisionTreeNode(
                "Check_Velocity",
                "velocityResult.allowed == false",
                velocityBreached,
                velocityBreached ? "Add score +40, REJECT" : "Pass"
        ));
        if (velocityBreached) {
            hits.add(new RuleHit("RULE_VEL_002", "Rolling Window Velocity Breach", ENGINE_VERSION, 40, RiskDecision.REJECT,
                    context.velocityResult().violationReason()));
            totalScore += 40;
            worstDecision = elevate(worstDecision, RiskDecision.REJECT);
        }

        // Rule 3: Geo-velocity Impossible Travel
        boolean impossibleTravel = context.geoResult() != null && context.geoResult().impossibleTravel();
        treeTrace.add(new DecisionTreeNode(
                "Check_GeoVelocity",
                "geoResult.impossibleTravel == true",
                impossibleTravel,
                impossibleTravel ? "Add score +35, REJECT" : "Pass"
        ));
        if (impossibleTravel) {
            hits.add(new RuleHit("RULE_GEO_003", "Impossible Travel Speed Anomaly", ENGINE_VERSION, 35, RiskDecision.REJECT,
                    context.geoResult().explanation()));
            totalScore += 35;
            worstDecision = elevate(worstDecision, RiskDecision.REJECT);
        }

        // Rule 4: Device Compromise (Emulator or Rooted)
        boolean deviceCompromised = context.deviceResult() != null &&
                (context.deviceResult().riskScore() >= 50 || context.deviceResult().isMultiAccount());
        treeTrace.add(new DecisionTreeNode(
                "Check_Device_Trust",
                "deviceRisk >= 50 or isMultiAccount",
                deviceCompromised,
                deviceCompromised ? "Add score +30, STEP_UP" : "Pass"
        ));
        if (deviceCompromised) {
            hits.add(new RuleHit("RULE_DEV_004", "Untrusted Device Environment", ENGINE_VERSION, 30, RiskDecision.STEP_UP,
                    "Device signals indicate emulator, rooted OS, or multi-accounting cluster"));
            totalScore += 30;
            worstDecision = elevate(worstDecision, RiskDecision.STEP_UP);
        }

        // Rule 5: Recent Telco SIM Swap (< 48h)
        boolean recentSimSwap = context.simResult() != null &&
                context.simResult().status() == SimSwapService.SimSignalStatus.VERIFIED_RECENT_SWAP;
        treeTrace.add(new DecisionTreeNode(
                "Check_Sim_Swap",
                "simResult.status == VERIFIED_RECENT_SWAP",
                recentSimSwap,
                recentSimSwap ? "Add score +35, apply SIM decision" : "Pass"
        ));
        if (recentSimSwap) {
            RiskDecision simDec = context.simResult().decision();
            hits.add(new RuleHit("RULE_SIM_005", "Recent IMSI SIM Swap Alert", ENGINE_VERSION, 35, simDec,
                    context.simResult().explanation()));
            totalScore += 35;
            worstDecision = elevate(worstDecision, simDec);
        }

        // Rule 6: Account Takeover Aggregation
        boolean atoElevated = context.atoResult() != null && context.atoResult().atoScore() >= 50;
        treeTrace.add(new DecisionTreeNode(
                "Check_ATO",
                "atoScore >= 50",
                atoElevated,
                atoElevated ? "Add score +35, apply ATO decision" : "Pass"
        ));
        if (atoElevated) {
            hits.add(new RuleHit("RULE_ATO_006", "Correlated Account Takeover Signals", ENGINE_VERSION, 35,
                    context.atoResult().decision(), context.atoResult().summary()));
            totalScore += 35;
            worstDecision = elevate(worstDecision, context.atoResult().decision());
        }

        // Rule 7: Bot Cadence Biometrics
        boolean botDetected = context.biometricResult() != null && context.biometricResult().isBotCadence();
        treeTrace.add(new DecisionTreeNode(
                "Check_Biometrics",
                "biometrics.isBotCadence == true",
                botDetected,
                botDetected ? "Add score +45, REJECT" : "Pass"
        ));
        if (botDetected) {
            hits.add(new RuleHit("RULE_BIO_007", "Automated Script / Bot Interaction Cadence", ENGINE_VERSION, 45, RiskDecision.REJECT,
                    "Telemetry indicates zero-variance keystroke or linear trajectory"));
            totalScore += 45;
            worstDecision = elevate(worstDecision, RiskDecision.REJECT);
        }

        // Rule 8: Gradient-Boosted Model Fraud Prediction
        boolean mlHighFraud = context.mlResult() != null && context.mlResult().fraudProbability() >= 0.70;
        treeTrace.add(new DecisionTreeNode(
                "Check_ML_Fraud_Model",
                "mlResult.fraudProbability >= 0.70",
                mlHighFraud,
                mlHighFraud ? "Add score +40, apply ML decision" : "Pass"
        ));
        if (mlHighFraud) {
            hits.add(new RuleHit("RULE_ML_008", "Gradient-Boosted Tree High Fraud Probability", ENGINE_VERSION, 40,
                    context.mlResult().recommendation(),
                    String.format("GBDT fraud model probability %.1f%%", context.mlResult().fraudProbability() * 100)));
            totalScore += 40;
            worstDecision = elevate(worstDecision, context.mlResult().recommendation());
        }

        int finalScore = Math.min(100, Math.max(0, totalScore));

        // Aggregate decision: score gates
        if (finalScore >= 80) {
            worstDecision = elevate(worstDecision, RiskDecision.REJECT);
        } else if (finalScore >= 50) {
            worstDecision = elevate(worstDecision, RiskDecision.STEP_UP);
        } else if (finalScore >= 30) {
            worstDecision = elevate(worstDecision, RiskDecision.REVIEW);
        }

        String jsonHits;
        String jsonTrace;
        try {
            jsonHits = objectMapper.writeValueAsString(hits);
            jsonTrace = objectMapper.writeValueAsString(treeTrace);
        } catch (JsonProcessingException e) {
            jsonHits = "[]";
            jsonTrace = "[]";
        }

        // Persist evaluation
        db.sql("""
            INSERT INTO risk_evaluations (
                id, actor_id, reference_id, action_type, amount_minor_units,
                risk_score, decision, rule_version, triggered_rules_json, explanation_json, created_at
            ) VALUES (
                :id, :actorId, :refId, :actionType, :amount,
                :score, :decision, :version, :hits, :trace, :now
            )
        """)
                .param("id", evalId)
                .param("actorId", context.actorId())
                .param("refId", context.referenceId())
                .param("actionType", context.actionType())
                .param("amount", context.amountMinorUnits())
                .param("score", finalScore)
                .param("decision", worstDecision.name())
                .param("version", ENGINE_VERSION)
                .param("hits", jsonHits)
                .param("trace", jsonTrace)
                .param("now", now)
                .update();

        return new RiskEvaluationResult(
                evalId,
                context.actorId(),
                finalScore,
                worstDecision,
                ENGINE_VERSION,
                hits,
                treeTrace,
                now
        );
    }

    private RiskDecision elevate(RiskDecision current, RiskDecision incoming) {
        if (current == RiskDecision.REJECT || incoming == RiskDecision.REJECT) return RiskDecision.REJECT;
        if (current == RiskDecision.STEP_UP || incoming == RiskDecision.STEP_UP) return RiskDecision.STEP_UP;
        if (current == RiskDecision.REVIEW || incoming == RiskDecision.REVIEW) return RiskDecision.REVIEW;
        return RiskDecision.ALLOW;
    }
}
