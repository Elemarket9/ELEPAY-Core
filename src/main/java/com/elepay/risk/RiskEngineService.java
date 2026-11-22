package com.elepay.risk;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Unified orchestration service for ELEPAY Fraud & Risk Engine.
 * Coordinates all component risk evaluations (velocity, rules, device fingerprinting,
 * SIM swap, geo-velocity, account takeover, behavioral biometrics, and GBDT ML scoring)
 * and conditionally issues bound step-up challenges.
 */
@Service
public class RiskEngineService {

    private final VelocityService velocityService;
    private final RuleEngineService ruleEngineService;
    private final DeviceRiskService deviceRiskService;
    private final SimSwapService simSwapService;
    private final GeoVelocityService geoVelocityService;
    private final AccountTakeoverService atoService;
    private final StepUpAuthService stepUpAuthService;
    private final MerchantRiskService merchantRiskService;
    private final PromotionAbuseService promotionAbuseService;
    private final MuleGraphService muleGraphService;
    private final SyntheticIdentityService syntheticIdentityService;
    private final BehavioralBiometricsService biometricsService;
    private final GradientBoostedFraudModel mlFraudModel;
    private final ChargebackPredictionService chargebackService;

    public RiskEngineService(
            VelocityService velocityService,
            RuleEngineService ruleEngineService,
            DeviceRiskService deviceRiskService,
            SimSwapService simSwapService,
            GeoVelocityService geoVelocityService,
            AccountTakeoverService atoService,
            StepUpAuthService stepUpAuthService,
            MerchantRiskService merchantRiskService,
            PromotionAbuseService promotionAbuseService,
            MuleGraphService muleGraphService,
            SyntheticIdentityService syntheticIdentityService,
            BehavioralBiometricsService biometricsService,
            GradientBoostedFraudModel mlFraudModel,
            ChargebackPredictionService chargebackService
    ) {
        this.velocityService = velocityService;
        this.ruleEngineService = ruleEngineService;
        this.deviceRiskService = deviceRiskService;
        this.simSwapService = simSwapService;
        this.geoVelocityService = geoVelocityService;
        this.atoService = atoService;
        this.stepUpAuthService = stepUpAuthService;
        this.merchantRiskService = merchantRiskService;
        this.promotionAbuseService = promotionAbuseService;
        this.muleGraphService = muleGraphService;
        this.syntheticIdentityService = syntheticIdentityService;
        this.biometricsService = biometricsService;
        this.mlFraudModel = mlFraudModel;
        this.chargebackService = chargebackService;
    }

    public record AssessmentRequest(
            String actorId,
            String referenceId,
            String actionType,
            long amountMinorUnits,
            String currency,
            String targetRecipient,
            DeviceRiskService.DeviceSignals deviceSignals,
            GeoVelocityService.GeoLocation location,
            String phoneNumber,
            String telcoProvider,
            OffsetDateTime imsiLastSwappedAt,
            boolean isTelcoAvailable,
            boolean isNewIpOrVpn,
            boolean recentCredentialChange,
            boolean dormancyBroken,
            boolean withdrawalAnomaly,
            BehavioralBiometricsService.BiometricTelemetry telemetry
    ) {}

    public record ComprehensiveRiskAssessment(
            UUID evaluationId,
            String actorId,
            int riskScore,
            RiskDecision decision,
            String explanation,
            StepUpAuthService.StepUpChallenge stepUpChallenge,
            RuleEngineService.RiskEvaluationResult ruleEvaluation,
            VelocityService.VelocityCheckResult velocityResult,
            DeviceRiskService.DeviceRiskResult deviceResult,
            GeoVelocityService.GeoVelocityResult geoResult,
            SimSwapService.SimSwapEvaluation simResult,
            AccountTakeoverService.AtoAssessment atoResult,
            BehavioralBiometricsService.BiometricAssessment biometricResult,
            GradientBoostedFraudModel.PredictionResult mlResult
    ) {}

    @Transactional
    public ComprehensiveRiskAssessment assessTransaction(AssessmentRequest req) {
        // 1. Velocity evaluation
        var velEvent = new VelocityService.VelocityEvent(
                "CUSTOMER", req.actorId(), req.amountMinorUnits(), req.currency(), req.actionType()
        );
        var velResult = velocityService.checkAndRecord(velEvent, VelocityService.VelocityLimit.standardCustomerLimits());

        // 2. Device risk evaluation
        DeviceRiskService.DeviceRiskResult devResult = null;
        if (req.deviceSignals() != null) {
            devResult = deviceRiskService.evaluateAndRecord(req.deviceSignals());
        }

        // 3. Geo-velocity evaluation
        GeoVelocityService.GeoVelocityResult geoResult = null;
        if (req.location() != null) {
            geoResult = geoVelocityService.checkAndRecord(req.actorId(), req.referenceId(), req.location());
        }

        // 4. SIM-swap evaluation
        SimSwapService.SimSwapEvaluation simResult = null;
        if (req.phoneNumber() != null && req.telcoProvider() != null) {
            simResult = simSwapService.evaluateSimStatus(
                    req.phoneNumber(), req.actorId(), req.telcoProvider(),
                    req.imsiLastSwappedAt(), req.isTelcoAvailable(), req.amountMinorUnits()
            );
        }

        // 5. Account takeover assessment
        var atoSignals = new AccountTakeoverService.AtoSignals(
                req.actorId(),
                req.referenceId(),
                req.deviceSignals() != null ? req.deviceSignals().deviceIdHash() : null,
                req.deviceSignals() != null ? req.deviceSignals().ipAddress() : null,
                devResult != null && devResult.isNewDeviceForActor(),
                req.isNewIpOrVpn(),
                req.recentCredentialChange(),
                req.dormancyBroken(),
                req.withdrawalAnomaly(),
                simResult != null && simResult.status() == SimSwapService.SimSignalStatus.VERIFIED_RECENT_SWAP
        );
        var atoResult = atoService.assessAtoRisk(atoSignals);

        // 6. Behavioral biometrics
        BehavioralBiometricsService.BiometricAssessment bioResult = null;
        if (req.telemetry() != null) {
            bioResult = biometricsService.evaluateTelemetry(req.telemetry());
        }

        // 7. GBDT ML fraud model prediction
        var fraudFeatures = new GradientBoostedFraudModel.FraudFeatures(
                req.amountMinorUnits(),
                (int) velResult.stats().count1h(),
                velResult.stats().amount24h(),
                devResult != null ? devResult.riskScore() : 10,
                geoResult != null ? geoResult.speedKmh() : 0.0,
                simResult != null && simResult.swapAgeHours() != null ? simResult.swapAgeHours() : 500,
                atoResult.atoScore(),
                OffsetDateTime.now().getHour(),
                devResult != null && devResult.isNewDeviceForActor(),
                0
        );
        var mlResult = mlFraudModel.predict(req.referenceId() != null ? req.referenceId() : UUID.randomUUID().toString(), fraudFeatures);

        // 8. Rules engine composite evaluation
        var context = new RuleEngineService.RiskContext(
                req.actorId(),
                req.referenceId(),
                req.actionType(),
                req.amountMinorUnits(),
                req.currency(),
                velResult,
                devResult,
                geoResult,
                simResult,
                atoResult,
                bioResult,
                mlResult
        );
        var ruleResult = ruleEngineService.evaluate(context);

        // 9. Adaptive Step-up challenge issuance if decision is STEP_UP
        StepUpAuthService.StepUpChallenge challenge = null;
        if (ruleResult.decision() == RiskDecision.STEP_UP) {
            var actionPayload = new StepUpAuthService.ActionPayload(
                    req.actorId(),
                    req.actionType(),
                    req.targetRecipient(),
                    req.amountMinorUnits(),
                    req.currency(),
                    UUID.randomUUID().toString()
            );
            challenge = stepUpAuthService.issueChallenge(actionPayload, StepUpAuthService.ChallengeType.SMS_OTP, null);
        }

        return new ComprehensiveRiskAssessment(
                ruleResult.evaluationId(),
                req.actorId(),
                ruleResult.overallRiskScore(),
                ruleResult.decision(),
                ruleResult.decision().name() + " (Score: " + ruleResult.overallRiskScore() + ")",
                challenge,
                ruleResult,
                velResult,
                devResult,
                geoResult,
                simResult,
                atoResult,
                bioResult,
                mlResult
        );
    }

    // Expose individual services for direct calls
    public VelocityService getVelocityService() { return velocityService; }
    public RuleEngineService getRuleEngineService() { return ruleEngineService; }
    public DeviceRiskService getDeviceRiskService() { return deviceRiskService; }
    public SimSwapService getSimSwapService() { return simSwapService; }
    public GeoVelocityService getGeoVelocityService() { return geoVelocityService; }
    public AccountTakeoverService getAtoService() { return atoService; }
    public StepUpAuthService getStepUpAuthService() { return stepUpAuthService; }
    public MerchantRiskService getMerchantRiskService() { return merchantRiskService; }
    public PromotionAbuseService getPromotionAbuseService() { return promotionAbuseService; }
    public MuleGraphService getMuleGraphService() { return muleGraphService; }
    public SyntheticIdentityService getSyntheticIdentityService() { return syntheticIdentityService; }
    public BehavioralBiometricsService getBiometricsService() { return biometricsService; }
    public GradientBoostedFraudModel getMlFraudModel() { return mlFraudModel; }
    public ChargebackPredictionService getChargebackService() { return chargebackService; }
}
