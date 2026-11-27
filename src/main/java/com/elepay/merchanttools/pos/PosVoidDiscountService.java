package com.elepay.merchanttools.pos;

import com.elepay.checkout.PosCheckoutGateway;
import com.elepay.common.ApiException;
import com.elepay.merchant.MerchantAccess;
import com.elepay.merchanttools.restaurant.KdsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosVoidDiscountService {
    private static final Set<String> MODIFIABLE_STATUSES = Set.of("PENDING", "PARTIAL");

    private final JdbcClient db;
    private final PosOrderService orderService;
    private final PosCashierService cashierService;
    private final KdsService kdsService;
    private final PosCheckoutGateway checkout;

    public PosVoidDiscountService(
            JdbcClient db,
            PosOrderService orderService,
            PosCashierService cashierService,
            KdsService kdsService,
            PosCheckoutGateway checkout
    ) {
        this.db = db;
        this.orderService = orderService;
        this.cashierService = cashierService;
        this.kdsService = kdsService;
        this.checkout = checkout;
    }

    public record MerchantPosSettings(
            UUID merchantId,
            long discountPinThresholdPesewas,
            Integer discountPinThresholdBps,
            OffsetDateTime updatedAt
    ) {}

    public record VoidLogEntry(
            UUID id,
            UUID merchantId,
            UUID branchId,
            UUID orderId,
            UUID orderItemId,
            String voidScope,
            String itemName,
            BigDecimal quantity,
            long amountPesewas,
            boolean wasFired,
            String reason,
            String voidedBy,
            UUID approvedBy,
            String approvedByName,
            OffsetDateTime createdAt
    ) {}

    public record DiscountLogEntry(
            UUID id,
            UUID merchantId,
            UUID branchId,
            UUID orderId,
            UUID orderItemId,
            long discountPesewas,
            String reason,
            String appliedBy,
            UUID approvedBy,
            String approvedByName,
            OffsetDateTime createdAt
    ) {}

    private record CheckoutLinkRow(
            UUID id,
            UUID orderId,
            String checkoutRef,
            long amount,
            String status,
            OffsetDateTime refundedAt
    ) {}

    private record TenderRow(
            UUID id,
            String tenderType,
            long amountPesewas,
            String status
    ) {}

    public MerchantPosSettings getSettings(UUID merchantId) {
        return db.sql("""
                SELECT merchant_id, discount_pin_threshold_pesewas, discount_pin_threshold_bps, updated_at
                FROM pos_merchant_settings
                WHERE merchant_id = :m
                """)
                .param("m", merchantId)
                .query(MerchantPosSettings.class)
                .optional()
                .orElse(new MerchantPosSettings(merchantId, 0L, null, null));
    }

    @Transactional
    public MerchantPosSettings updateSettings(UUID merchantId, long discountPinThresholdPesewas, Integer discountPinThresholdBps) {
        if (discountPinThresholdPesewas < 0) {
            throw ApiException.badRequest("invalid_threshold", "Discount PIN threshold cannot be negative");
        }
        if (discountPinThresholdBps != null && (discountPinThresholdBps < 0 || discountPinThresholdBps > 10000)) {
            throw ApiException.badRequest("invalid_threshold_bps", "Discount PIN threshold bps must be between 0 and 10000");
        }
        db.sql("""
                INSERT INTO pos_merchant_settings (
                    merchant_id, discount_pin_threshold_pesewas, discount_pin_threshold_bps, updated_at
                ) VALUES (:m, :pesewas, :bps, now())
                ON CONFLICT (merchant_id) DO UPDATE SET
                    discount_pin_threshold_pesewas = EXCLUDED.discount_pin_threshold_pesewas,
                    discount_pin_threshold_bps = EXCLUDED.discount_pin_threshold_bps,
                    updated_at = now()
                """)
                .param("m", merchantId)
                .param("pesewas", discountPinThresholdPesewas)
                .param("bps", discountPinThresholdBps)
                .update();
        return getSettings(merchantId);
    }

    private PosOrderService.PosOrder lockOrder(UUID orderId) {
        db.sql("SELECT id FROM pos_order WHERE id = :id FOR UPDATE")
                .param("id", orderId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        return orderService.getOrder(orderId).orElseThrow();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason_required", "A reason is required");
        }
        return reason.trim();
    }

    private static boolean requiresManagerPin(MerchantPosSettings settings, long discountPesewas, long baseAmount) {
        if (discountPesewas > settings.discountPinThresholdPesewas()) {
            return true;
        }
        if (settings.discountPinThresholdBps() != null && baseAmount > 0) {
            return Math.multiplyExact(discountPesewas, 10000L)
                    > Math.multiplyExact(baseAmount, settings.discountPinThresholdBps().longValue());
        }
        return false;
    }

    @Transactional
    public PosOrderService.PosOrder voidOrderItem(
            UUID orderId,
            UUID itemId,
            String reason,
            String managerBadgeNumber,
            String managerPin,
            String actor
    ) {
        String trimmedReason = requireReason(reason);
        PosOrderService.PosOrder order = lockOrder(orderId);
        if (!MODIFIABLE_STATUSES.contains(order.status())) {
            throw ApiException.conflict("order_not_modifiable",
                    "Order is " + order.status() + " and items cannot be voided");
        }
        PosCashierService.Cashier manager = cashierService.verifyManagerPin(
                order.merchantId(), managerBadgeNumber, managerPin);

        PosOrderService.PosOrderItem item = db.sql("""
                SELECT id, order_id, variant_id, item_name, quantity, unit_price_pesewas,
                       discount_pesewas, tax_pesewas, total_pesewas, modifiers_json,
                       seat_number, course, notes, kds_round, sent_at, voided
                FROM pos_order_item
                WHERE id = :itemId AND order_id = :orderId
                FOR UPDATE
                """)
                .param("itemId", itemId)
                .param("orderId", orderId)
                .query(PosOrderService.PosOrderItem.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Order item not found"));

        if (item.voided()) {
            return order;
        }

        long newSubtotal = db.sql("""
                SELECT COALESCE(SUM(total_pesewas), 0)
                FROM pos_order_item
                WHERE order_id = :orderId AND NOT voided AND id <> :itemId
                """)
                .param("orderId", orderId)
                .param("itemId", itemId)
                .query(Long.class)
                .single();
        long effectiveDiscount = Math.min(order.discountPesewas(), newSubtotal);
        long newTotal = Math.addExact(
                Math.addExact(Math.subtractExact(newSubtotal, effectiveDiscount), order.taxPesewas()),
                order.tipPesewas());

        if (order.paidPesewas() > newTotal) {
            throw ApiException.conflict("void_below_paid_amount",
                    "Cannot void item because paid amount exceeds remaining order total");
        }

        db.sql("""
                UPDATE pos_order_item
                SET voided = true, voided_at = now()
                WHERE id = :itemId
                """)
                .param("itemId", itemId)
                .update();

        db.sql("""
                UPDATE pos_order
                SET subtotal_pesewas = :subtotal, discount_pesewas = :discount, total_pesewas = :total
                WHERE id = :orderId
                """)
                .param("subtotal", newSubtotal)
                .param("discount", effectiveDiscount)
                .param("total", newTotal)
                .param("orderId", orderId)
                .update();

        boolean hasKdsItem = db.sql("""
                SELECT count(*) FROM restaurant_kds_item
                WHERE order_item_id = :itemId AND item_status <> 'CANCELLED'
                """).param("itemId", itemId).query(Long.class).single() > 0;
        boolean wasFired = item.sentAt() != null || hasKdsItem;

        db.sql("""
                INSERT INTO pos_void_log (
                    id, merchant_id, branch_id, order_id, order_item_id, void_scope,
                    item_name, quantity, amount_pesewas, was_fired, reason,
                    voided_by, approved_by, approved_by_name, created_at
                ) VALUES (
                    :id, :merchantId, :branchId, :orderId, :orderItemId, 'ITEM',
                    :itemName, :quantity, :amount, :wasFired, :reason,
                    :voidedBy, :approvedBy, :approvedByName, now()
                )
                """)
                .param("id", UUID.randomUUID())
                .param("merchantId", order.merchantId())
                .param("branchId", order.branchId())
                .param("orderId", orderId)
                .param("orderItemId", itemId)
                .param("itemName", item.itemName())
                .param("quantity", item.quantity())
                .param("amount", item.totalPesewas())
                .param("wasFired", wasFired)
                .param("reason", trimmedReason)
                .param("voidedBy", actor == null || actor.isBlank() ? "system" : actor)
                .param("approvedBy", manager.id())
                .param("approvedByName", manager.name())
                .update();

        if (wasFired) {
            kdsService.cancelOrderItem(order.merchantId(), itemId, "void-item:" + itemId, trimmedReason);
        }

        return orderService.getOrder(orderId).orElseThrow();
    }

    @Transactional
    public PosOrderService.PosOrder voidOrderWithControl(
            UUID orderId,
            String reason,
            String managerBadgeNumber,
            String managerPin,
            boolean refundPayments,
            MerchantAccess.Access caller,
            String actor
    ) {
        String trimmedReason = requireReason(reason);
        PosOrderService.PosOrder order = lockOrder(orderId);
        PosCashierService.Cashier manager = cashierService.verifyManagerPin(
                order.merchantId(), managerBadgeNumber, managerPin);

        if ("VOIDED".equals(order.status())) {
            return order;
        }
        if ("PAID".equals(order.status())) {
            throw ApiException.conflict("order_cannot_be_voided", "Fully paid orders cannot be voided");
        }

        List<CheckoutLinkRow> links = db.sql("""
                SELECT id, order_id, checkout_ref, amount, status, refunded_at
                FROM pos_checkout_link
                WHERE order_id = :id AND status IN ('open', 'applied')
                FOR UPDATE
                """)
                .param("id", orderId)
                .query(CheckoutLinkRow.class)
                .list();

        boolean isPartial = "PARTIAL".equals(order.status()) || order.paidPesewas() > 0;
        if (isPartial) {
            if (!refundPayments) {
                throw ApiException.conflict("partial_order_requires_refund",
                        "Partially paid order cannot be voided without refunding existing payments");
            }
            List<TenderRow> payments = db.sql("""
                    SELECT id, tender_type, amount_pesewas, status
                    FROM pos_tender_payment
                    WHERE order_id = :id AND status = 'SUCCESS'
                    FOR UPDATE
                    """)
                    .param("id", orderId)
                    .query(TenderRow.class)
                    .list();
            for (TenderRow payment : payments) {
                db.sql("UPDATE pos_tender_payment SET status = 'REFUNDED' WHERE id = :id")
                        .param("id", payment.id())
                        .update();
                if ("CASH".equals(payment.tenderType()) && order.terminalId() != null) {
                    cashierService.getActiveShift(order.terminalId())
                            .ifPresent(shift -> cashierService.recordCashTender(shift.id(), -payment.amountPesewas()));
                }
            }
            for (CheckoutLinkRow l : links) {
                if ("applied".equals(l.status()) && l.refundedAt() == null) {
                    if (caller == null) {
                        throw ApiException.forbidden("Authenticated caller required to refund digital checkout");
                    }
                    checkout.refund(caller, l.checkoutRef(), l.amount(), trimmedReason,
                            "void-order:" + orderId + ":" + l.id());
                    db.sql("UPDATE pos_checkout_link SET refunded_at = now() WHERE id = :id")
                            .param("id", l.id())
                            .update();
                }
            }
        } else {
            for (CheckoutLinkRow l : links) {
                if ("applied".equals(l.status())) {
                    throw ApiException.conflict("partial_order_requires_refund",
                            "Order has an applied digital payment and requires refundPayments=true to void");
                }
            }
        }

        for (CheckoutLinkRow l : links) {
            if ("open".equals(l.status())) {
                checkout.cancel(order.merchantId(), order.branchId(), l.checkoutRef(), actor);
                db.sql("UPDATE pos_checkout_link SET status = 'cancelled' WHERE id = :id")
                        .param("id", l.id())
                        .update();
            }
        }

        db.sql("""
                UPDATE pos_order
                SET status = 'VOIDED',
                    paid_pesewas = CASE WHEN :refunded THEN 0 ELSE paid_pesewas END,
                    change_pesewas = CASE WHEN :refunded THEN 0 ELSE change_pesewas END
                WHERE id = :id
                """)
                .param("refunded", isPartial)
                .param("id", orderId)
                .update();

        List<UUID> openTicketIds = db.sql("""
                SELECT id FROM restaurant_kds_ticket
                WHERE merchant_id = :m AND order_id = :orderId
                  AND status IN ('NEW', 'PREPARING', 'READY')
                ORDER BY created_at ASC, id ASC
                FOR UPDATE
                """)
                .param("m", order.merchantId())
                .param("orderId", orderId)
                .query(UUID.class)
                .list();
        for (UUID ticketId : openTicketIds) {
            kdsService.cancel(ticketId, "void:" + ticketId, trimmedReason);
        }

        boolean wasFired = db.sql("""
                SELECT count(*) FROM restaurant_kds_ticket
                WHERE merchant_id = :m AND order_id = :orderId
                """).param("m", order.merchantId()).param("orderId", orderId).query(Long.class).single() > 0;

        db.sql("""
                INSERT INTO pos_void_log (
                    id, merchant_id, branch_id, order_id, order_item_id, void_scope,
                    item_name, quantity, amount_pesewas, was_fired, reason,
                    voided_by, approved_by, approved_by_name, created_at
                ) VALUES (
                    :id, :merchantId, :branchId, :orderId, NULL, 'ORDER',
                    NULL, NULL, :amount, :wasFired, :reason,
                    :voidedBy, :approvedBy, :approvedByName, now()
                )
                """)
                .param("id", UUID.randomUUID())
                .param("merchantId", order.merchantId())
                .param("branchId", order.branchId())
                .param("orderId", orderId)
                .param("amount", order.totalPesewas())
                .param("wasFired", wasFired)
                .param("reason", trimmedReason)
                .param("voidedBy", actor == null || actor.isBlank() ? "system" : actor)
                .param("approvedBy", manager.id())
                .param("approvedByName", manager.name())
                .update();

        return orderService.getOrder(orderId).orElseThrow();
    }

    @Transactional
    public PosOrderService.PosOrder applyDiscount(
            UUID orderId,
            UUID orderItemId,
            long discountPesewas,
            String reason,
            String managerBadgeNumber,
            String managerPin,
            String actor
    ) {
        if (discountPesewas <= 0) {
            throw ApiException.badRequest("invalid_discount", "Discount amount must be positive");
        }
        String trimmedReason = requireReason(reason);
        PosOrderService.PosOrder order = lockOrder(orderId);
        if (!MODIFIABLE_STATUSES.contains(order.status())) {
            throw ApiException.conflict("order_not_modifiable",
                    "Order is " + order.status() + " and cannot accept discounts");
        }

        MerchantPosSettings settings = getSettings(order.merchantId());
        PosCashierService.Cashier manager = null;

        long existingItemDiscounts = db.sql("""
                SELECT COALESCE(SUM(discount_pesewas), 0)
                FROM pos_order_item
                WHERE order_id = :orderId AND NOT voided
                """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        long orderGrossBase = Math.addExact(order.subtotalPesewas(), existingItemDiscounts);
        long orderWideCumulativeDiscount = Math.addExact(
                Math.addExact(order.discountPesewas(), existingItemDiscounts),
                discountPesewas);

        if (orderItemId != null) {
            PosOrderService.PosOrderItem item = db.sql("""
                    SELECT id, order_id, variant_id, item_name, quantity, unit_price_pesewas,
                           discount_pesewas, tax_pesewas, total_pesewas, modifiers_json,
                           seat_number, course, notes, kds_round, sent_at, voided
                    FROM pos_order_item
                    WHERE id = :itemId AND order_id = :orderId AND NOT voided
                    FOR UPDATE
                    """)
                    .param("itemId", orderItemId)
                    .param("orderId", orderId)
                    .query(PosOrderService.PosOrderItem.class)
                    .optional()
                    .orElseThrow(() -> ApiException.notFound("Order item not found"));

            long gross = item.quantity().multiply(BigDecimal.valueOf(item.unitPricePesewas()))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            long newItemDiscount = Math.addExact(item.discountPesewas(), discountPesewas);
            if (newItemDiscount > gross) {
                throw ApiException.badRequest("discount_exceeds_item_total", "Item discount exceeds item gross amount");
            }
            if (requiresManagerPin(settings, newItemDiscount, gross)
                    || requiresManagerPin(settings, orderWideCumulativeDiscount, orderGrossBase)
                    || (managerPin != null && !managerPin.isBlank())) {
                manager = cashierService.verifyManagerPin(order.merchantId(), managerBadgeNumber, managerPin);
            }

            long newItemTotal = Math.addExact(Math.subtractExact(gross, newItemDiscount), item.taxPesewas());
            long newSubtotal = Math.addExact(Math.subtractExact(order.subtotalPesewas(), item.totalPesewas()), newItemTotal);
            if (order.discountPesewas() > newSubtotal) {
                throw ApiException.conflict("discount_exceeds_subtotal", "Order discount exceeds updated subtotal");
            }
            long newOrderTotal = Math.addExact(
                    Math.addExact(Math.subtractExact(newSubtotal, order.discountPesewas()), order.taxPesewas()),
                    order.tipPesewas());
            if (newOrderTotal < order.paidPesewas()) {
                throw ApiException.conflict("discount_below_paid_amount",
                        "Discount cannot reduce total below already paid amount");
            }

            db.sql("""
                    UPDATE pos_order_item
                    SET discount_pesewas = :itemDiscount, total_pesewas = :itemTotal
                    WHERE id = :itemId
                    """)
                    .param("itemDiscount", newItemDiscount)
                    .param("itemTotal", newItemTotal)
                    .param("itemId", orderItemId)
                    .update();

            db.sql("""
                    UPDATE pos_order
                    SET subtotal_pesewas = :subtotal, total_pesewas = :total
                    WHERE id = :orderId
                    """)
                    .param("subtotal", newSubtotal)
                    .param("total", newOrderTotal)
                    .param("orderId", orderId)
                    .update();
        } else {
            long newOrderDiscount = Math.addExact(order.discountPesewas(), discountPesewas);
            if (newOrderDiscount > order.subtotalPesewas()) {
                throw ApiException.badRequest("discount_exceeds_subtotal", "Order discount exceeds subtotal");
            }
            if (requiresManagerPin(settings, orderWideCumulativeDiscount, orderGrossBase)
                    || (managerPin != null && !managerPin.isBlank())) {
                manager = cashierService.verifyManagerPin(order.merchantId(), managerBadgeNumber, managerPin);
            }
            long newOrderTotal = Math.addExact(
                    Math.addExact(Math.subtractExact(order.subtotalPesewas(), newOrderDiscount), order.taxPesewas()),
                    order.tipPesewas());
            if (newOrderTotal < order.paidPesewas()) {
                throw ApiException.conflict("discount_below_paid_amount",
                        "Discount cannot reduce total below already paid amount");
            }

            db.sql("""
                    UPDATE pos_order
                    SET discount_pesewas = :discount, total_pesewas = :total
                    WHERE id = :orderId
                    """)
                    .param("discount", newOrderDiscount)
                    .param("total", newOrderTotal)
                    .param("orderId", orderId)
                    .update();
        }

        insertDiscountLog(order.merchantId(), order.branchId(), orderId, orderItemId,
                discountPesewas, trimmedReason, actor, manager);
        return orderService.getOrder(orderId).orElseThrow();
    }

    @Transactional
    public void validateAndLogInitialDiscounts(
            PosOrderService.PosOrder order,
            long orderDiscountPesewas,
            List<PosOrderService.OrderItemInput> items,
            String discountReason,
            String managerBadgeNumber,
            String managerPin,
            String actor
    ) {
        long itemDiscountSum = items == null ? 0L : items.stream()
                .mapToLong(PosOrderService.OrderItemInput::discountPesewas)
                .sum();
        long totalDiscount = Math.addExact(orderDiscountPesewas, itemDiscountSum);
        if (totalDiscount <= 0) {
            return;
        }
        String trimmedReason = requireReason(discountReason);
        MerchantPosSettings settings = getSettings(order.merchantId());
        long grossBase = Math.addExact(order.subtotalPesewas(), itemDiscountSum);
        PosCashierService.Cashier manager = null;
        if (requiresManagerPin(settings, totalDiscount, grossBase)
                || (managerPin != null && !managerPin.isBlank())) {
            manager = cashierService.verifyManagerPin(order.merchantId(), managerBadgeNumber, managerPin);
        }
        if (orderDiscountPesewas > 0) {
            insertDiscountLog(order.merchantId(), order.branchId(), order.id(), null,
                    orderDiscountPesewas, trimmedReason, actor, manager);
        }
        if (itemDiscountSum > 0) {
            List<PosOrderService.PosOrderItem> persistedItems = orderService.listOrderItems(order.id());
            for (PosOrderService.PosOrderItem item : persistedItems) {
                if (item.discountPesewas() > 0) {
                    insertDiscountLog(order.merchantId(), order.branchId(), order.id(), item.id(),
                            item.discountPesewas(), trimmedReason, actor, manager);
                }
            }
        }
    }

    private void insertDiscountLog(
            UUID merchantId,
            UUID branchId,
            UUID orderId,
            UUID orderItemId,
            long discountPesewas,
            String reason,
            String actor,
            PosCashierService.Cashier manager
    ) {
        db.sql("""
                INSERT INTO pos_discount_log (
                    id, merchant_id, branch_id, order_id, order_item_id,
                    discount_pesewas, reason, applied_by, approved_by, approved_by_name, created_at
                ) VALUES (
                    :id, :merchantId, :branchId, :orderId, :orderItemId,
                    :discountPesewas, :reason, :appliedBy, :approvedBy, :approvedByName, now()
                )
                """)
                .param("id", UUID.randomUUID())
                .param("merchantId", merchantId)
                .param("branchId", branchId)
                .param("orderId", orderId)
                .param("orderItemId", orderItemId)
                .param("discountPesewas", discountPesewas)
                .param("reason", reason)
                .param("appliedBy", actor == null || actor.isBlank() ? "system" : actor)
                .param("approvedBy", manager == null ? null : manager.id())
                .param("approvedByName", manager == null ? null : manager.name())
                .update();
    }

    public List<VoidLogEntry> listVoids(UUID orderId) {
        return db.sql("""
                SELECT id, merchant_id, branch_id, order_id, order_item_id, void_scope,
                       item_name, quantity, amount_pesewas, was_fired, reason,
                       voided_by, approved_by, approved_by_name, created_at
                FROM pos_void_log
                WHERE order_id = :orderId
                ORDER BY created_at DESC
                """)
                .param("orderId", orderId)
                .query(VoidLogEntry.class)
                .list();
    }

    public List<DiscountLogEntry> listDiscounts(UUID orderId) {
        return db.sql("""
                SELECT id, merchant_id, branch_id, order_id, order_item_id,
                       discount_pesewas, reason, applied_by, approved_by, approved_by_name, created_at
                FROM pos_discount_log
                WHERE order_id = :orderId
                ORDER BY created_at DESC
                """)
                .param("orderId", orderId)
                .query(DiscountLogEntry.class)
                .list();
    }
}
