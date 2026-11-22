package com.elepay.compliance;

import com.elepay.common.ApiException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Ghana Card matching engine (Algorithm 040).
 * Connects to the National Identification Authority (NIA) verification channel,
 * distinguishes simulated from live verification, validates format and check digits,
 * and records auditable verification status.
 */
public class GhanaCardVerificationService {

    private static final Pattern GHANA_CARD_PATTERN = Pattern.compile("^GHA-(\\d{9})-(\\d)$");

    public enum Status {
        VERIFIED,
        MISMATCHED,
        UNAVAILABLE,
        FAILED
    }

    public record GhanaCardVerificationResult(
            UUID id,
            UUID customerId,
            String idNumber,
            String fullName,
            LocalDate dateOfBirth,
            String gender,
            Status status,
            double confidenceScore,
            boolean simulated,
            String simulationProvider,
            String details
    ) {}

    public interface NiaClient {
        NiaResponse queryNia(String idNumber, String fullName, LocalDate dateOfBirth);
        boolean isSimulated();
        String getProviderName();
    }

    public record NiaResponse(
            Status status,
            double matchScore,
            String nationalRegistryName,
            LocalDate nationalRegistryDob,
            String message
    ) {}

    private final JdbcClient db;
    private final NiaClient niaClient;

    public GhanaCardVerificationService(JdbcClient db) {
        this(db, null);
    }

    public GhanaCardVerificationService(JdbcClient db, NiaClient niaClient) {
        this.db = db;
        this.niaClient = niaClient != null ? niaClient : new UnconfiguredNiaClient();
    }

    public static boolean isValidFormat(String idNumber) {
        if (idNumber == null) return false;
        var matcher = GHANA_CARD_PATTERN.matcher(idNumber.trim());
        if (!matcher.matches()) return false;

        String digits = matcher.group(1);
        int expectedCheck = Integer.parseInt(matcher.group(2));
        int calculatedCheck = calculateLuhnCheckDigit(digits);
        return calculatedCheck == expectedCheck;
    }

    public GhanaCardVerificationResult verify(UUID customerId, String idNumber, String fullName, LocalDate dateOfBirth, String gender) {
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(idNumber, "idNumber cannot be null");
        Objects.requireNonNull(fullName, "fullName cannot be null");

        String normalizedId = idNumber.trim().toUpperCase();
        if (!isValidFormat(normalizedId)) {
            throw ApiException.badRequest("invalid_ghana_card_format", "Invalid Ghana Card number format or check digit");
        }

        NiaResponse response = niaClient.queryNia(normalizedId, fullName, dateOfBirth);
        UUID verificationId = UUID.randomUUID();
        boolean isSimulated = niaClient.isSimulated();
        String provider = niaClient.getProviderName();

        GhanaCardVerificationResult result = new GhanaCardVerificationResult(
                verificationId,
                customerId,
                normalizedId,
                fullName,
                dateOfBirth,
                gender,
                response.status(),
                response.matchScore(),
                isSimulated,
                provider,
                response.message()
        );

        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO ghana_card_verification
                    (id, customer_id, id_number, full_name, date_of_birth, gender, status, confidence_score, simulated, simulation_provider, verification_response, verified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    """)
                    .params(
                            result.id(),
                            result.customerId(),
                            result.idNumber(),
                            result.fullName(),
                            result.dateOfBirth(),
                            result.gender(),
                            result.status().name(),
                            result.confidenceScore(),
                            result.simulated(),
                            result.simulationProvider(),
                            result.details()
                    )
                    .update();
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Unable to persist identity verification", failure);
            }
        }

        return result;
    }

    public static int calculateLuhnCheckDigit(String number) {
        int sum = 0;
        boolean alternate = true;
        for (int i = number.length() - 1; i >= 0; i--) {
            int n = Integer.parseInt(number.substring(i, i + 1));
            if (alternate) {
                n *= 2;
                if (n > 9) n = (n % 10) + 1;
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum * 9) % 10;
    }

    /**
     * Production fallback when an official NIA client bean or credentials are not configured.
     * Always fails closed with {@link Status#UNAVAILABLE}.
     */
    public static class UnconfiguredNiaClient implements NiaClient {
        @Override
        public NiaResponse queryNia(String idNumber, String fullName, LocalDate dateOfBirth) {
            return new NiaResponse(
                    Status.UNAVAILABLE,
                    0.0,
                    null,
                    null,
                    "Official National Identification Authority (NIA) client bean or credentials not configured; verification unavailable"
            );
        }

        @Override
        public boolean isSimulated() {
            return false;
        }

        @Override
        public String getProviderName() {
            return "UNCONFIGURED_NIA";
        }
    }

    /**
     * Test double for isolated testing and sandbox environments only.
     * Never used as default fallback in production.
     */
    @Deprecated
    public static class SimulatedNiaClient implements NiaClient {
        @Override
        public NiaResponse queryNia(String idNumber, String fullName, LocalDate dateOfBirth) {
            if (idNumber.startsWith("GHA-999")) {
                // Spec requirement: distinguish unavailable from verified or mismatched
                return new NiaResponse(Status.UNAVAILABLE, 0.0, null, null, "NIA gateway timeout or service unavailable");
            }
            if (idNumber.startsWith("GHA-000")) {
                return new NiaResponse(Status.MISMATCHED, 35.0, "Different Person", LocalDate.of(1980, 1, 1), "Name and DOB do not match NIA database");
            }
            return new NiaResponse(Status.VERIFIED, 99.5, fullName, dateOfBirth, "Simulated successful NIA registry match");
        }

        @Override
        public boolean isSimulated() {
            return true;
        }

        @Override
        public String getProviderName() {
            return "SIMULATED_NIA_SANDBOX";
        }
    }
}
