package com.elepay.merchanttools.crm;

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
    public static final long PESEWAS_PER_REDEEMED_POINT = 10;  // 1 point = 10 pesewas (GHS 0.10)

    private final JdbcClient db;

    public record Rules(UUID merchantId, int pointsPer1000Pesewas,
                        long pesewasPerRedeemedPoint, boolean enabled) {}

    /** A missing row retains historical program behavior for existing merchants. */
    public Rules rules(UUID merchantId) {
        return db.sql("""
                SELECT merchant_id, points_per_1000_pesewas, pesewas_per_redeemed_point, enabled
                FROM crm_loyalty_rules WHERE merchant_id = :merchant
                """).param("merchant", merchantId)
                .query(Rules.class).optional()
                .orElse(new Rules(merchantId, BASE_POINTS_PER_1000_PESEWAS,
                        PESEWAS_PER_REDEEMED_POINT, true));
    }

    @Transactional
    public Rules updateRules(UUID merchantId, int pointsPer1000, long pesewasPerPoint, boolean enabled) {
        if (pointsPer1000 < 0 || pointsPer1000 > 1000 || pesewasPerPoint < 0 || pesewasPerPoint > 100000)
            throw com.elepay.common.ApiException.badRequest("loyalty_rules_invalid",
                    "Loyalty earning and redemption rates outside allowed bounds");
        db.sql("""
                INSERT INTO crm_loyalty_rules (merchant_id, points_per_1000_pesewas,
                    pesewas_per_redeemed_point, enabled, updated_at)
                VALUES (:merchant, :points, :value, :enabled, now())
                ON CONFLICT (merchant_id) DO UPDATE SET
                    points_per_1000_pesewas = excluded.points_per_1000_pesewas,
                    pesewas_per_redeemed_point = excluded.pesewas_per_redeemed_point,
                    enabled = excluded.enabled, updated_at = now()
                """).param("merchant", merchantId).param("points", pointsPer1000)
                .param("value", pesewasPerPoint).param("enabled", enabled).update();
        return rules(merchantId);
    }

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

    /**
     * Initializes or retrieves the loyalty account for a merchant customer.
     */
    @Transactional
    public LoyaltyAccountSummary getOrCreateAccount(UUID merchantId, UUID customerId) {
        var existing = db.sql("""
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
        )).optional();

        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_loyalty_account
            (id, merchant_id, customer_id, points_balance, lifetime_points_earned, lifetime_points_redeemed, current_tier)
            VALUES (?, ?, ?, 0, 0, 0, 'BRONZE')
        """).params(id, merchantId, customerId).update();

        return new LoyaltyAccountSummary(id, merchantId, customerId, 0L, 0L, 0L, LoyaltyTier.BRONZE);
    }

    /**
     * Earns points for a purchase amount, factoring in tier multipliers and upgrading tiers when qualified.
     */
    @Transactional
    public PointTransactionResult earnPointsForPurchase(UUID merchantId, UUID customerId,
                                                        long spendAmountPesewas, String referenceType, UUID referenceId) {
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

        Rules plan = rules(merchantId);
        if (!plan.enabled())
            throw com.elepay.common.ApiException.conflict("loyalty_disabled", "Loyalty is disabled for this merchant");
        if (spendAmountPesewas <= 0)
            throw com.elepay.common.ApiException.badRequest("loyalty_purchase_invalid", "Purchase must be positive");
        long basePoints = Math.multiplyExact(spendAmountPesewas / 1000, plan.pointsPer1000Pesewas());
        long earnedPoints = Math.multiplyExact(basePoints, current.currentTier().getMultiplierBps()) / 100;
        if (earnedPoints <= 0 && plan.pointsPer1000Pesewas() > 0) earnedPoints = 1;

        long newBalance = current.pointsBalance() + earnedPoints;
        long newLifetimeEarned = current.lifetimePointsEarned() + earnedPoints;
        LoyaltyTier newTier = LoyaltyTier.determineTier(newLifetimeEarned);

        db.sql("""
            UPDATE crm_loyalty_account
            SET points_balance = ?,
                lifetime_points_earned = ?,
                current_tier = ?,
                updated_at = now()
            WHERE id = ?
        """).params(newBalance, newLifetimeEarned, newTier.name(), account.id()).update();

        UUID ledgerId = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_loyalty_ledger
            (id, loyalty_account_id, points, transaction_type, reference_type, reference_id, description)
            VALUES (?, ?, ?, 'EARNED', ?, ?, ?)
        """).params(ledgerId, account.id(), earnedPoints, referenceType, referenceId,
                "Earned " + earnedPoints + " points (Tier: " + newTier + ")")
        .update();

        // Update customer record loyalty tier
        db.sql("UPDATE crm_customer SET loyalty_tier = ? WHERE id = ?")
                .params(newTier.name(), customerId).update();

        return new PointTransactionResult(ledgerId, earnedPoints, newBalance, newTier, Math.multiplyExact(earnedPoints, plan.pesewasPerRedeemedPoint()));
    }

    /**
     * Redeems points for an order discount.
     */
    @Transactional
    public PointTransactionResult redeemPoints(UUID merchantId, UUID customerId, long pointsToRedeem,
                                               String referenceType, UUID referenceId) {
        if (pointsToRedeem <= 0)
            throw new IllegalArgumentException("Points to redeem must be greater than zero");
        Rules plan = rules(merchantId);
        if (!plan.enabled())
            throw com.elepay.common.ApiException.conflict("loyalty_disabled", "Loyalty is disabled for this merchant");

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

        if (current.pointsBalance() < pointsToRedeem) {
            throw new IllegalStateException("Insufficient points balance: requested " + pointsToRedeem +
                    ", available " + current.pointsBalance());
        }

        long newBalance = current.pointsBalance() - pointsToRedeem;
        long newLifetimeRedeemed = current.lifetimePointsRedeemed() + pointsToRedeem;

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
            (id, loyalty_account_id, points, transaction_type, reference_type, reference_id, description)
            VALUES (?, ?, ?, 'REDEEMED', ?, ?, ?)
        """).params(ledgerId, account.id(), -pointsToRedeem, referenceType, referenceId,
                "Redeemed " + pointsToRedeem + " points for discount")
        .update();

        long discountPesewas = Math.multiplyExact(pointsToRedeem, plan.pesewasPerRedeemedPoint());
        return new PointTransactionResult(ledgerId, -pointsToRedeem, newBalance, current.currentTier(), discountPesewas);
    }
}
