package com.elepay.merchanttools.pos;

import com.elepay.merchanttools.inventory.MovementType;
import com.elepay.merchanttools.inventory.StockService;
import com.elepay.merchanttools.restaurant.DiningMode;
import com.elepay.merchanttools.restaurant.KdsService;
import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosOrderService {
    private final JdbcClient db;
    private final StockService stockService;
    private final PosCashierService cashierService;
    private final com.elepay.checkout.PosCheckoutGateway checkout;
    private final KdsService kitchen;

    public PosOrderService(JdbcClient db, StockService stockService, PosCashierService cashierService,
                           com.elepay.checkout.PosCheckoutGateway checkout, KdsService kitchen) {
        this.db = db;
        this.stockService = stockService;
        this.cashierService = cashierService;
        this.checkout = checkout;
        this.kitchen = kitchen;
    }

    public record PosOrder(
            UUID id,
            String orderNumber,
            UUID merchantId,
            UUID branchId,
            UUID terminalId,
            UUID cashierId,
            UUID customerId,
            long subtotalPesewas,
            long discountPesewas,
            long taxPesewas,
            long tipPesewas,
            long totalPesewas,
            long paidPesewas,
            long changePesewas,
            String status,
            String orderType,
            OffsetDateTime createdAt
    ) {}

    public record PosOrderItem(
            UUID id,
            UUID orderId,
            UUID variantId,
            String itemName,
            BigDecimal quantity,
            long unitPricePesewas,
            long discountPesewas,
            long taxPesewas,
            long totalPesewas,
            String modifiersJson,
            Integer seatNumber,
            String course,
            String notes
    ) {}

    public record OrderItemInput(
            UUID variantId,
            String itemName,
            BigDecimal quantity,
            long unitPricePesewas,
            long discountPesewas,
            long taxPesewas,
            String modifiersJson,
            Integer seatNumber,
            String course,
            String notes
    ) {}

    public record TenderPayment(
            UUID id,
            UUID orderId,
            String tenderType,
            long amountPesewas,
            String reference,
            String status,
            OffsetDateTime createdAt
    ) {}

    private static long itemTotal(OrderItemInput item) {
        if (item == null || item.quantity() == null || item.quantity().signum() <= 0
                || item.unitPricePesewas() < 0 || item.discountPesewas() < 0 || item.taxPesewas() < 0)
            throw new IllegalArgumentException("Invalid POS item amounts");
        long gross = item.quantity().multiply(BigDecimal.valueOf(item.unitPricePesewas()))
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        if (item.discountPesewas() > gross) throw new IllegalArgumentException("Item discount exceeds gross");
        return Math.addExact(Math.subtractExact(gross, item.discountPesewas()), item.taxPesewas());
    }

    public static String generateOrderNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        int rand = ThreadLocalRandom.current().nextInt(10000, 99999);
        return "ORD-" + date + "-" + rand;
    }

    @Transactional
    public PosOrder createOrder(
            UUID merchantId, UUID branchId, UUID terminalId, UUID cashierId, UUID customerId,
            List<OrderItemInput> items, long discountPesewas, long taxPesewas,
            long tipPesewas, String orderType
    ) {
        return createOrder(merchantId, branchId, terminalId, cashierId, customerId, items,
                discountPesewas, taxPesewas, tipPesewas, orderType, null);
    }

    /**
     * A restaurant order, table assignment and the first kitchen ticket commit atomically.
     * Legacy RETAIL, STANDARD and RESTAURANT orders retain their existing behavior.
     * The latter is used by existing integrations that explicitly create their own kitchen tickets.
     */
    @Transactional
    public PosOrder createOrder(
            UUID merchantId, UUID branchId, UUID terminalId, UUID cashierId, UUID customerId,
            List<OrderItemInput> items, long discountPesewas, long taxPesewas,
            long tipPesewas, String orderType, UUID tableId
    ) {
        return createOrder(merchantId, branchId, terminalId, cashierId, customerId, items,
                discountPesewas, taxPesewas, tipPesewas, orderType, tableId, null);
    }

    /** The actor is supplied by server authentication, not by the cashier's request JSON. */
    @Transactional
    public PosOrder createOrder(
            UUID merchantId, UUID branchId, UUID terminalId, UUID cashierId, UUID customerId,
            List<OrderItemInput> items, long discountPesewas, long taxPesewas,
            long tipPesewas, String orderType, UUID tableId, String actorSubject
    ) {
        if (actorSubject != null && (actorSubject.isBlank() || actorSubject.length() > 200))
            throw ApiException.badRequest("actor_invalid", "Invalid authenticated staff identity");
        String channel = orderType == null ? "STANDARD" : orderType;
        boolean toKitchen = java.util.Set.of("DINE_IN", "TAKEOUT", "DELIVERY", "QR").contains(channel);
        if (!toKitchen && !java.util.Set.of("STANDARD", "RETAIL", "RESTAURANT", "OFFLINE_SYNC").contains(channel))
            throw ApiException.badRequest("order_channel_invalid", "Unknown POS order channel");
        if (tableId != null && !java.util.Set.of("DINE_IN", "QR").contains(channel))
            throw ApiException.badRequest("table_channel_invalid", "Only dine-in and table QR orders may select a table");
        if (("DINE_IN".equals(channel) || "QR".equals(channel)) && tableId == null)
            throw ApiException.badRequest("table_required", "Select a table for dine-in orders");
        UUID orderId = UUID.randomUUID();
        String orderNumber = generateOrderNumber();

        if (items == null || items.isEmpty() || discountPesewas < 0 || taxPesewas < 0 || tipPesewas < 0)
            throw new IllegalArgumentException("Invalid order amounts or empty items");
        long subtotal = 0;
        for (OrderItemInput item : items) subtotal = Math.addExact(subtotal, itemTotal(item));
        if (discountPesewas > subtotal) throw new IllegalArgumentException("Order discount exceeds subtotal");
        long total = Math.addExact(Math.addExact(Math.subtractExact(subtotal, discountPesewas), taxPesewas), tipPesewas);

        db.sql("""
            INSERT INTO pos_order (
                id, order_number, merchant_id, branch_id, terminal_id, cashier_id,
                customer_id, subtotal_pesewas, discount_pesewas, tax_pesewas, tip_pesewas,
                total_pesewas, paid_pesewas, change_pesewas, status, order_type, created_by_subject, created_at
            ) VALUES (
                :id, :orderNumber, :merchantId, :branchId, :terminalId, :cashierId,
                :customerId, :subtotal, :discount, :tax, :tip,
                :total, 0, 0, 'PENDING', :orderType, :createdBy, now()
            )
            """)
            .param("id", orderId)
            .param("orderNumber", orderNumber)
            .param("merchantId", merchantId)
            .param("branchId", branchId)
            .param("terminalId", terminalId)
            .param("cashierId", cashierId)
            .param("customerId", customerId)
            .param("subtotal", subtotal)
            .param("discount", discountPesewas)
            .param("tax", taxPesewas)
            .param("tip", tipPesewas)
            .param("total", total)
            .param("orderType", channel)
            .param("createdBy", actorSubject)
            .update();

        for (OrderItemInput item : items) {
            UUID itemId = UUID.randomUUID();
            long itemTotal = itemTotal(item);

            db.sql("""
                INSERT INTO pos_order_item (
                    id, order_id, variant_id, item_name, quantity, unit_price_pesewas,
                    discount_pesewas, tax_pesewas, total_pesewas, modifiers_json,
                    seat_number, course, notes
                ) VALUES (
                    :id, :orderId, :variantId, :itemName, :quantity, :unitPrice,
                    :discount, :tax, :total, :modifiersJson,
                    :seatNumber, :course, :notes
                )
                """)
                .param("id", itemId)
                .param("orderId", orderId)
                .param("variantId", item.variantId())
                .param("itemName", item.itemName())
                .param("quantity", item.quantity())
                .param("unitPrice", item.unitPricePesewas())
                .param("discount", item.discountPesewas())
                .param("tax", item.taxPesewas())
                .param("total", itemTotal)
                .param("modifiersJson", item.modifiersJson() == null ? "[]" : item.modifiersJson())
                .param("seatNumber", item.seatNumber())
                .param("course", item.course())
                .param("notes", item.notes())
                .update();
        }

        if (tableId != null) {
            // Claim only a currently free table; concurrent waiters cannot overwrite each other's orders.
            int claimed = db.sql("""
                    UPDATE restaurant_table SET status = 'OCCUPIED', current_order_id = :order
                    WHERE id = :table AND merchant_id = :merchant
                      AND status = 'AVAILABLE' AND current_order_id IS NULL
                    """).param("order", orderId).param("table", tableId).param("merchant", merchantId).update();
            if (claimed != 1)
                throw ApiException.conflict("table_unavailable", "Table is occupied or not available");
        }
        if (toKitchen) {
            DiningMode mode = switch (channel) {
                case "TAKEOUT" -> DiningMode.TAKEOUT;
                case "DELIVERY" -> DiningMode.DELIVERY;
                default -> DiningMode.DINE_IN;
            };
            List<KdsService.KdsItemInput> kitchenItems = listOrderItems(orderId).stream()
                    .map(i -> new KdsService.KdsItemInput(i.id(), i.itemName(), i.quantity(),
                            i.modifiersJson(), i.course() == null ? "MAIN" : i.course()))
                    .toList();
            // Client reference prevents duplicate cooking tickets if this integration is retried.
            kitchen.createTicket(merchantId, orderId, tableId, mode, null, "ALL", kitchenItems,
                    null, "pos:" + orderId);
        }
        return getOrder(orderId).orElseThrow();
    }

    /**
     * Add a second round to an unpaid restaurant order. One customer bill, one set
     * of tender payments, and one NEW kitchen ticket for only the added items.
     * All changes (items, total, ticket) commit in the same database transaction.
     */
    @Transactional
    public PosOrder addItems(UUID orderId, List<OrderItemInput> inputs, String operationKey) {
        PosOrder order = lockOrder(orderId);
        if (!java.util.Set.of("DINE_IN", "TAKEOUT", "DELIVERY", "QR").contains(order.orderType()))
            throw ApiException.conflict("restaurant_order_required", "Only restaurant orders can add a new round");
        if (!"PENDING".equals(order.status()) || order.paidPesewas() != 0 || link(orderId).isPresent())
            throw ApiException.conflict("order_payment_started", "Cannot change items after payment has started");
        if (inputs == null || inputs.isEmpty())
            throw ApiException.badRequest("items_required", "Choose items for the new round");
        if (operationKey == null || operationKey.isBlank() || operationKey.length() > 128)
            throw ApiException.badRequest("idempotency_required", "New rounds need an idempotency key");
        UUID tableId = db.sql("SELECT id FROM restaurant_table WHERE current_order_id = :order")
                .param("order", orderId).query(UUID.class).optional().orElse(null);
        if (("DINE_IN".equals(order.orderType()) || "QR".equals(order.orderType())) && tableId == null)
            throw ApiException.conflict("table_unavailable", "The original table is no longer occupied by this order");
        long extraTotal = 0;
        List<KdsService.KdsItemInput> kitchenItems = new java.util.ArrayList<>();
        for (OrderItemInput item : inputs) {
            long amount = itemTotal(item);
            extraTotal = Math.addExact(extraTotal, amount);
            UUID lineId = UUID.randomUUID();
            db.sql("""
                    INSERT INTO pos_order_item (id, order_id, variant_id, item_name, quantity,
                        unit_price_pesewas, discount_pesewas, tax_pesewas, total_pesewas,
                        modifiers_json, seat_number, course, notes)
                    VALUES (:id, :order, :variant, :name, :quantity, :price, :discount, :tax,
                        :total, :modifiers, :seat, :course, :notes)
                    """)
                    .param("id", lineId).param("order", orderId).param("variant", item.variantId())
                    .param("name", item.itemName()).param("quantity", item.quantity())
                    .param("price", item.unitPricePesewas()).param("discount", item.discountPesewas())
                    .param("tax", item.taxPesewas()).param("total", amount)
                    .param("modifiers", item.modifiersJson() == null ? "[]" : item.modifiersJson())
                    .param("seat", item.seatNumber()).param("course", item.course())
                    .param("notes", item.notes()).update();
            kitchenItems.add(new KdsService.KdsItemInput(lineId, item.itemName(), item.quantity(),
                    item.modifiersJson(), item.course() == null ? "MAIN" : item.course()));
        }
        db.sql("""
                UPDATE pos_order SET subtotal_pesewas = subtotal_pesewas + :amount,
                    total_pesewas = total_pesewas + :amount WHERE id = :id
                """).param("amount", extraTotal).param("id", orderId).update();
        DiningMode mode = switch (order.orderType()) {
            case "TAKEOUT" -> DiningMode.TAKEOUT;
            case "DELIVERY" -> DiningMode.DELIVERY;
            default -> DiningMode.DINE_IN;
        };
        String key = "round:" + UUID.nameUUIDFromBytes(
                (orderId + ":" + operationKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kitchen.createTicket(order.merchantId(), orderId, tableId, mode, null,
                "ALL", kitchenItems, "ADDITIONAL ROUND", key);
        return getOrder(orderId).orElseThrow();
    }

    public Optional<PosOrder> getOrder(UUID id) {
        return db.sql("""
            SELECT id, order_number, merchant_id, branch_id, terminal_id, cashier_id,
                   customer_id, subtotal_pesewas, discount_pesewas, tax_pesewas, tip_pesewas,
                   total_pesewas, paid_pesewas, change_pesewas, status, order_type, created_at
            FROM pos_order WHERE id = :id
            """)
            .param("id", id)
            .query(PosOrder.class)
            .optional();
    }

    public List<PosOrderItem> listOrderItems(UUID orderId) {
        return db.sql("""
            SELECT id, order_id, variant_id, item_name, quantity, unit_price_pesewas,
                   discount_pesewas, tax_pesewas, total_pesewas, modifiers_json,
                   seat_number, course, notes
            FROM pos_order_item WHERE order_id = :orderId
            """)
            .param("orderId", orderId)
            .query(PosOrderItem.class)
            .list();
    }

    public List<TenderPayment> listTenderPayments(UUID orderId) {
        return db.sql("""
            SELECT id, order_id, tender_type, amount_pesewas, reference, status, created_at
            FROM pos_tender_payment WHERE order_id = :orderId
            ORDER BY created_at ASC
            """)
            .param("orderId", orderId)
            .query(TenderPayment.class)
            .list();
    }

    public record DigitalCheckout(UUID id, UUID orderId, String reference, long amount, String status, String payUrl) {}
    private record Link(UUID id, UUID orderId, String checkoutRef, long amount, String status) {}

    private PosOrder lockOrder(UUID orderId) {
        db.sql("SELECT id FROM pos_order WHERE id = :id FOR UPDATE").param("id", orderId).query(UUID.class)
                .optional().orElseThrow(() -> com.elepay.common.ApiException.notFound("Order not found"));
        return getOrder(orderId).orElseThrow();
    }

    private Optional<Link> link(UUID orderId) {
        return db.sql("""
                SELECT id, order_id, checkout_ref, amount, status FROM pos_checkout_link
                WHERE order_id = :id AND status IN ('open','applied') ORDER BY created_at DESC LIMIT 1
                """).param("id",orderId).query(Link.class).optional();
    }

    @Transactional
    public DigitalCheckout startDigital(UUID orderId, String actor) {
        PosOrder order = lockOrder(orderId);
        Optional<Link> existing = link(orderId);
        if (existing.isPresent()) {
            Link l = existing.get();
            return new DigitalCheckout(l.id(), orderId, l.checkoutRef(), l.amount(), l.status(), checkout.payUrl(l.checkoutRef()));
        }
        long amount = Math.subtractExact(order.totalPesewas(), order.paidPesewas());
        if ("PAID".equals(order.status()) || amount <= 0)
            throw com.elepay.common.ApiException.conflict("order_paid", "Order already paid");
        UUID linkId = UUID.randomUUID();
        String ref = checkout.create(order.merchantId(), order.branchId(), linkId, amount, actor);
        db.sql("""
                INSERT INTO pos_checkout_link (id, order_id, checkout_ref, amount, status)
                VALUES (:id, :order, :ref, :amount, 'open')
                """).param("id",linkId).param("order",orderId).param("ref",ref).param("amount",amount).update();
        return new DigitalCheckout(linkId, orderId, ref, amount, "open", checkout.payUrl(ref));
    }

    @Transactional
    public PosOrder confirmDigital(UUID orderId) {
        PosOrder order = lockOrder(orderId);
        Link l = link(orderId).orElseThrow(() -> com.elepay.common.ApiException.notFound("Digital checkout not found"));
        if ("applied".equals(l.status())) return order;
        if (Math.subtractExact(order.totalPesewas(), order.paidPesewas()) != l.amount())
            throw com.elepay.common.ApiException.conflict("pos_payment_mismatch", "POS balance no longer matches checkout");
        var evidence = checkout.requirePaid(l.checkoutRef(),order.merchantId(),order.branchId(),l.amount());
        TenderType type = switch (evidence.method()) {
            case "momo" -> TenderType.MOMO;
            case "card" -> TenderType.CARD;
            case "wallet" -> TenderType.WALLET;
            default -> throw com.elepay.common.ApiException.unprocessable("unsupported_payment", "Unsupported checkout payment method");
        };
        PosOrder result = recordTender(order,type,l.amount(),l.checkoutRef());
        db.sql("UPDATE pos_checkout_link SET status = 'applied', journal_tx = :journal WHERE id = :id")
                .param("journal",evidence.journalTx()).param("id",l.id()).update();
        return result;
    }

    @Transactional
    public void cancelDigital(UUID orderId, String actor) {
        PosOrder order = lockOrder(orderId);
        Link l = link(orderId).orElseThrow(() -> com.elepay.common.ApiException.notFound("Digital checkout not found"));
        if (!"open".equals(l.status()))
            throw com.elepay.common.ApiException.conflict("checkout_paid", "Paid checkout cannot be cancelled");
        checkout.cancel(order.merchantId(),order.branchId(),l.checkoutRef(),actor);
        db.sql("UPDATE pos_checkout_link SET status = 'cancelled' WHERE id = :id").param("id",l.id()).update();
    }

    @Transactional
    public PosOrder addTenderPayment(UUID orderId, TenderType tenderType, long amountPesewas, String reference) {
        if (tenderType != TenderType.CASH)
            throw com.elepay.common.ApiException.unprocessable("digital_payment_unverified", "Use the linked ELEPAY checkout");
        PosOrder order = lockOrder(orderId);
        if (link(orderId).isPresent())
            throw com.elepay.common.ApiException.conflict("digital_payment_active", "Resolve the linked checkout before recording cash");
        return recordTender(order, tenderType, amountPesewas, reference);
    }

    private PosOrder recordTender(PosOrder order, TenderType tenderType, long amountPesewas, String reference) {
        if (amountPesewas <= 0) throw new IllegalArgumentException("Payment amount must be positive");
        UUID orderId = order.id();
        if ("PAID".equals(order.status()))
            throw com.elepay.common.ApiException.conflict("order_paid", "Order is already fully paid");

        UUID paymentId = UUID.randomUUID();
        db.sql("""
            INSERT INTO pos_tender_payment (id, order_id, tender_type, amount_pesewas, reference, status, created_at)
            VALUES (:id, :orderId, :tenderType, :amount, :reference, 'SUCCESS', now())
            """)
            .param("id", paymentId)
            .param("orderId", orderId)
            .param("tenderType", tenderType.name())
            .param("amount", amountPesewas)
            .param("reference", reference)
            .update();

        long newPaid = Math.addExact(order.paidPesewas(), amountPesewas);
        long change = 0;
        String status = "PARTIAL";

        if (newPaid >= order.totalPesewas()) {
            status = "PAID";
            if (tenderType == TenderType.CASH && newPaid > order.totalPesewas()) {
                change = newPaid - order.totalPesewas();
            }

            // Deduct stock for inventory-tracked variants
            deductInventoryForOrder(orderId, order.merchantId());
        }

        // The cash drawer holds cash retained, not gross tender including change returned.
        if (tenderType == TenderType.CASH && order.terminalId() != null) {
            long retainedCash = Math.subtractExact(amountPesewas, change);
            cashierService.getActiveShift(order.terminalId())
                    .ifPresent(shift -> cashierService.recordCashTender(shift.id(), retainedCash));
        }

        db.sql("""
            UPDATE pos_order
            SET paid_pesewas = :newPaid, change_pesewas = :change, status = :status
            WHERE id = :id
            """)
            .param("newPaid", newPaid)
            .param("change", change)
            .param("status", status)
            .param("id", orderId)
            .update();

        return getOrder(orderId).orElseThrow();
    }

    private void deductInventoryForOrder(UUID orderId, UUID merchantId) {
        // Find default warehouse for merchant
        var defaultWarehouseOpt = db.sql("SELECT id FROM inventory_warehouse WHERE merchant_id = :m LIMIT 1")
                .param("m", merchantId)
                .query(UUID.class)
                .optional();

        if (defaultWarehouseOpt.isEmpty()) {
            return; // No warehouse configured, skip stock deduction
        }

        UUID warehouseId = defaultWarehouseOpt.get();
        List<PosOrderItem> items = listOrderItems(orderId);
        for (PosOrderItem item : items) {
            if (item.variantId() != null) {
                try {
                    stockService.deductStock(
                            warehouseId,
                            item.variantId(),
                            item.quantity(),
                            MovementType.SALE_DEDUCTION,
                            orderId,
                            "POS Sale Order " + orderId
                    );
                } catch (Exception e) {
                    // Log or handle stock deduction note without breaking order completion
                }
            }
        }
    }
}
