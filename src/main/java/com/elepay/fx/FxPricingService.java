package com.elepay.fx;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm #060: Dynamic FX margin pricing.
 *
 * <p>Calculates dynamic tiered margins based on transaction amount, corridor, and
 * customer classification (standard, VIP, enterprise). Produces locked transparent
 * quotes with TTL and explicit rounding in integer minor units.
 */
@Service
public class FxPricingService {
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);

    private final JdbcClient db;

    public FxPricingService(JdbcClient db) {
        this.db = db;
    }

    public record MarginTier(UUID id, String corridor, String customerTier, long minAmount, long maxAmount,
                             int marginBps, long fixedFee, boolean active, int version) {}

    public record PricingResult(BigDecimal baseRate, BigDecimal marginRate, BigDecimal effectiveRate,
                                int marginBps, long fixedFee, long variableFee, long totalFee,
                                long sourceAmount, long destinationAmount) {}

    private int exponent(String currency) {
        return db.sql("SELECT exponent FROM currency WHERE code = :c").param("c", currency)
                .query(Integer.class).optional()
                .orElseThrow(() -> ApiException.badRequest("unknown_currency", "Unsupported currency " + currency));
    }

    /**
     * Resolves the best matching margin tier for a corridor, customer tier, and volume.
     */
    public MarginTier resolveMarginTier(String corridorCode, String customerTier, long sourceAmount) {
        String tier = (customerTier != null && !customerTier.isBlank()) ? customerTier : "standard";
        return db.sql("""
                SELECT id, corridor, customer_tier, min_amount, max_amount, margin_bps, fixed_fee, active, version
                FROM fx_margin_tier
                WHERE (corridor = :c OR corridor IS NULL)
                  AND customer_tier = :tier
                  AND min_amount <= :amt AND max_amount >= :amt
                  AND active = true
                ORDER BY corridor NULLS LAST, min_amount DESC
                LIMIT 1""")
                .param("c", corridorCode).param("tier", tier).param("amt", sourceAmount)
                .query((rs, i) -> new MarginTier(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getInt(6), rs.getLong(7), rs.getBoolean(8), rs.getInt(9)))
                .optional().orElse(null);
    }

    /**
     * Calculates transparent pricing including dynamic margin and fees.
     */
    public PricingResult calculatePricing(String corridorCode, String customerTier, String sourceCurrency,
                                         String destinationCurrency, long sourceAmount, BigDecimal baseRate,
                                         long defaultFixedFee, int defaultFeeBps) {
        if (sourceAmount <= 0) throw ApiException.badRequest("invalid_amount", "Source amount must be positive");
        if (baseRate == null || baseRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("invalid_rate", "Base rate must be positive");
        }

        MarginTier tier = resolveMarginTier(corridorCode, customerTier, sourceAmount);
        int marginBps = (tier != null) ? tier.marginBps() : defaultFeeBps;
        if (marginBps < 0 || marginBps >= 10_000) {
            throw ApiException.badRequest("invalid_margin", "Margin basis points must be between 0 and 9999");
        }
        long fixedFee = (tier != null && tier.fixedFee() > 0) ? tier.fixedFee() : defaultFixedFee;
        if (fixedFee < 0) {
            throw ApiException.badRequest("invalid_fee", "Fixed fee must be non-negative");
        }
        // Margin is applied once transparently in the FX spread (effectiveRate), not double-charged as a variable fee.
        long variableFee = 0L;
        long totalFee = fixedFee;

        // Effective client rate applies a margin deduction on outbound conversion
        BigDecimal marginFactor = BigDecimal.valueOf(10_000 - marginBps).divide(BigDecimal.valueOf(10_000), MC);
        BigDecimal effectiveRate = baseRate.multiply(marginFactor, MC);

        // Convert minor units with deterministic rounding down in customer's disfavour
        int srcExp = exponent(sourceCurrency);
        int dstExp = exponent(destinationCurrency);
        BigDecimal converted = BigDecimal.valueOf(sourceAmount)
                .multiply(effectiveRate)
                .movePointRight(dstExp - srcExp);
        if (converted.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw ApiException.badRequest("amount_overflow",
                    "Converted destination amount exceeds maximum supported value");
        }
        long destinationAmount;
        try {
            destinationAmount = converted
                    .setScale(0, RoundingMode.DOWN)
                    .longValueExact();
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("amount_overflow",
                    "Converted destination amount exceeds maximum supported value");
        }

        if (destinationAmount <= 0) {
            throw ApiException.badRequest("amount_too_small", "Amount is too small to convert to destination currency");
        }

        return new PricingResult(baseRate, effectiveRate, effectiveRate, marginBps, fixedFee,
                variableFee, totalFee, sourceAmount, destinationAmount);
    }
}
