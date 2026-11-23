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
     * Performs sanctions screening and KYC tier daily limit checks.
     */
    public ComplianceCheckResult evaluate(
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

        // 2. KYC Tier Limits
        long dailyLimit = getDailyLimitForTier(kycTier);
        if (amountPesewas > dailyLimit) {
            return ComplianceCheckResult.dailyLimitExceeded(
                    amountPesewas,
                    dailyLimit,
                    "Requested amount (" + amountPesewas + ") exceeds Bank of Ghana Tier "
                            + kycTier + " daily limit (" + dailyLimit + ")"
            );
        }

        // 3. Daily Cumulative Limit Check
        LocalDate today = LocalDate.now();
        long currentTotal = getDailyTotal(enterpriseId, externalCustomerId, today);
        long projectedTotal = Math.addExact(currentTotal, amountPesewas);

        if (projectedTotal > dailyLimit) {
            return ComplianceCheckResult.dailyLimitExceeded(
                    projectedTotal,
                    dailyLimit,
                    "Projected daily total (" + projectedTotal + ") exceeds Bank of Ghana Tier "
                            + kycTier + " daily limit (" + dailyLimit + ")"
            );
        }

        return ComplianceCheckResult.passed(currentTotal, dailyLimit);
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
        String key = accumulatorKey(enterpriseId, externalCustomerId, date);
        Long inMem = dailyAccumulators.get(key);
        if (inMem != null) return inMem;

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
                    dailyAccumulators.put(key, total);
                    return total;
                }
            } catch (Exception ignored) {}
        }

        return 0L;
    }

    private static String accumulatorKey(UUID enterpriseId, String customerId, LocalDate date) {
        return enterpriseId + ":" + customerId + ":" + date;
    }
}
