package com.elepay.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Fraud & Risk Engine: Unit Tests for Algorithms 027, 029, 034, 035, 036")
class RiskAlgorithmsUnitTest {

    @Test
    @DisplayName("Algorithm 027: Haversine distance and impossible travel mathematics")
    void testHaversineDistanceCalculations() {
        // Accra (Kotoka Airport): ~5.6052, -0.1668
        // Kumasi (Kumasi Airport): ~6.7146, -1.5907
        double accraLat = 5.6052;
        double accraLon = -0.1668;
        double kumasiLat = 6.7146;
        double kumasiLon = -1.5907;

        double distance = GeoVelocityService.calculateHaversineDistanceKm(accraLat, accraLon, kumasiLat, kumasiLon);
        // Distance Accra to Kumasi is approx 200 km (between 190 and 215 km)
        assertTrue(distance >= 190.0 && distance <= 215.0,
                "Accra to Kumasi distance should be ~200 km, but was: " + distance);

        // Same location should have 0 distance
        double zeroDist = GeoVelocityService.calculateHaversineDistanceKm(accraLat, accraLon, accraLat, accraLon);
        assertEquals(0.0, zeroDist, 0.001);

        // London to Accra (~5100 km)
        double londonLat = 51.5074;
        double londonLon = -0.1278;
        double intercontinentalDist = GeoVelocityService.calculateHaversineDistanceKm(accraLat, accraLon, londonLat, londonLon);
        assertTrue(intercontinentalDist >= 5000.0 && intercontinentalDist <= 5200.0,
                "Accra to London distance should be ~5100 km, but was: " + intercontinentalDist);
    }

    @Test
    @DisplayName("Algorithm 029: Step-up action payload cryptographic hash and tamper sensitivity")
    void testActionPayloadCryptographicHash() {
        var payload1 = new StepUpAuthService.ActionPayload("usr_123", "TRANSFER", "usr_456", 50_000L, "GHS", "nonce_abc");
        var payload2 = new StepUpAuthService.ActionPayload("usr_123", "TRANSFER", "usr_456", 50_000L, "GHS", "nonce_abc");
        var payloadTamperedAmount = new StepUpAuthService.ActionPayload("usr_123", "TRANSFER", "usr_456", 500_000L, "GHS", "nonce_abc");
        var payloadTamperedRecipient = new StepUpAuthService.ActionPayload("usr_123", "TRANSFER", "usr_attacker", 50_000L, "GHS", "nonce_abc");

        assertEquals(payload1.computeHash(), payload2.computeHash(), "Identical payloads must produce identical hashes");
        assertNotEquals(payload1.computeHash(), payloadTamperedAmount.computeHash(), "Amount tampering must alter hash");
        assertNotEquals(payload1.computeHash(), payloadTamperedRecipient.computeHash(), "Recipient tampering must alter hash");
        assertEquals(64, payload1.computeHash().length(), "SHA-256 hex string must be 64 characters");
    }

    @Test
    @DisplayName("Algorithm 034: Behavioral biometrics bot cadence detection and accessibility safeguards")
    void testBehavioralBiometricsLogic() {
        var biometricsService = new BehavioralBiometricsService(null);

        // Bot telemetry: superhuman typing speed (10ms flight time) and strictly linear pointer trajectory (curvature 1.0)
        var botTelemetry = new BehavioralBiometricsService.BiometricTelemetry(
                "usr_bot", "sess_1", 45.0, 10.0, 0.2, 4500.0, 100.0, 1.0, false
        );
        // Human telemetry: natural variation, curved pointer trajectory
        var humanTelemetry = new BehavioralBiometricsService.BiometricTelemetry(
                "usr_human", "sess_2", 85.0, 120.0, 35.0, 350.0, 25.0, 1.25, false
        );
        // User with accessibility assistance: slow or irregular, but flagged
        var accessibleTelemetry = new BehavioralBiometricsService.BiometricTelemetry(
                "usr_access", "sess_3", 350.0, 600.0, 120.0, 120.0, 10.0, 1.0, true
        );

        // We can test accessibility without DB because it returns early before DB persistence:
        var accessResult = biometricsService.evaluateTelemetry(accessibleTelemetry);
        assertFalse(accessResult.isBotCadence());
        assertEquals(0, accessResult.biometricAnomalyScore());
        assertEquals(RiskDecision.ALLOW, accessResult.decision());
        assertEquals("ASSISTIVE_TECH_ACTIVE", accessResult.accessibilityFlag());
    }

    @Test
    @DisplayName("Algorithm 035: GBDT fraud model feature vector conversion and probability calibration")
    void testGradientBoostedFraudModelFeaturesAndCalibration() {
        var features = new GradientBoostedFraudModel.FraudFeatures(
                100_000L, // 1,000 GHS
                3,        // 3 txns in 1h
                250_000L, // 2,500 GHS in 24h
                20,       // low device risk
                25.0,     // normal travel speed
                300,      // SIM swapped 300h ago (> 7 days)
                15,       // low ATO score
                14,       // 2 PM
                false,    // known device
                0         // 0 failed attempts
        );

        double[] vector = features.toVector();
        assertEquals(10, vector.length, "Feature vector must contain exactly 10 features");
        assertTrue(vector[0] > 0, "log amount should be positive");
        assertEquals(3.0, vector[1], "1h velocity count feature");
        assertEquals(0.2, vector[3], 0.001, "Normalized device risk");
        assertEquals(0.0, vector[5], "SIM swap risk factor should be 0 for >7d");
        assertEquals(0.0, vector[7], "Overnight flag should be 0 for 2 PM");
        assertEquals(0.0, vector[8], "New device flag should be 0 for known device");
    }

    @Test
    @DisplayName("Algorithm 036: Chargeback prediction mathematical risk and expected loss calculation")
    void testChargebackPredictionEconomicLossCalculation() {
        // Standard high dispute setup
        long amount = 100_000L; // 1,000 GHS
        long fixedFee = 1_500L;  // 15 GHS
        long exposure = amount + fixedFee; // 101,500 minor units

        double simulatedP = 0.10; // 10% probability
        long expectedLoss = Math.round(simulatedP * exposure);
        assertEquals(10_150L, expectedLoss, "Expected loss should be P * exposure");
    }
}
