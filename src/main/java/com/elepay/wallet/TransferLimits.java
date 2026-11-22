package com.elepay.wallet;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bank of Ghana (BoG) KYC Tier Limits and velocity enforcement (Algorithm 039).
 *
 * <ul>
 *   <li>Tier 1: Max balance 1,000 GHS (100,000 pesewas), Daily 1,000 GHS (100,000 pesewas), Monthly 10,000 GHS (1,000,000 pesewas).</li>
 *   <li>Tier 2: Max balance 10,000 GHS (1,000,000 pesewas), Daily 5,000 GHS (500,000 pesewas), Monthly 50,000 GHS (5,000,000 pesewas).</li>
 *   <li>Tier 3: Max balance 50,000 GHS (5,000,000 pesewas), Daily 20,000 GHS (2,000,000 pesewas), Monthly 200,000 GHS (20,000,000 pesewas).</li>
 * </ul>
 */
@Component
public class TransferLimits {
    private final JdbcClient db;
    private final com.elepay.ledger.Ledger ledger;
    private final long perTransfer;
    private final long perDay;
    private final int perMinute;

    public record TierLimit(int tier, long maxBalance, long dailyLimit, long monthlyLimit) {
        public static final TierLimit TIER_1 = new TierLimit(1, 100_000L, 100_000L, 1_000_000L);
        public static final TierLimit TIER_2 = new TierLimit(2, 1_000_000L, 500_000L, 5_000_000L);
        public static final TierLimit TIER_3 = new TierLimit(3, 5_000_000L, 2_000_000L, 20_000_000L);

        public static TierLimit forTier(int tier) {
            return switch (tier) {
                case 1 -> TIER_1;
                case 2 -> TIER_2;
                case 3 -> TIER_3;
                default -> throw new IllegalArgumentException("Unknown KYC tier: " + tier);
            };
        }
    }

    public TransferLimits(JdbcClient db, com.elepay.ledger.Ledger ledger,
                          @Value("${elepay.wallet.limits.transfer-pesewas:0}") long perTransfer,
                          @Value("${elepay.wallet.limits.daily-pesewas:0}") long perDay,
                          @Value("${elepay.wallet.limits.transfers-per-minute:0}") int perMinute) {
        if (perTransfer < 0 || perDay < 0 || perMinute < 0) {
            throw new IllegalArgumentException("Wallet transfer limits must be non-negative");
        }
        this.db = db;
        this.ledger = ledger;
        this.perTransfer = perTransfer;
        this.perDay = perDay;
        this.perMinute = perMinute;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void check(UUID sender, long amount) {
        if (amount <= 0) {
            throw ApiException.badRequest("invalid_amount", "Transfer amount must be positive");
        }
        if (perTransfer > 0 && amount > perTransfer) {
            throw ApiException.conflict("transfer_limit", "Amount exceeds the limit for one transfer");
        }

        // Hold the sender row and query kyc_tier and status
        record CustomerStatus(boolean active, int kycTier) {}
        CustomerStatus status = db.sql("SELECT status = 'active', kyc_tier FROM customer WHERE id = :id FOR UPDATE")
                .param("id", sender)
                .query((rs, i) -> new CustomerStatus(rs.getBoolean(1), rs.getInt(2)))
                .optional().orElse(null);

        if (status == null || !status.active()) {
            throw ApiException.conflict("wallet_unavailable", "The sending wallet is unavailable");
        }

        TierLimit tierLimit = TierLimit.forTier(status.kycTier());

        Usage usage = db.sql("""
                SELECT coalesce(sum(o.amount) FILTER (WHERE o.created_at >=
                           date_trunc('day', statement_timestamp(), 'Africa/Accra')
                           AND o.status NOT IN ('failed', 'returned')), 0),
                       coalesce(sum(o.amount) FILTER (WHERE o.created_at >=
                           date_trunc('month', statement_timestamp(), 'Africa/Accra')
                           AND o.status NOT IN ('failed', 'returned')), 0),
                       count(*) FILTER (WHERE o.created_at >= statement_timestamp() - interval '1 minute')
                FROM wallet_operation o
                WHERE o.customer_id = :sender AND o.kind = 'transfer_out'
                  AND o.created_at >= date_trunc('month', statement_timestamp(), 'Africa/Accra')
                """).param("sender", sender)
                .query((row, index) -> new Usage(row.getBigDecimal(1), row.getBigDecimal(2), row.getLong(3)))
                .single();

        if (perMinute > 0 && usage.recent() >= perMinute) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "transfer_rate_limit", "Too many recent transfers. Wait a minute and try again.");
        }

        // Check sender daily limit (BoG statutory limit, constrained by perDay if configured)
        long effectiveDailyLimit = perDay > 0 ? Math.min(perDay, tierLimit.dailyLimit()) : tierLimit.dailyLimit();
        if (usage.daily().add(BigDecimal.valueOf(amount)).compareTo(BigDecimal.valueOf(effectiveDailyLimit)) > 0) {
            throw ApiException.conflict("daily_transfer_limit",
                    "Amount exceeds the remaining daily transfer limit for KYC Tier " + status.kycTier());
        }

        // Check sender monthly limit
        if (usage.monthly().add(BigDecimal.valueOf(amount)).compareTo(BigDecimal.valueOf(tierLimit.monthlyLimit())) > 0) {
            throw ApiException.conflict("monthly_transfer_limit",
                    "Amount exceeds the remaining monthly transfer limit for KYC Tier " + status.kycTier());
        }
    }

    /**
     * Checks both sender daily/monthly limits and recipient balance limit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void check(UUID sender, UUID recipient, long amount) {
        check(sender, amount);

        record RecipientInfo(int kycTier, long balance) {}
        RecipientInfo rec = db.sql("""
                SELECT kyc_tier FROM customer WHERE id = :recipient
                """)
                .param("recipient", recipient)
                .query((rs, i) -> new RecipientInfo(rs.getInt(1), ledger.balance(CustomerDirectory.available(recipient))))
                .optional()
                .orElseThrow(() -> ApiException.conflict("wallet_unavailable", "Recipient wallet is unavailable"));

        TierLimit recLimit = TierLimit.forTier(rec.kycTier());
        long newBalance = Math.addExact(rec.balance(), amount);
        if (newBalance > recLimit.maxBalance()) {
            throw ApiException.conflict("recipient_balance_limit",
                    "Transfer would exceed recipient max balance limit of " + recLimit.maxBalance()
                            + " pesewas for KYC Tier " + rec.kycTier());
        }
    }

    private record Usage(BigDecimal daily, BigDecimal monthly, long recent) {}
}
