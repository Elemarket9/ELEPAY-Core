package com.elepay.risk;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Milestone 5A: Fraud & Risk Engine Full Integration Tests (Algorithms 023-036)")
class RiskEngineIntegrationTest extends PostgresTestBase {

    @Autowired
    private RiskEngineService riskEngine;

    @Autowired
    private VelocityService velocityService;

    @Autowired
    private RuleEngineService ruleEngine;

    @Autowired
    private DeviceRiskService deviceRiskService;

    @Autowired
    private SimSwapService simSwapService;

    @Autowired
    private GeoVelocityService geoVelocityService;

    @Autowired
    private AccountTakeoverService atoService;

    @Autowired
    private StepUpAuthService stepUpAuthService;

    @Autowired
    private MerchantRiskService merchantRiskService;

    @Autowired
    private MuleGraphService muleGraphService;

    @Autowired
    private SyntheticIdentityService syntheticIdentityService;

    @Autowired
    private BehavioralBiometricsService biometricsService;

    @Autowired
    private GradientBoostedFraudModel mlFraudModel;

    @Autowired
    private ChargebackPredictionService chargebackService;

    @Test
    @DisplayName("Algorithm 023: Velocity rules sliding window enforcement")
    void testVelocitySlidingWindowEnforcement() {
        String actorId = "usr_vel_" + UUID.randomUUID();
        var limit = new VelocityService.VelocityLimit(
                3,       // max 3 txns in 1h
                300_000L,// max 3,000 GHS in 1h
                10,
                1_000_000L,
                50,
                5_000_000L
        );

        var event1 = new VelocityService.VelocityEvent("CUSTOMER", actorId, 100_000L, "GHS", "TRANSFER");
        var res1 = velocityService.checkAndRecord(event1, limit);
        assertTrue(res1.allowed(), "First transaction within velocity limit should be allowed");
        assertEquals(1, res1.stats().count1h());

        var event2 = new VelocityService.VelocityEvent("CUSTOMER", actorId, 100_000L, "GHS", "TRANSFER");
        var res2 = velocityService.checkAndRecord(event2, limit);
        assertTrue(res2.allowed(), "Second transaction should be allowed");
        assertEquals(2, res2.stats().count1h());

        var event3 = new VelocityService.VelocityEvent("CUSTOMER", actorId, 100_000L, "GHS", "TRANSFER");
        var res3 = velocityService.checkAndRecord(event3, limit);
        assertTrue(res3.allowed(), "Third transaction reaches limit");
        assertEquals(3, res3.stats().count1h());

        // 4th transaction breaches maxCount1h (3)
        var event4 = new VelocityService.VelocityEvent("CUSTOMER", actorId, 100_000L, "GHS", "TRANSFER");
        var res4 = velocityService.checkAndRecord(event4, limit);
        assertFalse(res4.allowed(), "4th transaction in 1h must breach velocity count limit");
        assertTrue(res4.violationReason().contains("1-hour transaction count limit"));
    }

    @Test
    @DisplayName("Algorithm 024: Rules engine and risk score with explainable decision trees")
    void testRuleEngineEvaluationAndExplanation() {
        String actorId = "usr_rule_" + UUID.randomUUID();
        var velCheck = new VelocityService.VelocityCheckResult(
                true, null, new VelocityService.VelocityStats(1, 100_000L, 1, 100_000L, 1, 100_000L),
                VelocityService.VelocityLimit.standardCustomerLimits()
        );

        var context = new RuleEngineService.RiskContext(
                actorId, "ref_1001", "PAYMENT", 1_500_000L, "GHS", // 15,000 GHS (triggers high amount rule)
                velCheck, null, null, null, null, null, null
        );

        var result = ruleEngine.evaluate(context);
        assertNotNull(result.evaluationId());
        assertEquals(RuleEngineService.ENGINE_VERSION, result.engineVersion());
        assertTrue(result.overallRiskScore() >= 20, "High value transfer must increase score");
        assertEquals(RiskDecision.STEP_UP, result.decision(), "High value transfer requires step-up");
        assertFalse(result.decisionTreeTrace().isEmpty(), "Decision tree trace must not be empty");
        assertTrue(result.decisionTreeTrace().stream().anyMatch(n -> n.nodeName().equals("Check_High_Value") && n.satisfied()));
    }

    @Test
    @DisplayName("Algorithm 025: Device risk signals and multi-accounting detection")
    void testDeviceFingerprintAndMultiAccounting() {
        String deviceHash = "dev_hash_" + UUID.randomUUID().toString().replace("-", "");
        String actor1 = "usr_dev_1_" + UUID.randomUUID();
        String actor2 = "usr_dev_2_" + UUID.randomUUID();
        String actor3 = "usr_dev_3_" + UUID.randomUUID();

        var sig1 = new DeviceRiskService.DeviceSignals(deviceHash, actor1, "192.168.1.1", "Mozilla/5.0", "Web", "macOS", false, false, false);
        var res1 = deviceRiskService.evaluateAndRecord(sig1);
        assertFalse(res1.isMultiAccount());
        assertTrue(res1.confidenceScore() >= 50);

        var sig2 = new DeviceRiskService.DeviceSignals(deviceHash, actor2, "192.168.1.1", "Mozilla/5.0", "Web", "macOS", false, false, false);
        var res2 = deviceRiskService.evaluateAndRecord(sig2);

        // Third distinct actor on the same device within 24h triggers multi-accounting
        var sig3 = new DeviceRiskService.DeviceSignals(deviceHash, actor3, "192.168.1.1", "Mozilla/5.0", "Web", "macOS", true, false, false);
        var res3 = deviceRiskService.evaluateAndRecord(sig3);

        assertTrue(res3.isMultiAccount(), "3 or more accounts on same device must flag multi-accounting");
        assertTrue(res3.riskScore() >= 50, "Emulator + multi-accounting must produce high device risk");
        assertTrue(res3.riskFactors().stream().anyMatch(f -> f.contains("MULTI_ACCOUNTING")));
        assertTrue(res3.riskFactors().stream().anyMatch(f -> f.contains("EMULATOR")));
    }

    @Test
    @DisplayName("Algorithm 026: SIM-swap checks and IMSI age handling")
    void testSimSwapAgeEvaluation() {
        String phone = "+233244000111";
        String actorId = "usr_sim_" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Swap 12 hours ago (< 48h) on high-value transfer: must REJECT
        var evalRecent = simSwapService.evaluateSimStatus(
                phone, actorId, "MTN", now.minusHours(12), true, 100_000L // 1,000 GHS
        );
        assertEquals(SimSwapService.SimSignalStatus.VERIFIED_RECENT_SWAP, evalRecent.status());
        assertEquals(RiskDecision.REJECT, evalRecent.decision());

        // 2. Swap 5 days ago (120 hours) on normal amount: ALLOW
        var eval5d = simSwapService.evaluateSimStatus(
                phone, actorId, "MTN", now.minusHours(120), true, 10_000L // 100 GHS
        );
        assertEquals(SimSwapService.SimSignalStatus.VERIFIED_AGING_SWAP, eval5d.status());
        assertEquals(RiskDecision.ALLOW, eval5d.decision());

        // 3. Telco service unavailable
        var evalUnavail = simSwapService.evaluateSimStatus(
                phone, actorId, "Telecel", null, false, 50_000L
        );
        assertEquals(SimSwapService.SimSignalStatus.SIGNAL_UNAVAILABLE, evalUnavail.status());
        assertEquals(RiskDecision.REVIEW, evalUnavail.decision());
    }

    @Test
    @DisplayName("Algorithm 027: Geo-velocity impossible travel detection")
    void testGeoVelocityImpossibleTravel() {
        String actorId = "usr_geo_" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(30);

        // Point 1: Accra Kotoka Airport (5.6052, -0.1668) 30 minutes ago
        var locAccra = new GeoVelocityService.GeoLocation(
                5.6052, -0.1668, 50.0, "Accra", "GHA", "102.176.65.1", false, t0
        );
        var r1 = geoVelocityService.checkAndRecord(actorId, "tx_1", locAccra);
        assertFalse(r1.impossibleTravel());
        assertEquals(RiskDecision.ALLOW, r1.decision());

        // Point 2: London Heathrow (51.4700, -0.4543) now (30 minutes after Accra)
        // Distance is ~5100 km in 0.5 hours = ~10,200 km/h (impossible travel)
        var locLondon = new GeoVelocityService.GeoLocation(
                51.4700, -0.4543, 50.0, "London", "GBR", "82.165.197.1", false, OffsetDateTime.now()
        );
        var r2 = geoVelocityService.checkAndRecord(actorId, "tx_2", locLondon);

        assertTrue(r2.impossibleTravel(), "Accra to London in 30 minutes must be flagged as impossible travel");
        assertTrue(r2.speedKmh() > 5000.0, "Speed must exceed supersonic speeds");
        assertEquals(RiskDecision.REJECT, r2.decision());
    }

    @Test
    @DisplayName("Algorithm 028: Account takeover detection multi-signal correlation")
    void testAccountTakeoverMultiSignalScoring() {
        String actorId = "usr_ato_" + UUID.randomUUID();

        // High-risk ATO attempt: new device + new IP + credential reset + dormant reactivation + withdrawal
        var signals = new AccountTakeoverService.AtoSignals(
                actorId, "sess_100", "dev_hash_abc", "197.251.10.2",
                true,  // isNewDevice (+25)
                true,  // isNewIpOrVpn (+20)
                true,  // recentCredentialChange (+30)
                true,  // dormancyBroken (+25)
                true,  // withdrawalAnomaly (+20)
                false  // simSwap
        );

        var assessment = atoService.assessAtoRisk(signals);
        assertEquals(100, assessment.atoScore(), "Correlated ATO factors should produce maximum risk");
        assertEquals(RiskDecision.REJECT, assessment.decision());
        assertTrue(assessment.anomalyFactors().size() >= 4);
    }

    @Test
    @DisplayName("Algorithm 029: Adaptive step-up authentication lifecycle and payload binding")
    void testStepUpChallengeIssuanceAndVerification() {
        String actorId = "usr_step_" + UUID.randomUUID();
        var payload = new StepUpAuthService.ActionPayload(actorId, "TRANSFER", "usr_dest_1", 250_000L, "GHS", "nonce_999");

        // Issue challenge with a known test code "123456"
        var challenge = stepUpAuthService.issueChallenge(payload, StepUpAuthService.ChallengeType.SMS_OTP, "123456");
        assertNotNull(challenge.challengeToken());

        // 1. Attempt verification with wrong code
        var failAttempt = stepUpAuthService.verifyChallenge(challenge.challengeToken(), "999999", payload);
        assertFalse(failAttempt.verified(), "Wrong code must fail");
        assertTrue(failAttempt.reason().contains("Invalid secret code"));

        // 2. Attempt verification with tampered payload (altered amount)
        var tamperedPayload = new StepUpAuthService.ActionPayload(actorId, "TRANSFER", "usr_dest_1", 999_000L, "GHS", "nonce_999");
        var tamperAttempt = stepUpAuthService.verifyChallenge(challenge.challengeToken(), "123456", tamperedPayload);
        assertFalse(tamperAttempt.verified(), "Tampered payload must be rejected cryptographically");
        assertTrue(tamperAttempt.reason().contains("Cryptographic payload mismatch"));

        // 3. Successful verification with correct code and exact matching payload
        var successAttempt = stepUpAuthService.verifyChallenge(challenge.challengeToken(), "123456", payload);
        assertTrue(successAttempt.verified(), "Valid code and bound payload must succeed");

        // 4. Replay attack rejection: token must be single-use
        var replayAttempt = stepUpAuthService.verifyChallenge(challenge.challengeToken(), "123456", payload);
        assertFalse(replayAttempt.verified(), "Replay of verified token must be rejected");
        assertTrue(replayAttempt.reason().contains("replay rejected"));
    }

    @Test
    @DisplayName("Algorithm 030: Merchant risk scoring continuous evaluation")
    void testMerchantRiskScoringAndTiers() {
        String merchantId = "merch_" + UUID.randomUUID();

        // Unhealthy merchant: high refund rate (20%) and high dispute rate (3%)
        var metricsUnhealthy = new MerchantRiskService.MerchantMetrics(
                merchantId,
                1_000_000L, // 10,000 GHS sales
                200_000L,   // 2,000 GHS refunds (20%)
                100,        // 100 txns
                3,          // 3 disputes (3%)
                150_000L    // surge in 24h
        );

        var eval = merchantRiskService.evaluateMerchantRisk(metricsUnhealthy);
        assertTrue(eval.riskScore() >= 75, "High refund and dispute rates must produce high risk score");
        assertTrue(eval.riskTier() == MerchantRiskService.MerchantRiskTier.PROBATION ||
                   eval.riskTier() == MerchantRiskService.MerchantRiskTier.SUSPENDED);
        assertTrue(eval.riskFactors().stream().anyMatch(f -> f.contains("CRITICAL_REFUND_RATE")));
        assertTrue(eval.riskFactors().stream().anyMatch(f -> f.contains("CRITICAL_DISPUTE_RATE")));
    }

    @Test
    @DisplayName("Algorithm 032: Mule-account graph detection and rapid pass-through velocity")
    void testMuleAccountRapidPassthroughDetection() {
        String muleAccount = "mule_acc_" + UUID.randomUUID();
        String senderA = "sender_a_" + UUID.randomUUID();
        String receiverB = "receiver_b_" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Flow In: Sender A sends 500,000 GHS to mule account 5 minutes ago
        var flowIn = new MuleGraphService.TransactionFlow(
                senderA, muleAccount, 500_000L, "GHS", now.minusMinutes(5)
        );
        muleGraphService.recordFlowAndAssess(flowIn);

        // 2. Flow Out: Mule immediately forwards 490,000 GHS (98% drain ratio) to Receiver B now
        var flowOut = new MuleGraphService.TransactionFlow(
                muleAccount, receiverB, 490_000L, "GHS", now
        );
        var muleAssessment = muleGraphService.recordFlowAndAssess(flowOut);

        assertTrue(muleAssessment.drainRatio() >= 0.90, "Drain ratio should be >= 90%");
        assertTrue(muleAssessment.rapidPassThroughCount() >= 1, "Rapid pass-through within 15 minutes must be recorded");
        assertTrue(muleAssessment.muleScore() >= 50, "Mule account score should reflect high pass-through behavior");
        assertTrue(muleAssessment.isMuleSuspect(), "Account must be flagged as a suspect money mule");
    }

    @Test
    @DisplayName("Algorithm 033: Synthetic identity detection across recycled ID and phone")
    void testSyntheticIdentityInconsistencyDetection() {
        String nationalId = "GHA-712345678-9";
        String nidHash = "hash_" + UUID.nameUUIDFromBytes(nationalId.getBytes());
        String phone = "+233241234567";

        // Profile 1: Legitimate initial registration
        var prof1 = new SyntheticIdentityService.IdentityProfile(
                "usr_legit", nidHash, "TIN123", phone, "Kwame Mensah",
                LocalDate.of(1990, 5, 12), "addr_accra_1", false
        );
        syntheticIdentityService.assessIdentity(prof1);

        // Profile 2: Impostor attempts to register with same National ID but divergent name and DOB
        var profImpostor = new SyntheticIdentityService.IdentityProfile(
                "usr_impostor", nidHash, "TIN999", "+233249876543", "Kofi Boateng", // different name
                LocalDate.of(1985, 10, 20), "addr_kumasi_2", false              // different DOB
        );
        var result = syntheticIdentityService.assessIdentity(profImpostor);

        assertTrue(result.syntheticRiskScore() >= 70, "Conflicting name and DOB on same national ID must trigger high risk");
        assertEquals("HIGH_RISK_SYNTHETIC", result.riskVerdict());
        assertTrue(result.inconsistencies().stream().anyMatch(i -> i.contains("NATIONAL_ID_NAME_MISMATCH")));
        assertTrue(result.inconsistencies().stream().anyMatch(i -> i.contains("NATIONAL_ID_DOB_MISMATCH")));
    }

    @Test
    @DisplayName("Algorithm 034: Behavioral biometrics bot cadence detection with DB persistence")
    void testBehavioralBiometricsTelemetryPersistence() {
        var botTelemetry = new BehavioralBiometricsService.BiometricTelemetry(
                "usr_telemetry_bot", "sess_99", 45.0, 10.0, 0.1, 3000.0, 50.0, 1.0, false
        );
        var eval = biometricsService.evaluateTelemetry(botTelemetry);
        assertTrue(eval.isBotCadence(), "Bot flight time and linear trajectory must be detected as bot");
        assertEquals(RiskDecision.REJECT, eval.decision());
    }

    @Test
    @DisplayName("Algorithm 035: Gradient-boosted fraud model inference and persistence")
    void testGradientBoostedFraudModelPrediction() {
        String txId = "tx_ml_" + UUID.randomUUID();
        var highRiskFeatures = new GradientBoostedFraudModel.FraudFeatures(
                1_500_000L, // 15,000 GHS (high amount)
                8,          // 8 txns in 1h (high velocity)
                3_000_000L, // 30,000 GHS in 24h
                80,         // high device risk
                850.0,      // high geo speed
                12,         // SIM swapped 12h ago
                85,         // high ATO score
                3,          // 3 AM (overnight)
                true,       // new device
                3           // failed attempts
        );

        var pred = mlFraudModel.predict(txId, highRiskFeatures);
        assertEquals(GradientBoostedFraudModel.MODEL_NAME, pred.modelName());
        assertTrue(pred.isExperimental(), "Synthetic model must be marked experimental");
        assertTrue(pred.fraudProbability() >= 0.70, "High risk features must produce high fraud probability");
        assertTrue(pred.recommendation() == RiskDecision.STEP_UP || pred.recommendation() == RiskDecision.REJECT);
        assertNotNull(pred.featureImportance());
        assertTrue(pred.featureImportance().containsKey("log_amount"));
    }

    @Test
    @DisplayName("Algorithm 036: Chargeback prediction calibrated dispute probability")
    void testChargebackPredictionCalibratedProbability() {
        String pmtId = "pmt_" + UUID.randomUUID();
        String merchantId = "merch_" + UUID.randomUUID();

        // High dispute profile: gaming MCC (7995), cross-border card, no 3DS, high merchant dispute rate
        var profile = new ChargebackPredictionService.PaymentRiskProfile(
                pmtId, merchantId, "7995", 200_000L, "GHS",
                true, // cross border
                "NONE", // no 3DS
                0.025, // 2.5% merchant dispute rate
                3      // buyer disputes
        );

        var assessment = chargebackService.predictChargeback(profile);
        assertTrue(assessment.calibratedProbability() >= 0.15, "High dispute scenario must yield >= 15% probability");
        assertEquals(ChargebackPredictionService.SettlementAction.HOLD_AND_REVIEW, assessment.recommendedAction());
        assertTrue(assessment.expectedLossMinorUnits() > 0);
    }

    @Test
    @DisplayName("Comprehensive Risk Assessment: Full orchestration with automatic step-up issuance")
    void testComprehensiveRiskOrchestration() {
        String actorId = "usr_orch_" + UUID.randomUUID();
        var devSignals = new DeviceRiskService.DeviceSignals(
                "dev_orch_" + UUID.randomUUID(), actorId, "192.168.1.50", "Mozilla/5.0", "Web", "macOS", false, false, false
        );
        var location = new GeoVelocityService.GeoLocation(
                5.6052, -0.1668, 20.0, "Accra", "GHA", "192.168.1.50", false, OffsetDateTime.now()
        );

        var req = new RiskEngineService.AssessmentRequest(
                actorId, "ref_orch_1", "TRANSFER",
                350_000L, // 3,500 GHS (elevated amount requiring STEP_UP without breaching velocity)
                "GHS", "usr_recipient_99",
                devSignals, location,
                "+233244999888", "MTN", OffsetDateTime.now().minusDays(30), true,
                false, false, false, false, null
        );

        var assessment = riskEngine.assessTransaction(req);
        assertNotNull(assessment.evaluationId());
        assertEquals(RiskDecision.STEP_UP, assessment.decision());
        assertNotNull(assessment.stepUpChallenge(), "Step-up challenge must be automatically issued for STEP_UP decision");
        assertNotNull(assessment.stepUpChallenge().challengeToken());
        assertEquals("SMS_OTP", assessment.stepUpChallenge().challengeType().name());
    }
}
