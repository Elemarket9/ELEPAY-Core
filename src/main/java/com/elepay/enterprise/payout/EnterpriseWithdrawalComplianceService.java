package com.elepay.enterprise.payout;

import com.elepay.compliance.SanctionsScreeningService;
import com.elepay.compliance.SanctionsScreeningService.ScreeningMatch;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compliance screening service for enterprise wallet withdrawals.
 * Evaluates Bank of Ghana KYC tier daily limits and executes fuzzy sanctions screening.
 */
@Service
public class EnterpriseWithdrawalComplianceService {

    // Bank of Ghana Tier Daily Limits in minor units (Pesewas)
    public static final long BOG_TIER_1_DAILY_LIMIT = 100_000L;   // GH₵ 1,000.00
    public static final long BOG_TIER_2_DAILY_LIMIT = 500_000L;   // GH₵ 5,000.00
    public static final long BOG_TIER_3_DAILY_LIMIT = 1_000_000L; // GH₵ 10,000.00

    private final SanctionsScreeningService sanctionsService;
    private final JdbcClient db;

    // Concurrent in-memory daily accumulator: "enterpriseId:customerId:date" -> totalWithdrawnPesewas
    private final Map<String, Long> dailyAccumulators = new ConcurrentHashMap<>();

    public EnterpriseWithdrawalComplianceService(
            @org.springframework.beans.factory.annotation.Autowired(required = false) SanctionsScreeningService sanctionsService,
            JdbcClient db
    ) {
        this.sanctionsService = sanctionsService != null ? sanctionsService : new SanctionsScreeningService(db);
        this.db = db;
    }

    /**
     * Resolves the maximum daily withdrawal limit for a customer given their KYC tier.
     */
    public long getDailyLimitForTier(int kycTier) {
        return switch (kycTier) {
            case 3 -> BOG_TIER_3_DAILY_LIMIT;
            case 2 -> BOG_TIER_2_DAILY_LIMIT;
            default -> BOG_TIER_1_DAILY_LIMIT;
        };
    }

    /**
     * Atomically validates compliance and reserves the daily withdrawal amount under a row-level lock.
     * Prevents race conditions where concurrent withdrawals could bypass Bank of Ghana KYC Tier daily limits.
     */
    @org.springframework.transaction.annotation.Transactional
    public ComplianceCheckResult reserveWithdrawal(
            UUID enterpriseId,
            String externalCustomerId,
            String customerName,
            int kycTier,
            long amountPesewas
    ) {
        // 1. Sanctions Screening
        if (sanctionsService != null && customerName != null && !customerName.isBlank()) {
            List<ScreeningMatch> matches = sanctionsService.screenName(customerName);
            for (ScreeningMatch match : matches) {
                if (match.similarityScore() >= 0.85) {
                    return ComplianceCheckResult.sanctionsViolation(
                            "Sanctions screening match detected (" + match.matchType() + " score="
                                    + String.format("%.2f", match.similarityScore()) + ") against listed entity: "
                                    + match.matchedEntry().entityName()
                    );
                }
            }
        }

        // 2. KYC Tier Limits (Per-transaction single amount check)
        long dailyLimit = getDailyLimitForTier(kycTier);
        if (amountPesewas > dailyLimit) {
            return ComplianceCheckResult.dailyLimitExceeded(
                    amountPesewas,
                    dailyLimit,
                    "Requested amount (" + amountPesewas + ") exceeds Bank of Ghana Tier "
                            + kycTier + " daily limit (" + dailyLimit + ")"
            );
        }

        LocalDate today = LocalDate.now();
        String key = accumulatorKey(enterpriseId, externalCustomerId, today);

        // 3. Daily Cumulative Limit Check with Row-Level Lock & Atomic Reservation
        if (db != null) {
            // Ensure record exists for customer and date
            db.sql("""
                INSERT INTO enterprise_withdrawal_daily_accumulators
                (id, enterprise_id, external_customer_id, accumulation_date, total_withdrawn_pesewas, transaction_count, updated_at)
                VALUES (:id, :eid, :cid, :dt, 0, 0, now())
                ON CONFLICT (enterprise_id, external_customer_id, accumulation_date) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("eid", enterpriseId)
                .param("cid", externalCustomerId)
                .param("dt", today)
                .update();

            // Lock the accumulator row exclusively
            Long currentTotal = db.sql("""
                SELECT total_withdrawn_pesewas FROM enterprise_withdrawal_daily_accumulators
                WHERE enterprise_id = :eid AND external_customer_id = :cid AND accumulation_date = :dt
                FOR UPDATE
                """)
                .param("eid", enterpriseId)
                .param("cid", externalCustomerId)
                .param("dt", today)
                .query(Long.class)
                .single();

            long projectedTotal = Math.addExact(currentTotal, amountPesewas);
            if (projectedTotal > dailyLimit) {
                return ComplianceCheckResult.dailyLimitExceeded(
                        projectedTotal,
                        dailyLimit,
                        "Projected daily total (" + projectedTotal + ") exceeds Bank of Ghana Tier "
                                + kycTier + " daily limit (" + dailyLimit + ")"
                );
            }

            // Atomically update under row lock
            db.sql("""
                UPDATE enterprise_withdrawal_daily_accumulators
                SET total_withdrawn_pesewas = :projected,
                    transaction_count = transaction_count + 1,
                    updated_at = now()
                WHERE enterprise_id = :eid AND external_customer_id = :cid AND accumulation_date = :dt
                """)
                .param("projected", projectedTotal)
                .param("eid", enterpriseId)
                .param("cid", externalCustomerId)
                .param("dt", today)
                .update();

            dailyAccumulators.put(key, projectedTotal);
            return ComplianceCheckResult.passed(currentTotal, dailyLimit);
        } else {
            // In-memory synchronization fallback for non-DB unit tests
            synchronized (key.intern()) {
                long currentTotal = dailyAccumulators.getOrDefault(key, 0L);
                long projectedTotal = Math.addExact(currentTotal, amountPesewas);
                if (projectedTotal > dailyLimit) {
                    return ComplianceCheckResult.dailyLimitExceeded(
                            projectedTotal,
                            dailyLimit,
                            "Projected daily total (" + projectedTotal + ") exceeds Bank of Ghana Tier "
                                    + kycTier + " daily limit (" + dailyLimit + ")"
                    );
                }
                dailyAccumulators.put(key, projectedTotal);
                return ComplianceCheckResult.passed(currentTotal, dailyLimit);
            }
        }
    }

    /**
     * Releases a previously reserved withdrawal amount if subsequent processing fails.
     */
    public void releaseReservation(UUID enterpriseId, String externalCustomerId, long amountPesewas) {
        LocalDate today = LocalDate.now();
        String key = accumulatorKey(enterpriseId, externalCustomerId, today);
        dailyAccumulators.computeIfPresent(key, (k, v) -> Math.max(0L, v - amountPesewas));

        if (db != null) {
            try {
                db.sql("""
                    UPDATE enterprise_withdrawal_daily_accumulators
                    SET total_withdrawn_pesewas = GREATEST(0, total_withdrawn_pesewas - :amt),
                        transaction_count = GREATEST(0, transaction_count - 1),
                        updated_at = now()
                    WHERE enterprise_id = :eid AND external_customer_id = :cid AND accumulation_date = :dt
                    """)
                    .param("amt", amountPesewas)
                    .param("eid", enterpriseId)
                    .param("cid", externalCustomerId)
                    .param("dt", today)
                    .update();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Performs sanctions screening and KYC tier daily limit checks.
     * Delegates to atomic reservation to guarantee serialization under concurrency.
     */
    public ComplianceCheckResult evaluate(
            UUID enterpriseId,
            String externalCustomerId,
            String customerName,
            int kycTier,
            long amountPesewas
    ) {
        return reserveWithdrawal(enterpriseId, externalCustomerId, customerName, kycTier, amountPesewas);
    }

    /**
     * Records a successful withdrawal into the customer's daily accumulator.
     */
    public void recordWithdrawal(UUID enterpriseId, String externalCustomerId, long amountPesewas) {
        LocalDate today = LocalDate.now();
        String key = accumulatorKey(enterpriseId, externalCustomerId, today);
        dailyAccumulators.merge(key, amountPesewas, Math::addExact);

        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO enterprise_withdrawal_daily_accumulators
                    (id, enterprise_id, external_customer_id, accumulation_date, total_withdrawn_pesewas, transaction_count, updated_at)
                    VALUES (:id, :eid, :cid, :dt, :amt, 1, now())
                    ON CONFLICT (enterprise_id, external_customer_id, accumulation_date) DO UPDATE
                    SET total_withdrawn_pesewas = enterprise_withdrawal_daily_accumulators.total_withdrawn_pesewas + EXCLUDED.total_withdrawn_pesewas,
                        transaction_count = enterprise_withdrawal_daily_accumulators.transaction_count + 1,
                        updated_at = now()
                    """)
                    .param("id", UUID.randomUUID())
                    .param("eid", enterpriseId)
                    .param("cid", externalCustomerId)
                    .param("dt", today)
                    .param("amt", amountPesewas)
                    .update();
            } catch (Exception ignored) {}
        }
    }

    public long getDailyTotal(UUID enterpriseId, String externalCustomerId, LocalDate date) {
        if (db != null) {
            try {
                Long total = db.sql("""
                    SELECT total_withdrawn_pesewas FROM enterprise_withdrawal_daily_accumulators
                    WHERE enterprise_id = :eid AND external_customer_id = :cid AND accumulation_date = :dt
                    """)
                    .param("eid", enterpriseId)
                    .param("cid", externalCustomerId)
                    .param("dt", date)
                    .query(Long.class)
                    .optional().orElse(null);
                if (total != null) {
                    return total;
                }
            } catch (Exception ignored) {}
        }

        String key = accumulatorKey(enterpriseId, externalCustomerId, date);
        return dailyAccumulators.getOrDefault(key, 0L);
    }

    private static String accumulatorKey(UUID enterpriseId, String customerId, LocalDate date) {
        return enterpriseId + ":" + customerId + ":" + date;
    }
}
