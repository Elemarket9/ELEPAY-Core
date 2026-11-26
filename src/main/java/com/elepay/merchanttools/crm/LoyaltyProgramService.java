package com.elepay.merchanttools.crm;

import com.elepay.common.ApiException;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 7: Points-Based and Tiered Customer Loyalty Program.
 * Supports tier progression (Bronze, Silver, Gold, Platinum), tier multiplier bonuses,
 * point earning on transactions, point redemption for monetary discounts, and full audit ledger.
 */
@Service
public class LoyaltyProgramService {

    public static final int BASE_POINTS_PER_1000_PESEWAS = 1; // 1 point per GHS 10.00
    public static final long EARN_RATE_PESEWAS_PER_POINT = 1000L; // 1,000 pesewas (GHS 10.00) per base point
    public static final long PESEWAS_PER_REDEEMED_POINT = 10L;    // 1 point = 10 pesewas (GHS 0.10)

    private final JdbcClient db;

    public LoyaltyProgramService(JdbcClient db) {
        this.db = db;
    }

    public enum LoyaltyTier {
        BRONZE(100, 0),        // 1.0x, min 0 points
        SILVER(125, 500),      // 1.25x, min 500 points
        GOLD(150, 2000),       // 1.5x, min 2000 points
        PLATINUM(200, 5000);   // 2.0x, min 5000 points

        private final int multiplierBps;
        private final long minLifetimePoints;

        LoyaltyTier(int multiplierBps, long minLifetimePoints) {
            this.multiplierBps = multiplierBps;
            this.minLifetimePoints = minLifetimePoints;
        }

        public int getMultiplierBps() {
            return multiplierBps;
        }

        public long getMinLifetimePoints() {
            return minLifetimePoints;
        }

        public static LoyaltyTier determineTier(long lifetimePoints) {
            if (lifetimePoints >= PLATINUM.minLifetimePoints) return PLATINUM;
            if (lifetimePoints >= GOLD.minLifetimePoints) return GOLD;
            if (lifetimePoints >= SILVER.minLifetimePoints) return SILVER;
            return BRONZE;
        }
    }

    public record LoyaltyAccountSummary(
            UUID id,
            UUID merchantId,
            UUID customerId,
            long pointsBalance,
            long lifetimePointsEarned,
            long lifetimePointsRedeemed,
            LoyaltyTier currentTier
    ) {}

    public record PointTransactionResult(
            UUID transactionId,
            long pointsDelta,
            long newPointsBalance,
            LoyaltyTier currentTier,
            long monetaryEquivalentPesewas
    ) {}

    private record StoredIdempotentEntry(
            UUID id,
            long points,
            String payloadHash,
            long balanceAfter,
            String tierAfter,
            long monetaryEquivalentPesewas
    ) {}

    /**
     * Initializes or retrieves the loyalty account for a merchant customer (concurrency-safe).
     */
    @Transactional
    public LoyaltyAccountSummary getOrCreateAccount(UUID merchantId, UUID customerId) {
        UUID newId = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_loyalty_account
            (id, merchant_id, customer_id, points_balance, lifetime_points_earned, lifetime_points_redeemed, current_tier)
            VALUES (?, ?, ?, 0, 0, 0, 'BRONZE')
            ON CONFLICT (merchant_id, customer_id) DO NOTHING
        """).params(newId, merchantId, customerId).update();

        return db.sql("""
            SELECT id, merchant_id, customer_id, points_balance, lifetime_points_earned,
                   lifetime_points_redeemed, current_tier
            FROM crm_loyalty_account WHERE merchant_id = ? AND customer_id = ?
        """).params(merchantId, customerId)
        .query((rs, rowNum) -> new LoyaltyAccountSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getLong("points_balance"),
                rs.getLong("lifetime_points_earned"),
                rs.getLong("lifetime_points_redeemed"),
                LoyaltyTier.valueOf(rs.getString("current_tier"))
        )).single();
    }

    /**
     * Acquires a row lock on the customer's loyalty account and checks whether an idempotency key
     * has already been processed. Returns the original result on identical replay, throws 409 on
     * payload mismatch, or returns empty if the key has not been used yet.
     */
    @Transactional
    public Optional<PointTransactionResult> lockAccountAndFindIdempotentResult(
            UUID merchantId, UUID customerId, String idempotencyKey, String payloadHash) {
        LoyaltyAccountSummary account = getOrCreateAccount(merchantId, customerId);
        db.sql("SELECT id FROM crm_loyalty_account WHERE id = ? FOR UPDATE")
                .param(account.id()).query(UUID.class).single();
        return findExistingIdempotentEntry(merchantId, customerId, idempotencyKey, payloadHash);
    }

    private Optional<PointTransactionResult> findExistingIdempotentEntry(
            UUID merchantId, UUID customerId, String idempotencyKey, String payloadHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        Optional<StoredIdempotentEntry> existing = db.sql("""
            SELECT id, points, payload_hash, balance_after, tier_after, monetary_equivalent_pesewas
            FROM crm_loyalty_ledger
            WHERE merchant_id = ? AND customer_id = ? AND idempotency_key = ?
        """).params(merchantId, customerId, idempotencyKey)
        .query((rs, rowNum) -> new StoredIdempotentEntry(
                rs.getObject("id", UUID.class),
                rs.getLong("points"),
                rs.getString("payload_hash"),
                rs.getLong("balance_after"),
                rs.getString("tier_after"),
                rs.getLong("monetary_equivalent_pesewas")
        )).optional();

        if (existing.isEmpty()) {
            return Optional.empty();
        }
        StoredIdempotentEntry entry = existing.get();
        if (payloadHash == null || !payloadHash.equals(entry.payloadHash())) {
            throw ApiException.idempotencyConflict();
        }
        LoyaltyTier tier = entry.tierAfter() != null ? LoyaltyTier.valueOf(entry.tierAfter()) : LoyaltyTier.BRONZE;
        return Optional.of(new PointTransactionResult(
                entry.id(), entry.points(), entry.balanceAfter(), tier, entry.monetaryEquivalentPesewas()));
    }

    /**
     * Earns points for a purchase amount, factoring in tier multipliers and upgrading tiers when qualified.
     */
    @Transactional
    public PointTransactionResult earnPointsForPurchase(UUID merchantId, UUID customerId,
                                                        long spendAmountPesewas, String referenceType, UUID referenceId) {
        return earnPointsForPurchase(merchantId, customerId, spendAmountPesewas, referenceType, referenceId, null, null);
    }

    /**
     * Earns points for a purchase amount with idempotency protection.
     * Uses integer floor division (amountPesewas / 1000) so micro-purchases cannot farm points.
     */
    @Transactional
    public PointTransactionResult earnPointsForPurchase(UUID merchantId, UUID customerId,
                                                        long spendAmountPesewas, String referenceType, UUID referenceId,
                                                        String idempotencyKey, String payloadHash) {
        if (spendAmountPesewas <= 0) {
            throw new IllegalArgumentException("Spend amount must be positive");
        }

        LoyaltyAccountSummary account = getOrCreateAccount(merchantId, customerId);

        // Lock account
        var current = db.sql("""
            SELECT id, points_balance, lifetime_points_earned, lifetime_points_redeemed, current_tier
            FROM crm_loyalty_account WHERE id = ? FOR UPDATE
        """).param(account.id())
        .query((rs, rowNum) -> new LoyaltyAccountSummary(
                rs.getObject("id", UUID.class),
                merchantId,
                customerId,
                rs.getLong("points_balance"),
                rs.getLong("lifetime_points_earned"),
                rs.getLong("lifetime_points_redeemed"),
                LoyaltyTier.valueOf(rs.getString("current_tier"))
        )).single();

        Optional<PointTransactionResult> replay = findExistingIdempotentEntry(
                merchantId, customerId, idempotencyKey, payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        long basePoints = spendAmountPesewas / EARN_RATE_PESEWAS_PER_POINT;
        long earnedPoints;
        long newBalance;
        long newLifetimeEarned;
        long monetaryEquivalent;
        try {
            earnedPoints = BigInteger.valueOf(basePoints)
                    .multiply(BigInteger.valueOf(current.currentTier().getMultiplierBps()))
                    .divide(BigInteger.valueOf(100L))
                    .longValueExact();
            newBalance = Math.addExact(current.pointsBalance(), earnedPoints);
            newLifetimeEarned = Math.addExact(current.lifetimePointsEarned(), earnedPoints);
            monetaryEquivalent = Math.multiplyExact(earnedPoints, PESEWAS_PER_REDEEMED_POINT);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("amount_overflow", "Loyalty points calculation exceeds supported bounds");
        }

        LoyaltyTier newTier = LoyaltyTier.determineTier(newLifetimeEarned);

        if (earnedPoints > 0 || newTier != current.currentTier()) {
            db.sql("""
                UPDATE crm_loyalty_account
                SET points_balance = ?,
                    lifetime_points_earned = ?,
                    current_tier = ?,
                    updated_at = now()
                WHERE id = ?
            """).params(newBalance, newLifetimeEarned, newTier.name(), account.id()).update();

            // Update customer record loyalty tier
            db.sql("UPDATE crm_customer SET loyalty_tier = ? WHERE id = ?")
                    .params(newTier.name(), customerId).update();
        }

        UUID ledgerId = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_loyalty_ledger
            (id, loyalty_account_id, points, transaction_type, reference_type, reference_id, description,
             merchant_id, customer_id, idempotency_key, payload_hash, balance_after, tier_after, monetary_equivalent_pesewas)
            VALUES (?, ?, ?, 'EARNED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(ledgerId, account.id(), earnedPoints, referenceType, referenceId,
                "Earned " + earnedPoints + " points (Tier: " + newTier + ")",
                merchantId, customerId, idempotencyKey, payloadHash, newBalance, newTier.name(), monetaryEquivalent)
        .update();

        return new PointTransactionResult(ledgerId, earnedPoints, newBalance, newTier, monetaryEquivalent);
    }

    /**
     * Redeems points for an order discount.
     */
    @Transactional
    public PointTransactionResult redeemPoints(UUID merchantId, UUID customerId, long pointsToRedeem,
                                               String referenceType, UUID referenceId) {
        return redeemPoints(merchantId, customerId, pointsToRedeem, referenceType, referenceId, null, null);
    }

    /**
     * Redeems points for an order discount with idempotency protection.
     */
    @Transactional
    public PointTransactionResult redeemPoints(UUID merchantId, UUID customerId, long pointsToRedeem,
                                               String referenceType, UUID referenceId,
                                               String idempotencyKey, String payloadHash) {
        if (pointsToRedeem <= 0) {
            throw new IllegalArgumentException("Points to redeem must be greater than zero");
        }

        LoyaltyAccountSummary account = getOrCreateAccount(merchantId, customerId);

        var current = db.sql("""
            SELECT id, points_balance, lifetime_points_earned, lifetime_points_redeemed, current_tier
            FROM crm_loyalty_account WHERE id = ? FOR UPDATE
        """).param(account.id())
        .query((rs, rowNum) -> new LoyaltyAccountSummary(
                rs.getObject("id", UUID.class),
                merchantId,
                customerId,
                rs.getLong("points_balance"),
                rs.getLong("lifetime_points_earned"),
                rs.getLong("lifetime_points_redeemed"),
                LoyaltyTier.valueOf(rs.getString("current_tier"))
        )).single();

        Optional<PointTransactionResult> replay = findExistingIdempotentEntry(
                merchantId, customerId, idempotencyKey, payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        if (current.pointsBalance() < pointsToRedeem) {
            throw new IllegalStateException("Insufficient points balance: requested " + pointsToRedeem +
                    ", available " + current.pointsBalance());
        }

        long newBalance = current.pointsBalance() - pointsToRedeem;
        long newLifetimeRedeemed;
        long discountPesewas;
        try {
            newLifetimeRedeemed = Math.addExact(current.lifetimePointsRedeemed(), pointsToRedeem);
            discountPesewas = Math.multiplyExact(pointsToRedeem, PESEWAS_PER_REDEEMED_POINT);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("amount_overflow", "Redemption calculation exceeds supported bounds");
        }

        db.sql("""
            UPDATE crm_loyalty_account
            SET points_balance = ?,
                lifetime_points_redeemed = ?,
                updated_at = now()
            WHERE id = ?
        """).params(newBalance, newLifetimeRedeemed, account.id()).update();

        UUID ledgerId = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_loyalty_ledger
            (id, loyalty_account_id, points, transaction_type, reference_type, reference_id, description,
             merchant_id, customer_id, idempotency_key, payload_hash, balance_after, tier_after, monetary_equivalent_pesewas)
            VALUES (?, ?, ?, 'REDEEMED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(ledgerId, account.id(), -pointsToRedeem, referenceType, referenceId,
                "Redeemed " + pointsToRedeem + " points for discount",
                merchantId, customerId, idempotencyKey, payloadHash, newBalance, current.currentTier().name(), discountPesewas)
        .update();

        return new PointTransactionResult(ledgerId, -pointsToRedeem, newBalance, current.currentTier(), discountPesewas);
    }
}
