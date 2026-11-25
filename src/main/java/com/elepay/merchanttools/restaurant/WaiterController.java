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
    private final WaiterDeviceService devices;
    private final RestaurantSettingsService settings;

    public WaiterController(JdbcClient db, MerchantAccess merchants, MerchantToolAccess access,
            ProductCatalogService catalog, PosOrderService orders, KdsService kitchen, Idempotency idempotency,
            WaiterDeviceService devices, RestaurantSettingsService settings) {
        this.db = db; this.merchants = merchants; this.access = access; this.catalog = catalog;
        this.orders = orders; this.kitchen = kitchen; this.idempotency = idempotency;
        this.devices = devices; this.settings = settings;
    }

    public record WaiterTable(UUID id, String tableNumber, int capacity, String status, UUID currentOrderId) {}
    public record WaiterMenuItem(UUID variantId, String name, String category, long pricePesewas) {}
    public record WaiterLine(UUID variantId, int quantity, String notes) {}
    public record WaiterOrderRequest(UUID tableId, List<WaiterLine> items, String notes) {}
    public record WaiterOrderResult(UUID orderId, UUID ticketId, String orderNumber, long totalPesewas) {}
    /**
     * One live order at the waiter's branch. Every waiter on the branch sees every order so a shift handover or
     * a colleague covering a table works; {@code takenBy}/{@code mine} show who took it.
     */
    public record WaiterOrderView(UUID orderId, UUID ticketId, String tableNumber, String orderNumber,
            String orderStatus, String kitchenStatus, long subtotalPesewas, long taxPesewas, long totalPesewas,
            boolean billRequested, String takenBy, boolean mine) {}
    public record WaiterBranchAssignment(UUID branchId) {}
    public record WaiterMe(UUID merchantId, String merchantName, UUID branchId, String waiter, int taxBasisPoints) {}

    /**
     * The waiter a request acts for. Either the waiter's own sign-in (role waiter with a branch), or a paired
     * waiter phone, which acts for the waiter it was paired to and stops working as soon as that person is no
     * longer a branch waiter here or the device is revoked.
     */
    private WaiterDeviceService.Waiter waiter(UUID merchant) {
        var actor = com.elepay.identity.Actor.current();
        WaiterDeviceService.Waiter waiter;
        if (actor.isApiKey()) {
            if (!actor.isWaiterDeviceKey() || !merchant.equals(actor.apiKeyMerchant()))
                throw ApiException.forbidden("A branch-assigned waiter account is required");
            waiter = devices.resolve(merchant, actor.apiKeyCredentialId())
                    .orElseThrow(() -> ApiException.forbidden("This phone is no longer paired to a waiter; ask a manager to pair it again"));
            var m = merchants.find(merchant).orElseThrow(() -> ApiException.notFound("Merchant not found"));
            if (!"active".equals(m.status())) throw ApiException.conflict("merchant_not_active", "The merchant account is " + m.status());
        } else {
            var caller = merchants.require(merchant, MerchantAccess.Permission.RESTAURANT_ORDER);
            if (!"waiter".equals(caller.role()) || caller.branchId() == null)
                throw ApiException.forbidden("A branch-assigned waiter account is required");
            waiter = new WaiterDeviceService.Waiter(actor.subject(), caller.branchId());
        }
        access.branch(merchant, waiter.branchId());
        return waiter;
    }

    @GetMapping("/{merchant}/me")
    public WaiterMe waiterMe(@PathVariable UUID merchant) {
        var caller = waiter(merchant);
        var m = merchants.find(merchant).orElseThrow(() -> ApiException.notFound("Merchant not found"));
        return new WaiterMe(merchant, m.tradingName(), caller.branchId(), caller.subject(), settings.taxBasisPoints(merchant));
    }

    @PutMapping("/{merchant}/tables/{table}/branch")
    @Transactional
    public Map<String, String> waiterAssignTable(@PathVariable UUID merchant, @PathVariable UUID table,
            @RequestBody WaiterBranchAssignment assignment) {
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
    public List<WaiterTable> waiterTables(@PathVariable UUID merchant) {
        var caller = waiter(merchant);
        return db.sql("""
                SELECT id, table_number, capacity, status, current_order_id FROM restaurant_table
                WHERE merchant_id = :m AND waiter_branch_id = :b ORDER BY table_number
                """).param("m", merchant).param("b", caller.branchId()).query(WaiterTable.class).list();
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

    @PostMapping("/{merchant}/orders")
    @Transactional
    public WaiterOrderResult waiterSubmitOrder(@PathVariable UUID merchant, @RequestHeader("Idempotency-Key") String key,
            @RequestBody WaiterOrderRequest req) {
        var caller = waiter(merchant);
        if (req.tableId() == null || req.items() == null || req.items().isEmpty() || req.items().size() > 100
                || (req.notes() != null && req.notes().length() > 500))
            throw ApiException.badRequest("invalid_order", "Choose a table and 1 to 100 items; notes max 500 characters");
        return idempotency.execute("waiter:" + merchant + ":" + Idempotency.sha256(caller.subject()),
                key, req, WaiterOrderResult.class, () -> create(merchant, caller, req));
    }

    private WaiterOrderResult create(UUID merchant, WaiterDeviceService.Waiter caller, WaiterOrderRequest req) {
        WaiterTable table = db.sql("""
                SELECT id, table_number, capacity, status, current_order_id FROM restaurant_table
                WHERE id = :t AND merchant_id = :m AND waiter_branch_id = :b FOR UPDATE
                """).param("t", req.tableId()).param("m", merchant).param("b", caller.branchId())
                .query(WaiterTable.class).optional().orElseThrow(() -> ApiException.notFound("Table not found"));
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
        long subtotal = 0;
        for (var item : items) subtotal = Math.addExact(subtotal, Math.multiplyExact(item.unitPricePesewas(), item.quantity().longValueExact()));
        long tax = settings.taxOn(merchant, subtotal);
        var order = orders.createOrder(merchant, caller.branchId(), null, null, null, items, 0, tax, 0, "DINE_IN");
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
                .param("s", caller.subject()).param("k", ticket.id()).update();
        return new WaiterOrderResult(order.id(), ticket.id(), order.orderNumber(), order.totalPesewas());
    }

    @GetMapping("/{merchant}/orders")
    public List<WaiterOrderView> waiterListOrders(@PathVariable UUID merchant) {
        var caller = waiter(merchant);
        return db.sql("""
                SELECT o.id AS order_id, w.ticket_id, t.table_number, o.order_number,
                    o.status AS order_status, k.status AS kitchen_status, o.subtotal_pesewas, o.tax_pesewas,
                    o.total_pesewas, w.bill_requested_at IS NOT NULL AS bill_requested,
                    w.waiter_subject AS taken_by, w.waiter_subject = :s AS mine
                FROM restaurant_waiter_order w JOIN pos_order o ON o.id = w.order_id
                JOIN restaurant_table t ON t.id = w.table_id JOIN restaurant_kds_ticket k ON k.id = w.ticket_id
                WHERE w.merchant_id = :m AND o.branch_id = :b AND t.waiter_branch_id = :b
                  AND t.current_order_id = o.id ORDER BY (w.waiter_subject = :s) DESC, o.created_at DESC
                """).param("m", merchant).param("s", caller.subject()).param("b", caller.branchId())
                .query(WaiterOrderView.class).list();
    }

    /** Any live waiter order at the caller's branch: waiters cover each other's tables. */
    private UUID ownedTicket(UUID merchant, WaiterDeviceService.Waiter caller, UUID order) {
        return db.sql("""
                SELECT w.ticket_id FROM restaurant_waiter_order w JOIN pos_order o ON o.id = w.order_id
                JOIN restaurant_table t ON t.id = w.table_id
                WHERE w.order_id = :o AND w.merchant_id = :m
                    AND o.branch_id = :b AND t.waiter_branch_id = :b AND t.current_order_id = o.id
                """).param("o", order).param("m", merchant)
                .param("b", caller.branchId()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Order not found"));
    }

    @PostMapping("/{merchant}/orders/{order}/served")
    @Transactional
    public void waiterMarkServed(@PathVariable UUID merchant, @PathVariable UUID order, @RequestHeader("Idempotency-Key") String key) {
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
    public void waiterRequestBill(@PathVariable UUID merchant, @PathVariable UUID order) {
        var caller = waiter(merchant);
        ownedTicket(merchant, caller, order);
        db.sql("UPDATE restaurant_waiter_order SET bill_requested_at = COALESCE(bill_requested_at, now()) WHERE order_id = :o")
                .param("o", order).update();
    }

    @PostMapping("/{merchant}/orders/{order}/close-table")
    @Transactional
    public void waiterCloseTable(@PathVariable UUID merchant, @PathVariable UUID order) {
        var caller = waiter(merchant);
        UUID ticket = ownedTicket(merchant, caller, order);
        if (!"SERVED".equals(kitchen.getTicket(ticket).orElseThrow(() -> ApiException.notFound("Ticket not found")).status()))
            throw ApiException.conflict("not_served", "Food must be served before closing the table");
        int changed = db.sql("""
                UPDATE restaurant_table t SET status = 'DIRTY', current_order_id = NULL
                FROM pos_order o WHERE t.current_order_id = o.id AND o.id = :o AND o.status = 'PAID'
                """).param("o", order).update();
        if (changed != 1) throw ApiException.conflict("not_paid", "Cashier payment is required before closing the table");
    }

    // ---- Manager: restaurant tax and waiter phones -------------------------------------------------------

    public record TaxSetting(Integer taxBasisPoints) {}
    public record PairingRequest(String waiterSubject) {}

    @GetMapping("/{merchant}/settings")
    public TaxSetting waiterSettings(@PathVariable UUID merchant) {
        access.manage(merchant);
        return new TaxSetting(settings.taxBasisPoints(merchant));
    }

    @PutMapping("/{merchant}/settings")
    public TaxSetting waiterUpdateSettings(@PathVariable UUID merchant, @RequestBody TaxSetting req) {
        access.manage(merchant);
        if (req == null || req.taxBasisPoints() == null || req.taxBasisPoints() < 0 || req.taxBasisPoints() > 5000)
            throw ApiException.badRequest("invalid_tax", "Tax must be between 0 and 5000 basis points (0% to 50%)");
        settings.setTaxBasisPoints(merchant, req.taxBasisPoints(), com.elepay.identity.Actor.current().subject());
        return new TaxSetting(req.taxBasisPoints());
    }

    @PostMapping("/{merchant}/devices/pairing-codes")
    public WaiterDeviceService.PairingCode waiterPairingCode(@PathVariable UUID merchant, @RequestBody PairingRequest req) {
        access.manage(merchant);
        if (req == null || req.waiterSubject() == null || req.waiterSubject().isBlank())
            throw ApiException.badRequest("waiter_required", "Choose the waiter this phone is for");
        return devices.createCode(merchant, req.waiterSubject().strip(), com.elepay.identity.Actor.current().subject());
    }

    @GetMapping("/{merchant}/devices")
    public List<WaiterDeviceService.Device> waiterDevices(@PathVariable UUID merchant) {
        access.manage(merchant);
        return devices.list(merchant);
    }

    @DeleteMapping("/{merchant}/devices/{device}")
    public Map<String, String> waiterRevokeDevice(@PathVariable UUID merchant, @PathVariable UUID device) {
        access.manage(merchant);
        devices.revoke(merchant, device);
        return Map.of("status", "revoked");
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
