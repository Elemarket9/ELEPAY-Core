package com.elepay.merchanttools.pos;

import com.elepay.merchanttools.inventory.MovementType;
import com.elepay.merchanttools.inventory.StockService;
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

    public PosOrderService(JdbcClient db, StockService stockService, PosCashierService cashierService) {
        this.db = db;
        this.stockService = stockService;
        this.cashierService = cashierService;
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

    public static String generateOrderNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        int rand = ThreadLocalRandom.current().nextInt(10000, 99999);
        return "ORD-" + date + "-" + rand;
    }

    @Transactional
    public PosOrder createOrder(
            UUID merchantId,
            UUID branchId,
            UUID terminalId,
            UUID cashierId,
            UUID customerId,
            List<OrderItemInput> items,
            long discountPesewas,
            long taxPesewas,
            long tipPesewas,
            String orderType
    ) {
        UUID orderId = UUID.randomUUID();
        String orderNumber = generateOrderNumber();

        long subtotal = 0;
        for (OrderItemInput item : items) {
            long itemTotal = (item.unitPricePesewas() * item.quantity().longValue()) - item.discountPesewas() + item.taxPesewas();
            subtotal += itemTotal;
        }

        long total = subtotal - discountPesewas + taxPesewas + tipPesewas;
        if (total < 0) total = 0;

        db.sql("""
            INSERT INTO pos_order (
                id, order_number, merchant_id, branch_id, terminal_id, cashier_id,
                customer_id, subtotal_pesewas, discount_pesewas, tax_pesewas, tip_pesewas,
                total_pesewas, paid_pesewas, change_pesewas, status, order_type, created_at
            ) VALUES (
                :id, :orderNumber, :merchantId, :branchId, :terminalId, :cashierId,
                :customerId, :subtotal, :discount, :tax, :tip,
                :total, 0, 0, 'PENDING', :orderType, now()
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
            .param("orderType", orderType == null ? "STANDARD" : orderType)
            .update();

        for (OrderItemInput item : items) {
            UUID itemId = UUID.randomUUID();
            long itemTotal = (item.unitPricePesewas() * item.quantity().longValue()) - item.discountPesewas() + item.taxPesewas();

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

    @Transactional
    public PosOrder addTenderPayment(UUID orderId, TenderType tenderType, long amountPesewas, String reference) {
        PosOrder order = getOrder(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if ("PAID".equals(order.status())) {
            throw new IllegalStateException("Order is already fully paid");
        }

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

        // Update active shift if cash
        if (tenderType == TenderType.CASH && order.terminalId() != null) {
            cashierService.getActiveShift(order.terminalId())
                    .ifPresent(shift -> cashierService.recordCashTender(shift.id(), amountPesewas));
        }

        long newPaid = order.paidPesewas() + amountPesewas;
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
