package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.merchant.MerchantAccess;
import com.elepay.merchanttools.MerchantToolAccess;
import com.elepay.merchanttools.inventory.ProductCatalogService;
import com.elepay.merchanttools.pos.PosOrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/restaurant/waiter")
public class WaiterController {
    private final JdbcClient db;
    private final MerchantAccess merchants;
    private final MerchantToolAccess access;
    private final ProductCatalogService catalog;
    private final PosOrderService orders;
    private final KdsService kitchen;
    private final Idempotency idempotency;

    public WaiterController(JdbcClient db, MerchantAccess merchants, MerchantToolAccess access,
            ProductCatalogService catalog, PosOrderService orders, KdsService kitchen, Idempotency idempotency) {
        this.db = db; this.merchants = merchants; this.access = access; this.catalog = catalog;
        this.orders = orders; this.kitchen = kitchen; this.idempotency = idempotency;
    }

    public record Table(UUID id, String tableNumber, int capacity, String status, UUID currentOrderId) {}
    public record MenuItem(UUID variantId, String name, String category, long pricePesewas) {}
    public record Line(UUID variantId, int quantity, String notes) {}
    public record Submit(UUID tableId, List<Line> items, String notes) {}
    public record Result(UUID orderId, UUID ticketId, String orderNumber, long totalPesewas) {}
    public record OrderView(UUID orderId, UUID ticketId, String tableNumber, String orderNumber,
            String orderStatus, String kitchenStatus, long totalPesewas, boolean billRequested) {}
    public record BranchAssignment(UUID branchId) {}

    private MerchantAccess.Access waiter(UUID merchant) {
        var caller = merchants.require(merchant, MerchantAccess.Permission.RESTAURANT_ORDER);
        if (!"waiter".equals(caller.role()) || caller.branchId() == null || caller.actor().isApiKey())
            throw ApiException.forbidden("A branch-assigned waiter account is required");
        access.branch(merchant, caller.branchId());
        return caller;
    }

    @PutMapping("/{merchant}/tables/{table}/branch")
    @Transactional
    public Map<String, String> assignTable(@PathVariable UUID merchant, @PathVariable UUID table,
            @RequestBody BranchAssignment assignment) {
        access.manage(merchant);
        access.belongs(merchant, MerchantToolAccess.Resource.TABLE, table);
        if (assignment.branchId() == null) throw ApiException.badRequest("branch_required", "Select a branch");
        access.branch(merchant, assignment.branchId());
        int changed = db.sql("UPDATE restaurant_table SET waiter_branch_id = :b WHERE id = :t AND current_order_id IS NULL")
                .param("b", assignment.branchId()).param("t", table).update();
        if (changed != 1) throw ApiException.conflict("table_in_use", "Table has an order");
        return Map.of("status", "assigned");
    }

    @GetMapping("/{merchant}/tables")
    public List<Table> tables(@PathVariable UUID merchant) {
        var caller = waiter(merchant);
        return db.sql("""
                SELECT id, table_number, capacity, status, current_order_id FROM restaurant_table
                WHERE merchant_id = :m AND waiter_branch_id = :b ORDER BY table_number
                """).param("m", merchant).param("b", caller.branchId()).query(Table.class).list();
    }

    private static String label(ProductCatalogService.CatalogRow row) {
        return row.variantName() == null || row.variantName().equals("Standard") ? row.productName()
                : row.productName() + " (" + row.variantName() + ")";
    }

    @GetMapping("/{merchant}/menu")
    public List<MenuItem> menu(@PathVariable UUID merchant) {
        waiter(merchant);
        return catalog.listCatalog(merchant).stream().map(row ->
                new MenuItem(row.variantId(), label(row), row.categoryName(), row.retailPricePesewas())).toList();
    }

    @PostMapping("/{merchant}/orders")
    @Transactional
    public Result submit(@PathVariable UUID merchant, @RequestHeader("Idempotency-Key") String key,
            @RequestBody Submit req) {
        var caller = waiter(merchant);
        if (req.tableId() == null || req.items() == null || req.items().isEmpty() || req.items().size() > 100
                || (req.notes() != null && req.notes().length() > 500))
            throw ApiException.badRequest("invalid_order", "Choose a table and 1 to 100 items; notes max 500 characters");
        return idempotency.execute("waiter:" + merchant + ":" + Idempotency.sha256(caller.actor().subject()),
                key, req, Result.class, () -> create(merchant, caller, req));
    }

    private Result create(UUID merchant, MerchantAccess.Access caller, Submit req) {
        Table table = db.sql("""
                SELECT id, table_number, capacity, status, current_order_id FROM restaurant_table
                WHERE id = :t AND merchant_id = :m AND waiter_branch_id = :b FOR UPDATE
                """).param("t", req.tableId()).param("m", merchant).param("b", caller.branchId())
                .query(Table.class).optional().orElseThrow(() -> ApiException.notFound("Table not found"));
        if (table.currentOrderId() != null || !"AVAILABLE".equals(table.status()))
            throw ApiException.conflict("table_in_use", "Table is not available");
        var menu = catalog.listCatalog(merchant).stream()
                .collect(Collectors.toMap(ProductCatalogService.CatalogRow::variantId, Function.identity()));
        var items = req.items().stream().map(line -> {
            if (line == null || line.variantId() == null || line.quantity() < 1 || line.quantity() > 99
                    || (line.notes() != null && line.notes().length() > 200))
                throw ApiException.badRequest("invalid_item", "Invalid quantity or item notes");
            var row = menu.get(line.variantId());
            if (row == null) throw ApiException.notFound("Menu item not found");
            return new PosOrderService.OrderItemInput(row.variantId(), label(row), BigDecimal.valueOf(line.quantity()),
                    row.retailPricePesewas(), 0, 0, null, null, "MAIN", line.notes());
        }).toList();
        var order = orders.createOrder(merchant, caller.branchId(), null, null, null, items, 0, 0, 0, "DINE_IN");
        var kitchenItems = orders.listOrderItems(order.id()).stream().map(item ->
                new KdsService.KdsItemInput(item.id(), item.itemName(), item.quantity(), item.notes(), "MAIN")).toList();
        var ticket = kitchen.createTicket(merchant, order.id(), table.id(), DiningMode.DINE_IN, null,
                "MAIN", kitchenItems, req.notes(), "pos:" + order.id());
        db.sql("UPDATE restaurant_table SET status = 'OCCUPIED', current_order_id = :o WHERE id = :t")
                .param("o", order.id()).param("t", table.id()).update();
        db.sql("""
                INSERT INTO restaurant_waiter_order(order_id, merchant_id, table_id, waiter_subject, ticket_id)
                VALUES (:o, :m, :t, :s, :k)
                """).param("o", order.id()).param("m", merchant).param("t", table.id())
                .param("s", caller.actor().subject()).param("k", ticket.id()).update();
        return new Result(order.id(), ticket.id(), order.orderNumber(), order.totalPesewas());
    }

    @GetMapping("/{merchant}/orders")
    public List<OrderView> list(@PathVariable UUID merchant) {
        var caller = waiter(merchant);
        return db.sql("""
                SELECT o.id AS order_id, w.ticket_id, t.table_number, o.order_number,
                    o.status AS order_status, k.status AS kitchen_status, o.total_pesewas,
                    w.bill_requested_at IS NOT NULL AS bill_requested
                FROM restaurant_waiter_order w JOIN pos_order o ON o.id = w.order_id
                JOIN restaurant_table t ON t.id = w.table_id JOIN restaurant_kds_ticket k ON k.id = w.ticket_id
                WHERE w.merchant_id = :m AND w.waiter_subject = :s AND o.branch_id = :b
                  AND t.current_order_id = o.id ORDER BY o.created_at DESC
                """).param("m", merchant).param("s", caller.actor().subject()).param("b", caller.branchId())
                .query(OrderView.class).list();
    }

    private UUID ownedTicket(UUID merchant, MerchantAccess.Access caller, UUID order) {
        return db.sql("""
                SELECT w.ticket_id FROM restaurant_waiter_order w JOIN pos_order o ON o.id = w.order_id
                JOIN restaurant_table t ON t.id = w.table_id
                WHERE w.order_id = :o AND w.merchant_id = :m AND w.waiter_subject = :s
                    AND o.branch_id = :b AND t.current_order_id = o.id
                """).param("o", order).param("m", merchant).param("s", caller.actor().subject())
                .param("b", caller.branchId()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Order not found"));
    }

    @PostMapping("/{merchant}/orders/{order}/served")
    @Transactional
    public void served(@PathVariable UUID merchant, @PathVariable UUID order, @RequestHeader("Idempotency-Key") String key) {
        var caller = waiter(merchant);
        UUID ticket = ownedTicket(merchant, caller, order);
        var current = kitchen.getTicket(ticket).orElseThrow(() -> ApiException.notFound("Ticket not found"));
        if ("SERVED".equals(current.status())) return;
        if (!"READY".equals(current.status())) throw ApiException.conflict("not_ready", "Kitchen has not marked this ready");
        var result = kitchen.advanceTicket(ticket, "READY", key);
        if (!"SERVED".equals(result.view().ticket().status()))
            throw ApiException.conflict("status_changed", "Kitchen status changed; refresh");
    }

    @PostMapping("/{merchant}/orders/{order}/bill")
    @Transactional
    public void bill(@PathVariable UUID merchant, @PathVariable UUID order) {
        var caller = waiter(merchant);
        ownedTicket(merchant, caller, order);
        db.sql("UPDATE restaurant_waiter_order SET bill_requested_at = COALESCE(bill_requested_at, now()) WHERE order_id = :o")
                .param("o", order).update();
    }

    @PostMapping("/{merchant}/orders/{order}/close-table")
    @Transactional
    public void closeTable(@PathVariable UUID merchant, @PathVariable UUID order) {
        var caller = waiter(merchant);
        UUID ticket = ownedTicket(merchant, caller, order);
        if (!"SERVED".equals(kitchen.getTicket(ticket).orElseThrow().status()))
            throw ApiException.conflict("not_served", "Food must be served before closing the table");
        int changed = db.sql("""
                UPDATE restaurant_table t SET status = 'DIRTY', current_order_id = NULL
                FROM pos_order o WHERE t.current_order_id = o.id AND o.id = :o AND o.status = 'PAID'
                """).param("o", order).update();
        if (changed != 1) throw ApiException.conflict("not_paid", "Cashier payment is required before closing the table");
    }

    public record Claim(UUID terminalId, UUID cashierId) {}

    @PostMapping("/{merchant}/orders/{order}/cashier")
    @Transactional
    public PosOrderService.PosOrder claim(@PathVariable UUID merchant, @PathVariable UUID order, @RequestBody Claim req) {
        var caller = access.operateAccess(merchant);
        access.belongs(merchant, MerchantToolAccess.Resource.ORDER, order);
        access.belongs(merchant, MerchantToolAccess.Resource.TERMINAL, req.terminalId());
        access.belongs(merchant, MerchantToolAccess.Resource.CASHIER, req.cashierId());
        access.requireTerminal(caller, req.terminalId());
        access.requireOrderBranch(caller, order);
        int changed = db.sql("""
                UPDATE pos_order o SET terminal_id = :t, cashier_id = :c
                WHERE o.id = :o AND o.merchant_id = :m AND o.status = 'PENDING' AND o.paid_pesewas = 0
                AND (o.terminal_id IS NULL OR (o.terminal_id = :t AND o.cashier_id = :c))
                AND EXISTS (SELECT 1 FROM pos_terminal t JOIN pos_shift s ON s.terminal_id = t.id
                    JOIN pos_cashier c ON c.id = s.cashier_id
                    WHERE t.id = :t AND t.branch_id = o.branch_id AND t.merchant_id = :m
                      AND t.status <> 'REVOKED' AND c.is_active AND s.cashier_id = :c AND s.status = 'OPEN')
                """).param("t", req.terminalId()).param("c", req.cashierId()).param("o", order)
                .param("m", merchant).update();
        if (changed != 1) throw ApiException.conflict("cashier_claim_failed", "An active shift in the order branch is required; another cashier may have claimed this order");
        return orders.getOrder(order).orElseThrow();
    }

    @GetMapping("/{merchant}/bill-requests")
    public List<UUID> billRequests(@PathVariable UUID merchant) {
        var caller = access.operateAccess(merchant);
        return db.sql("""
                SELECT w.order_id FROM restaurant_waiter_order w JOIN pos_order o ON o.id = w.order_id
                WHERE w.merchant_id = :m AND w.bill_requested_at IS NOT NULL AND o.status IN ('PENDING', 'PARTIAL')
                AND (CAST(:b AS uuid) IS NULL OR o.branch_id = :b)
                """).param("m", merchant).param("b", caller.branchId()).query(UUID.class).list();
    }
}
