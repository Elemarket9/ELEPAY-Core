package com.elepay.crm;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 7: Dynamic Coupon Code & Promotion Discount Engine.
 */
@Service
public class CouponEngineService {

    public enum DiscountType {
        PERCENTAGE,
        FIXED_AMOUNT
    }

    private final JdbcClient db;

    public CouponEngineService(JdbcClient db) {
        this.db = db;
    }

    public record CouponDefinition(
            UUID id,
            UUID merchantId,
            String code,
            DiscountType discountType,
            long discountValue,
            long minOrderPesewas,
            long maxDiscountPesewas,
            int usageLimit,
            int timesUsed,
            int perCustomerLimit,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            boolean isActive
    ) {}

    public record CouponApplicationResult(
            boolean isValid,
            long discountPesewas,
            long finalOrderAmountPesewas,
            String message
    ) {}

    /**
     * Creates a new dynamic coupon rule.
     */
    @Transactional
    public CouponDefinition createCoupon(UUID merchantId, String code, DiscountType type,
                                         long discountValue, long minOrderPesewas,
                                         long maxDiscountPesewas, int usageLimit,
                                         OffsetDateTime startsAt, OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_coupon
            (id, merchant_id, code, discount_type, discount_value, min_order_pesewas,
             max_discount_pesewas, usage_limit, times_used, per_customer_limit, starts_at, expires_at, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 1, ?, ?, true)
        """).params(id, merchantId, code.toUpperCase(), type.name(), discountValue,
                minOrderPesewas, maxDiscountPesewas, usageLimit, startsAt, expiresAt)
        .update();

        return new CouponDefinition(id, merchantId, code.toUpperCase(), type, discountValue,
                minOrderPesewas, maxDiscountPesewas, usageLimit, 0, 1, startsAt, expiresAt, true);
    }

    /**
     * Validates and applies a coupon code to an order amount, decrementing available usage.
     */
    @Transactional
    public CouponApplicationResult applyCoupon(UUID merchantId, String code, long orderAmountPesewas) {
        var coupon = db.sql("""
            SELECT id, discount_type, discount_value, min_order_pesewas, max_discount_pesewas,
                   usage_limit, times_used, starts_at, expires_at, is_active
            FROM crm_coupon
            WHERE merchant_id = ? AND code = ? FOR UPDATE
        """).params(merchantId, code.toUpperCase())
        .query((rs, rowNum) -> new CouponDefinition(
                rs.getObject("id", UUID.class),
                merchantId,
                code.toUpperCase(),
                DiscountType.valueOf(rs.getString("discount_type")),
                rs.getLong("discount_value"),
                rs.getLong("min_order_pesewas"),
                rs.getLong("max_discount_pesewas"),
                rs.getInt("usage_limit"),
                rs.getInt("times_used"),
                1,
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getBoolean("is_active")
        )).optional();

        if (coupon.isEmpty()) {
            return new CouponApplicationResult(false, 0L, orderAmountPesewas, "Invalid coupon code");
        }

        var c = coupon.get();
        if (!c.isActive()) {
            return new CouponApplicationResult(false, 0L, orderAmountPesewas, "Coupon is no longer active");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (now.isBefore(c.startsAt()) || now.isAfter(c.expiresAt())) {
            return new CouponApplicationResult(false, 0L, orderAmountPesewas, "Coupon is expired or not yet valid");
        }

        if (orderAmountPesewas < c.minOrderPesewas()) {
            return new CouponApplicationResult(false, 0L, orderAmountPesewas,
                    "Order amount does not meet minimum order requirement of " + c.minOrderPesewas() + " pesewas");
        }

        if (c.usageLimit() > 0 && c.timesUsed() >= c.usageLimit()) {
            return new CouponApplicationResult(false, 0L, orderAmountPesewas, "Coupon usage limit reached");
        }

        long calculatedDiscount;
        if (c.discountType() == DiscountType.PERCENTAGE) {
            calculatedDiscount = (orderAmountPesewas * c.discountValue()) / 10000;
            if (c.maxDiscountPesewas() > 0) {
                calculatedDiscount = Math.min(calculatedDiscount, c.maxDiscountPesewas());
            }
        } else {
            calculatedDiscount = Math.min(orderAmountPesewas, c.discountValue());
        }

        long finalAmount = Math.max(0, orderAmountPesewas - calculatedDiscount);

        db.sql("UPDATE crm_coupon SET times_used = times_used + 1 WHERE id = ?").param(c.id()).update();

        return new CouponApplicationResult(true, calculatedDiscount, finalAmount, "Coupon applied successfully");
    }
}
