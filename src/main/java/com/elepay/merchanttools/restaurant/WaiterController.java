package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.identity.Actor;
import com.elepay.identity.NativeAuthService;
import com.elepay.merchant.MerchantAccess;
import com.elepay.merchanttools.MerchantToolAccess;
import com.elepay.merchanttools.inventory.ProductCatalogService;
import com.elepay.merchanttools.pos.PosOrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
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
    private final RestaurantTableService tableService;
    private final Idempotency idempotency;
    private final NativeAuthService nativeAuth;

    public WaiterController(JdbcClient db, MerchantAccess merchants, MerchantToolAccess access,
            ProductCatalogService catalog, PosOrderService orders, KdsService kitchen,
            RestaurantTableService tableService, Idempotency idempotency,
            NativeAuthService nativeAuth) {
        this.db = db; this.merchants = merchants; this.access = access; this.catalog = catalog;
        this.orders = orders; this.kitchen = kitchen; this.tableService = tableService;
        this.idempotency = idempotency; this.nativeAuth = nativeAuth;
    }

    public record WaiterTable(UUID id, String tableNumber, int capacity, String status, UUID currentOrderId, boolean ownedByMe) {
        public WaiterTable(UUID id, String tableNumber, int capacity, String status, UUID currentOrderId) {
            this(id, tableNumber, capacity, status, currentOrderId, false);
        }
    }
    public record WaiterMenuItem(UUID variantId, String name, String category, long pricePesewas) {}
    public record WaiterLine(UUID variantId, int quantity, String notes, String course) {
        public WaiterLine(UUID variantId, int quantity, String notes) {
            this(variantId, quantity, notes, null);
        }
    }
    public record WaiterOrderRequest(UUID tableId, List<WaiterLine> items, String notes) {}
    public record WaiterAddItemsRequest(List<WaiterLine> items, String notes) {}
    public record WaiterOrderResult(UUID orderId, UUID ticketId, String orderNumber, long totalPesewas) {}
    public record WaiterOrderView(UUID orderId, UUID ticketId, String tableNumber, String orderNumber,
            String orderStatus, String kitchenStatus, long totalPesewas, boolean billRequested) {}
    public record WaiterBranchAssignment(UUID branchId) {}

    private static String normalizeCourse(String raw) {
        if (raw == null || raw.isBlank()) return Course.MAIN.name();
        String candidate = raw.strip().toUpperCase(Locale.ROOT);
        try {
            return Course.valueOf(candidate).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_course", "Invalid course: " + raw);
        }
    }

    private String resolveServerName(MerchantAccess.Access caller) {
        String name = Actor.currentDisplayName().orElse(null);
        if (name == null || name.isBlank()) {
            String profileName = nativeAuth.currentProfile(caller.actor()).fullName();
            if (profileName != null && !profileName.isBlank()) {
                name = profileName.strip();
            }
        }
        if (name == null || name.isBlank()) {
            String subject = caller.actor().subject();
            name = (subject == null || subject.isBlank()) ? null : subject.strip();
        }
        if (name != null && name.length() > 64) {
            name = name.substring(0, 64);
        }
        return name;
    }

    private MerchantAccess.Access waiter(UUID merchant) {
        var caller = merchants.require(merchant, MerchantAccess.Permission.RESTAURANT_ORDER);
        if (!"waiter".equals(caller.role()) || caller.branchId() == null || caller.actor().isApiKey())
            throw ApiException.forbidden("A branch-assigned waiter account is required");
        access.branch(merchant, caller.branchId());
        return caller;
    }

    @PutMapping("/{merchant}/tables/{table}/branch")
    @Transactional
    public Map<String, String> waiterAssignTable(@PathVariable UUID merchant, @PathVariable UUID table,
            @RequestBody WaiterBranchAssignment assignment) {
        access.manage(merchant);
        access.belongs(merchant, MerchantToolAccess.Resource.TABLE, table);
        if (assignment.branchId() == null) throw ApiException.badRequest("branch_required", "Select a branch");
        access.branch(merchant, assignment.branchId());
        int changed = db.sql("""
                UPDATE restaurant_table
                SET branch_id = :b, waiter_branch_id = :b
                WHERE id = :t AND current_order_id IS NULL
                """)
                .param("b", assignment.branchId()).param("t", table).update();
        if (changed != 1) throw ApiException.conflict("table_in_use", "Table has an order");
        return Map.of("status", "assigned");
    }

    @GetMapping("/{merchant}/tables")
    public List<WaiterTable> waiterTables(@PathVariable UUID merchant) {
        var caller = waiter(merchant);
        return db.sql("""
                SELECT t.id, t.table_number, t.capacity, t.status, t.current_order_id,
                       (w.order_id IS NOT NULL) AS owned_by_me
                FROM restaurant_table t
                LEFT JOIN restaurant_waiter_order w
                       ON w.order_id = t.current_order_id AND w.waiter_subject = :s
                WHERE t.merchant_id = :m AND COALESCE(t.branch_id, t.waiter_branch_id) = :b
                ORDER BY t.table_number
                """)
                .param("m", merchant)
                .param("b", caller.branchId())
                .param("s", caller.actor().subject())
                .query(WaiterTable.class)
                .list();
    }

    private static String label(ProductCatalogService.CatalogRow row) {
        return row.variantName() == null || row.variantName().equals("Standard") ? row.productName()
                : row.productName() + " (" + row.variantName() + ")";
    }

    @GetMapping("/{merchant}/menu")
    public List<WaiterMenuItem> waiterMenu(@PathVariable UUID merchant) {
        waiter(merchant);
        return catalog.listCatalog(merchant).stream().map(row ->
                new WaiterMenuItem(row.variantId(), label(row), row.categoryName(), row.retailPricePesewas())).toList();
    }

    private List<PosOrderService.OrderItemInput> resolveWaiterItems(UUID merchant, List<WaiterLine> lines) {
        var menu = catalog.listCatalog(merchant).stream()
                .collect(Collectors.toMap(ProductCatalogService.CatalogRow::variantId, Function.identity()));
        return lines.stream().map(line -> {
            if (line == null || line.variantId() == null || line.quantity() < 1 || line.quantity() > 99
                    || (line.notes() != null && line.notes().length() > 200))
                throw ApiException.badRequest("invalid_item", "Invalid quantity or item notes");
            var row = menu.get(line.variantId());
            if (row == null) throw ApiException.notFound("Menu item not found");
            return new PosOrderService.OrderItemInput(row.variantId(), label(row), BigDecimal.valueOf(line.quantity()),
                    row.retailPricePesewas(), 0, 0, null, null, normalizeCourse(line.course()), line.notes());
        }).toList();
    }

    @PostMapping("/{merchant}/orders")
    @Transactional
    public WaiterOrderResult waiterSubmitOrder(@PathVariable UUID merchant, @RequestHeader("Idempotency-Key") String key,
            @RequestBody WaiterOrderRequest req) {
        var caller = waiter(merchant);
        if (req.tableId() == null || req.items() == null || req.items().isEmpty() || req.items().size() > 100
                || (req.notes() != null && req.notes().length() > 500))
            throw ApiException.badRequest("invalid_order", "Choose a table and 1 to 100 items; notes max 500 characters");
        return idempotency.execute("waiter:" + merchant + ":" + Idempotency.sha256(caller.actor().subject()),
                key, req, WaiterOrderResult.class, () -> create(merchant, caller, req));
    }

    private WaiterOrderResult create(UUID merchant, MerchantAccess.Access caller, WaiterOrderRequest req) {
        WaiterTable table = db.sql("""
                SELECT id, table_number, capacity, status, current_order_id, false AS owned_by_me
                FROM restaurant_table
                WHERE id = :t AND merchant_id = :m AND COALESCE(branch_id, waiter_branch_id) = :b
                FOR UPDATE
                """).param("t", req.tableId()).param("m", merchant).param("b", caller.branchId())
                .query(WaiterTable.class).optional().orElseThrow(() -> ApiException.notFound("Table not found"));
        if (table.currentOrderId() != null || !"AVAILABLE".equals(table.status()))
            throw ApiException.conflict("table_in_use", "Table is not available");
        var items = resolveWaiterItems(merchant, req.items());
        var order = orders.createOrder(merchant, caller.branchId(), null, null, null, items, 0, 0, 0, "DINE_IN");
        String serverName = resolveServerName(caller);
        var ticket = orders.fireUnfiredItemsToKds(order.id(), table.id(), serverName, req.notes())
                .orElseThrow(() -> ApiException.conflict("kds_ticket_failed", "Failed to create KDS ticket"));
        db.sql("UPDATE restaurant_table SET status = 'OCCUPIED', current_order_id = :o WHERE id = :t")
                .param("o", order.id()).param("t", table.id()).update();
        db.sql("""
                INSERT INTO restaurant_waiter_order(order_id, merchant_id, table_id, waiter_subject, ticket_id)
                VALUES (:o, :m, :t, :s, :k)
                """).param("o", order.id()).param("m", merchant).param("t", table.id())
                .param("s", caller.actor().subject()).param("k", ticket.id()).update();
        return new WaiterOrderResult(order.id(), ticket.id(), order.orderNumber(), order.totalPesewas());
    }

    @PostMapping("/{merchant}/orders/{orderId}/items")
    @Transactional
    public WaiterOrderResult waiterAddItems(
            @PathVariable UUID merchant,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody WaiterAddItemsRequest req) {
        var caller = waiter(merchant);
        if (req == null || req.items() == null || req.items().isEmpty() || req.items().size() > 100
                || (req.notes() != null && req.notes().length() > 500)) {
            throw ApiException.badRequest("invalid_order", "Choose 1 to 100 items; notes max 500 characters");
        }
        UUID tableId = ownedTableForOrder(merchant, caller, orderId);
        return idempotency.execute(
                "waiter-add:" + merchant + ":" + orderId + ":" + Idempotency.sha256(caller.actor().subject()),
                key,
                req,
                WaiterOrderResult.class,
                () -> {
                    var items = resolveWaiterItems(merchant, req.items());
                    var updatedOrder = orders.addItemsToOrder(orderId, items);
                    var ticket = orders.fireUnfiredItemsToKds(orderId, tableId, resolveServerName(caller), req.notes())
                            .orElseThrow(() -> ApiException.conflict("kds_ticket_failed", "Failed to create add-on KDS ticket"));
                    db.sql("UPDATE restaurant_waiter_order SET ticket_id = :k WHERE order_id = :o")
                            .param("k", ticket.id())
                            .param("o", orderId)
                            .update();
                    return new WaiterOrderResult(orderId, ticket.id(), updatedOrder.orderNumber(), updatedOrder.totalPesewas());
                });
    }

    @GetMapping("/{merchant}/orders")
    public List<WaiterOrderView> waiterListOrders(@PathVariable UUID merchant) {
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
                .query(WaiterOrderView.class).list();
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

    private UUID ownedTableForOrder(UUID merchant, MerchantAccess.Access caller, UUID order) {
        return db.sql("""
                SELECT w.table_id FROM restaurant_waiter_order w JOIN pos_order o ON o.id = w.order_id
                JOIN restaurant_table t ON t.id = w.table_id
                WHERE w.order_id = :o AND w.merchant_id = :m AND w.waiter_subject = :s
                    AND o.branch_id = :b AND t.current_order_id = o.id
                """).param("o", order).param("m", merchant).param("s", caller.actor().subject())
                .param("b", caller.branchId()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Order not found"));
    }

    @PostMapping("/{merchant}/orders/{order}/served")
    @Transactional
    public void waiterMarkServed(@PathVariable UUID merchant, @PathVariable UUID order, @RequestHeader("Idempotency-Key") String key) {
        var caller = waiter(merchant);
        ownedTicket(merchant, caller, order);
        List<UUID> readyTickets = db.sql("""
                SELECT id FROM restaurant_kds_ticket
                WHERE merchant_id = :m AND order_id = :o AND status = 'READY'
                ORDER BY created_at ASC, id ASC
                """)
                .param("m", merchant)
                .param("o", order)
                .query(UUID.class)
                .list();
        if (readyTickets.isEmpty()) {
            long unreadyOpen = db.sql("""
                    SELECT count(*) FROM restaurant_kds_ticket
                    WHERE merchant_id = :m AND order_id = :o AND status IN ('NEW', 'PREPARING')
                    """).param("m", merchant).param("o", order).query(Long.class).single();
            long served = db.sql("""
                    SELECT count(*) FROM restaurant_kds_ticket
                    WHERE merchant_id = :m AND order_id = :o AND status = 'SERVED'
                    """).param("m", merchant).param("o", order).query(Long.class).single();
            if (unreadyOpen == 0 && served > 0) {
                return;
            }
            throw ApiException.conflict("not_ready", "Kitchen has not marked this ready");
        }
        for (UUID ticketId : readyTickets) {
            String actionKey = readyTickets.size() == 1 ? key : key + ":" + ticketId;
            var result = kitchen.advanceTicket(ticketId, "READY", actionKey);
            if (!"SERVED".equals(result.view().ticket().status())) {
                throw ApiException.conflict("status_changed", "Kitchen status changed; refresh");
            }
        }
    }

    @PostMapping("/{merchant}/orders/{order}/bill")
    @Transactional
    public void waiterRequestBill(@PathVariable UUID merchant, @PathVariable UUID order) {
        var caller = waiter(merchant);
        ownedTicket(merchant, caller, order);
        db.sql("UPDATE restaurant_waiter_order SET bill_requested_at = COALESCE(bill_requested_at, now()) WHERE order_id = :o")
                .param("o", order).update();
        tableService.markBillPrintedForOrder(merchant, order);
    }

    @PostMapping("/{merchant}/orders/{order}/close-table")
    @Transactional
    public void waiterCloseTable(@PathVariable UUID merchant, @PathVariable UUID order) {
        var caller = waiter(merchant);
        ownedTicket(merchant, caller, order);
        long openTickets = db.sql("""
                SELECT count(*) FROM restaurant_kds_ticket
                WHERE merchant_id = :m AND order_id = :o AND status IN ('NEW', 'PREPARING', 'READY')
                """).param("m", merchant).param("o", order).query(Long.class).single();
        long servedTickets = db.sql("""
                SELECT count(*) FROM restaurant_kds_ticket
                WHERE merchant_id = :m AND order_id = :o AND status = 'SERVED'
                """).param("m", merchant).param("o", order).query(Long.class).single();
        if (openTickets > 0 || servedTickets == 0)
            throw ApiException.conflict("not_served", "Food must be served before closing the table");
        int changed = db.sql("""
                UPDATE restaurant_table t SET status = 'DIRTY', current_order_id = NULL
                FROM pos_order o WHERE t.current_order_id = o.id AND o.id = :o AND o.status = 'PAID'
                """).param("o", order).update();
        if (changed < 1) throw ApiException.conflict("not_paid", "Cashier payment is required before closing the table");
    }

    public record WaiterCashierClaim(UUID terminalId, UUID cashierId) {}

    @PostMapping("/{merchant}/orders/{order}/cashier")
    @Transactional
    public PosOrderService.PosOrder waiterClaimOrder(@PathVariable UUID merchant, @PathVariable UUID order, @RequestBody WaiterCashierClaim req) {
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
                AND EXISTS (SELECT 1 FROM restaurant_waiter_order w JOIN restaurant_table rt ON rt.id = w.table_id
                    WHERE w.order_id = o.id AND rt.current_order_id = o.id)
                AND EXISTS (SELECT 1 FROM pos_terminal t JOIN pos_shift s ON s.terminal_id = t.id
                    JOIN pos_cashier c ON c.id = s.cashier_id
                    WHERE t.id = :t AND t.branch_id = o.branch_id AND t.merchant_id = :m
                      AND t.status <> 'REVOKED' AND c.merchant_id = :m AND c.is_active AND s.cashier_id = :c AND s.status = 'OPEN')
                """).param("t", req.terminalId()).param("c", req.cashierId()).param("o", order)
                .param("m", merchant).update();
        if (changed != 1) throw ApiException.conflict("cashier_claim_failed", "An active shift in the order branch is required; another cashier may have claimed this order");
        return orders.getOrder(order).orElseThrow();
    }

    @GetMapping("/{merchant}/bill-requests")
    public List<UUID> waiterBillRequests(@PathVariable UUID merchant) {
        var caller = access.operateAccess(merchant);
        return db.sql("""
                SELECT w.order_id FROM restaurant_waiter_order w JOIN pos_order o ON o.id = w.order_id
                WHERE w.merchant_id = :m AND w.bill_requested_at IS NOT NULL AND o.status IN ('PENDING', 'PARTIAL')
                AND (CAST(:b AS uuid) IS NULL OR o.branch_id = :b)
                """).param("m", merchant).param("b", caller.branchId()).query(UUID.class).list();
    }
}
