package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.merchanttools.inventory.BatchTrackingService;
import com.elepay.merchanttools.inventory.MovementType;
import com.elepay.merchanttools.inventory.ProductCatalogService;
import com.elepay.merchanttools.inventory.PurchaseOrderService;
import com.elepay.merchanttools.inventory.StockService;
import com.elepay.merchanttools.inventory.StockTransferService;
import com.elepay.merchanttools.inventory.WarehouseService;
import com.elepay.merchanttools.pos.PosOrderService;
import com.elepay.merchanttools.restaurant.DiningMode;
import com.elepay.merchanttools.restaurant.KdsPrintService;
import com.elepay.merchanttools.restaurant.KdsService;
import com.elepay.merchanttools.restaurant.KitchenStationService;
import com.elepay.merchanttools.restaurant.RestaurantTableService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Regression suite for Track 6 (Inventory & Restaurant) adversarial findings:
 * 1. CRIT-09: PO fractional cost precision, StockService adjustStock/reserveStock, BatchTrackingService createBatch
 * 2. CRIT-12: Stock transfer receive 0 rejected so dispatched stock is never lost in transit
 * 3. MED: InventoryController items: null returns 400 instead of 500 NPE
 * 4. HIGH: Restaurant branch isolation on tables (status/seat/vacate) and KDS print jobs (claim/result)
 * 5. MED: KDS ticket creation rejects cross-order orderItemId
 * 6. LOW/MED: Reject non-positive table capacity and negative/inverted modifier group bounds
 */
class AdversarialInventoryRestaurantRegressionTest extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired WarehouseService warehouseService;
    @Autowired ProductCatalogService catalogService;
    @Autowired StockService stockService;
    @Autowired BatchTrackingService batchService;
    @Autowired StockTransferService transferService;
    @Autowired PurchaseOrderService poService;
    @Autowired PosOrderService posOrderService;
    @Autowired RestaurantTableService tableService;
    @Autowired KitchenStationService stationService;
    @Autowired KdsService kdsService;
    @Autowired KdsPrintService printService;

    private TestApi api;
    private String ownerToken;
    private String opsToken;
    private String branchACashierToken;
    private String branchBCashierToken;
    private UUID merchantId;
    private UUID branchAId;
    private UUID branchBId;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        opsToken = api.token("ops-inv-" + suffix, "ops");
        ownerToken = api.token("owner-inv-" + suffix);
        String cashierASubject = "cashier-a-" + suffix;
        String cashierBSubject = "cashier-b-" + suffix;
        branchACashierToken = api.token(cashierASubject);
        branchBCashierToken = api.token(cashierBSubject);

        var created = api.call("POST", "/v1/merchants", ownerToken, null, Map.of(
                "legalName", "Adversarial Grill " + suffix,
                "tradingName", "Adversarial Grill " + suffix,
                "kind", "merchant",
                "contactPhone", "0241234567"
        ));
        assertThat(created.status()).isEqualTo(200);
        merchantId = UUID.fromString(created.body().get("id").asString());

        var approved = api.call("POST", "/v1/ops/merchants/" + merchantId + "/review", opsToken, null,
                Map.of("decision", "approve"));
        assertThat(approved.status()).isEqualTo(200);

        branchAId = UUID.fromString(api.call("POST", "/v1/merchants/" + merchantId + "/branches", ownerToken, null,
                Map.of("name", "Branch A")).body().get("id").asString());
        branchBId = UUID.fromString(api.call("POST", "/v1/merchants/" + merchantId + "/branches", ownerToken, null,
                Map.of("name", "Branch B")).body().get("id").asString());

        assertThat(api.call("PUT", "/v1/merchants/" + merchantId + "/members/" + cashierASubject, ownerToken, null,
                Map.of("role", "cashier", "branchId", branchAId.toString())).status()).isEqualTo(200);
        assertThat(api.call("PUT", "/v1/merchants/" + merchantId + "/members/" + cashierBSubject, ownerToken, null,
                Map.of("role", "cashier", "branchId", branchBId.toString())).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("CRIT-09: PO fractional quantity computes exact rounded cost, and stock/batch/reserve reject invalid quantities")
    void inventoryQuantityAndFractionalPoValidation() {
        var wh = warehouseService.createWarehouse(merchantId, branchAId, "WH-C9-" + UUID.randomUUID().toString().substring(0, 6), "Main WH", "Accra");
        var cat = catalogService.createCategory(merchantId, "Grains");
        var prod = catalogService.createProduct(merchantId, cat.id(), "Rice", "Long grain", "EleFarm");
        var variant = catalogService.createVariant(merchantId, prod.id(), "RICE-" + UUID.randomUUID().toString().substring(0, 6),
                null, "Bulk kg", "{}", 501L, 800L, true);
        var supplier = poService.createSupplier(merchantId, "Accra Mills", "mills@example.com", "0240000001", "Tema");

        // Fractional PO 0.5 @ 501p => 250.5 -> HALF_UP => 251p (not truncated to 0)
        var po = poService.createPurchaseOrder(merchantId, supplier.id(), wh.id(), "PO-FRAC-1",
                List.of(new PurchaseOrderService.PoItemInput(variant.id(), 501L, new BigDecimal("0.5"))));
        assertThat(po.totalCostPesewas()).isEqualTo(251L);

        // Overflowing PO cost throws IllegalArgumentException (HTTP 400)
        assertThatThrownBy(() -> poService.createPurchaseOrder(merchantId, supplier.id(), wh.id(), "PO-OVERFLOW",
                List.of(new PurchaseOrderService.PoItemInput(variant.id(), Long.MAX_VALUE, BigDecimal.TEN))))
                .isInstanceOf(IllegalArgumentException.class);

        // adjustStock rejects qty == 0 and negative inbound RECEIVED / TRANSFER_IN
        var zeroAdjust = api.call("POST", "/v1/inventory/stock/adjust", ownerToken, null, Map.of(
                "warehouseId", wh.id().toString(),
                "variantId", variant.id().toString(),
                "quantityChange", 0,
                "movementType", "ADJUSTMENT",
                "notes", "zero"
        ));
        assertThat(zeroAdjust.status()).isEqualTo(400);
        assertThat(zeroAdjust.body().get("code").asString()).isEqualTo("invalid_argument");

        var negReceived = api.call("POST", "/v1/inventory/stock/adjust", ownerToken, null, Map.of(
                "warehouseId", wh.id().toString(),
                "variantId", variant.id().toString(),
                "quantityChange", -1,
                "movementType", "RECEIVED",
                "notes", "neg inbound"
        ));
        assertThat(negReceived.status()).isEqualTo(400);
        assertThat(negReceived.body().get("code").asString()).isEqualTo("invalid_argument");

        var negTransferIn = api.call("POST", "/v1/inventory/stock/adjust", ownerToken, null, Map.of(
                "warehouseId", wh.id().toString(),
                "variantId", variant.id().toString(),
                "quantityChange", -2,
                "movementType", "TRANSFER_IN",
                "notes", "neg transfer in"
        ));
        assertThat(negTransferIn.status()).isEqualTo(400);
        assertThat(negTransferIn.body().get("code").asString()).isEqualTo("invalid_argument");

        // Positive RECEIVED succeeds
        var posReceived = api.call("POST", "/v1/inventory/stock/adjust", ownerToken, null, Map.of(
                "warehouseId", wh.id().toString(),
                "variantId", variant.id().toString(),
                "quantityChange", 10,
                "movementType", "RECEIVED",
                "notes", "initial"
        ));
        assertThat(posReceived.status()).isEqualTo(200);

        // createBatch rejects qty <= 0
        var zeroBatch = api.call("POST", "/v1/inventory/batches", ownerToken, null, Map.of(
                "warehouseId", wh.id().toString(),
                "variantId", variant.id().toString(),
                "batchNumber", "LOT-ZERO",
                "quantity", 0,
                "costPricePesewas", 500
        ));
        assertThat(zeroBatch.status()).isEqualTo(400);
        assertThat(zeroBatch.body().get("code").asString()).isEqualTo("invalid_argument");

        var negBatch = api.call("POST", "/v1/inventory/batches", ownerToken, null, Map.of(
                "warehouseId", wh.id().toString(),
                "variantId", variant.id().toString(),
                "batchNumber", "LOT-NEG",
                "quantity", -5,
                "costPricePesewas", 500
        ));
        assertThat(negBatch.status()).isEqualTo(400);
        assertThat(negBatch.body().get("code").asString()).isEqualTo("invalid_argument");

        // reserveStock rejects null, 0, and negative quantities
        assertThatThrownBy(() -> stockService.reserveStock(wh.id(), variant.id(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockService.reserveStock(wh.id(), variant.id(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockService.reserveStock(wh.id(), variant.id(), BigDecimal.valueOf(-3)))
                .isInstanceOf(IllegalArgumentException.class);

        var reserved = stockService.reserveStock(wh.id(), variant.id(), BigDecimal.valueOf(3));
        assertThat(reserved.quantityReserved()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("CRIT-12: Transfer receive with 0 quantity is rejected with 400 so dispatched stock is not lost")
    void transferReceiveZeroRejected() {
        var wh1 = warehouseService.createWarehouse(merchantId, branchAId, "WH1-" + UUID.randomUUID().toString().substring(0, 6), "WH1", "Accra");
        var wh2 = warehouseService.createWarehouse(merchantId, branchBId, "WH2-" + UUID.randomUUID().toString().substring(0, 6), "WH2", "Kumasi");
        var cat = catalogService.createCategory(merchantId, "Drinks");
        var prod = catalogService.createProduct(merchantId, cat.id(), "Water", "Bottled", "Voltic");
        var variant = catalogService.createVariant(merchantId, prod.id(), "WAT-" + UUID.randomUUID().toString().substring(0, 6),
                null, "500ml", "{}", 200L, 400L, true);

        stockService.adjustStock(wh1.id(), variant.id(), BigDecimal.valueOf(10), MovementType.RECEIVED, null, "seed");
        var transfer = transferService.createTransferOrder(merchantId, wh1.id(), wh2.id(),
                List.of(new StockTransferService.TransferItemInput(variant.id(), BigDecimal.valueOf(5))),
                "TRK-01", "dispatch 5");
        transferService.dispatchTransferOrder(transfer.id());

        var zeroReceive = api.call("POST", "/v1/inventory/transfers/" + transfer.id() + "/receive", ownerToken, null,
                Map.of(variant.id().toString(), 0));
        assertThat(zeroReceive.status()).isEqualTo(400);
        assertThat(zeroReceive.body().get("code").asString()).isEqualTo("invalid_argument");

        // Transfer must still be DISPATCHED and can be properly received afterwards
        assertThat(transferService.getTransferOrder(transfer.id()).orElseThrow().status()).isEqualTo("DISPATCHED");

        var validReceive = api.call("POST", "/v1/inventory/transfers/" + transfer.id() + "/receive", ownerToken, null,
                Map.of(variant.id().toString(), 5));
        assertThat(validReceive.status()).isEqualTo(200);
        assertThat(validReceive.body().get("status").asString()).isEqualTo("RECEIVED");
        assertThat(stockService.getOrCreateStock(wh2.id(), variant.id()).quantityOnHand()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("MED: InventoryController returns 400 invalid_argument (not 500 NPE) when items is null on transfer or PO")
    void inventoryControllerNullItemsReturns400() {
        var wh1 = warehouseService.createWarehouse(merchantId, branchAId, "WHN1-" + UUID.randomUUID().toString().substring(0, 6), "WHN1", "Accra");
        var wh2 = warehouseService.createWarehouse(merchantId, branchBId, "WHN2-" + UUID.randomUUID().toString().substring(0, 6), "WHN2", "Kumasi");
        var supplier = poService.createSupplier(merchantId, "Null Test Supplier", "s@example.com", "0240000002", "Accra");

        Map<String, Object> transferReq = new HashMap<>();
        transferReq.put("merchantId", merchantId.toString());
        transferReq.put("sourceWarehouseId", wh1.id().toString());
        transferReq.put("destWarehouseId", wh2.id().toString());
        transferReq.put("items", null);
        transferReq.put("trackingNumber", "TRK-NULL");
        transferReq.put("notes", "null items");

        var transferResp = api.call("POST", "/v1/inventory/transfers", ownerToken, null, transferReq);
        assertThat(transferResp.status()).isEqualTo(400);
        assertThat(transferResp.body().get("code").asString()).isEqualTo("invalid_argument");

        Map<String, Object> poReq = new HashMap<>();
        poReq.put("merchantId", merchantId.toString());
        poReq.put("supplierId", supplier.id().toString());
        poReq.put("warehouseId", wh1.id().toString());
        poReq.put("poNumber", "PO-NULL");
        poReq.put("items", null);

        var poResp = api.call("POST", "/v1/inventory/purchase-orders", ownerToken, null, poReq);
        assertThat(poResp.status()).isEqualTo(400);
        assertThat(poResp.body().get("code").asString()).isEqualTo("invalid_argument");
    }

    @Test
    @DisplayName("HIGH: Branch B cashier cannot mutate Branch A tables or claim/complete Branch A KDS print jobs")
    void restaurantBranchIsolationOnTablesAndPrintJobs() {
        var floor = tableService.createFloorPlan(merchantId, "Main Hall", "{}");
        var tableA = tableService.createTable(merchantId, floor.id(), "T-A1", 4);

        // Assign Table T-A1 to Branch A
        var assignResp = api.call("PUT", "/v1/restaurant/waiter/" + merchantId + "/tables/" + tableA.id() + "/branch",
                ownerToken, null, Map.of("branchId", branchAId.toString()));
        assertThat(assignResp.status()).isEqualTo(200);

        // Create POS order in Branch A
        var orderA = posOrderService.createOrder(merchantId, branchAId, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Jollof", BigDecimal.ONE, 5000L, 0L, 0L, "[]", null, "MAIN", null)),
                0L, 0L, 0L, "RESTAURANT");
        var orderAItem = posOrderService.listOrderItems(orderA.id()).get(0);

        // Branch B cashier MUST be blocked from mutating Branch A table
        assertThat(api.call("POST", "/v1/restaurant/tables/" + tableA.id() + "/status?status=OCCUPIED",
                branchBCashierToken, null, null).status()).isEqualTo(404);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + tableA.id() + "/seat?orderId=" + orderA.id(),
                branchBCashierToken, null, null).status()).isEqualTo(404);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + tableA.id() + "/vacate?markDirty=true",
                branchBCashierToken, null, null).status()).isEqualTo(404);

        // Branch A cashier CAN seat and update Branch A table
        assertThat(api.call("POST", "/v1/restaurant/tables/" + tableA.id() + "/seat?orderId=" + orderA.id(),
                branchACashierToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/restaurant/tables/" + tableA.id() + "/status?status=OCCUPIED",
                branchACashierToken, null, null).status()).isEqualTo(200);

        // Create a Branch A printer station & ticket so a KDS print job is enqueued for Branch A
        stationService.create(merchantId, new KitchenStationService.StationInput(
                "Branch A Grill", branchAId, true, KitchenStationService.Output.PRINTER, "tcp://192.168.1.50:9100", 42));
        kdsService.createTicket(merchantId, orderA.id(), tableA.id(), DiningMode.DINE_IN, "Ama", "MAIN",
                List.of(new KdsService.KdsItemInput(orderAItem.id(), "Jollof", BigDecimal.ONE, null, "MAIN")),
                null, "ref-" + UUID.randomUUID());

        // Branch B cashier MUST NOT claim Branch A's print job (receives 204 No Content)
        var branchBClaim = api.call("POST",
                "/v1/restaurant/kds/print-jobs/claim?merchantId=" + merchantId + "&deviceId=branch-b-device",
                branchBCashierToken, null, null);
        assertThat(branchBClaim.status()).isEqualTo(204);

        // Branch A cashier claims Branch A's print job
        var branchAClaim = api.call("POST",
                "/v1/restaurant/kds/print-jobs/claim?merchantId=" + merchantId + "&deviceId=branch-a-device",
                branchACashierToken, null, null);
        assertThat(branchAClaim.status()).isEqualTo(200);
        UUID jobId = UUID.fromString(branchAClaim.body().get("id").asString());

        // Branch B cashier MUST NOT complete/report Branch A's print job (receives 403 Forbidden)
        var branchBReport = api.call("POST", "/v1/restaurant/kds/print-jobs/" + jobId + "/result",
                branchBCashierToken, null, Map.of("deviceId", "branch-a-device", "printed", true));
        assertThat(branchBReport.status()).isEqualTo(403);

        // Branch A cashier CAN complete Branch A's print job
        var branchAReport = api.call("POST", "/v1/restaurant/kds/print-jobs/" + jobId + "/result",
                branchACashierToken, null, Map.of("deviceId", "branch-a-device", "printed", true));
        assertThat(branchAReport.status()).isEqualTo(200);
        assertThat(branchAReport.body().get("status").asString()).isEqualTo("PRINTED");
    }

    @Test
    @DisplayName("MED: KDS ticket creation rejects cross-order orderItemId")
    void kdsRejectsCrossOrderOrderItemId() {
        var order1 = posOrderService.createOrder(merchantId, branchAId, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Waakye", BigDecimal.ONE, 3000L, 0L, 0L, "[]", null, "MAIN", null)),
                0L, 0L, 0L, "RESTAURANT");
        var order2 = posOrderService.createOrder(merchantId, branchAId, null, null, null,
                List.of(new PosOrderService.OrderItemInput(null, "Banku", BigDecimal.ONE, 4000L, 0L, 0L, "[]", null, "MAIN", null)),
                0L, 0L, 0L, "RESTAURANT");
        var order1Item = posOrderService.listOrderItems(order1.id()).get(0);
        var order2Item = posOrderService.listOrderItems(order2.id()).get(0);

        // Mixing order1 with order2Item must be rejected with 400
        var crossOrderResp = api.call("POST", "/v1/restaurant/kds/tickets", ownerToken, null, Map.of(
                "merchantId", merchantId.toString(),
                "orderId", order1.id().toString(),
                "diningMode", "DINE_IN",
                "serverName", "Kofi",
                "course", "MAIN",
                "items", List.of(Map.of(
                        "orderItemId", order2Item.id().toString(),
                        "itemName", "Banku",
                        "quantity", 1,
                        "course", "MAIN"
                ))
        ));
        assertThat(crossOrderResp.status()).isEqualTo(400);
        assertThat(crossOrderResp.body().get("code").asString()).isEqualTo("order_item_mismatch");

        // Matching order1 with order1Item succeeds
        var validResp = api.call("POST", "/v1/restaurant/kds/tickets", ownerToken, null, Map.of(
                "merchantId", merchantId.toString(),
                "orderId", order1.id().toString(),
                "diningMode", "DINE_IN",
                "serverName", "Kofi",
                "course", "MAIN",
                "items", List.of(Map.of(
                        "orderItemId", order1Item.id().toString(),
                        "itemName", "Waakye",
                        "quantity", 1,
                        "course", "MAIN"
                ))
        ));
        assertThat(validResp.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("LOW/MED: Reject non-positive table capacity and negative or inverted modifier bounds")
    void rejectNegativeTableCapacityAndInvertedModifierBounds() {
        var floor = tableService.createFloorPlan(merchantId, "Patio", "{}");

        var negCapacity = api.call("POST", "/v1/restaurant/tables", ownerToken, null, Map.of(
                "merchantId", merchantId.toString(),
                "sectionId", floor.id().toString(),
                "tableNumber", "T-NEG",
                "capacity", -5
        ));
        assertThat(negCapacity.status()).isEqualTo(400);
        assertThat(negCapacity.body().get("code").asString()).isEqualTo("invalid_argument");

        var zeroCapacity = api.call("POST", "/v1/restaurant/tables", ownerToken, null, Map.of(
                "merchantId", merchantId.toString(),
                "sectionId", floor.id().toString(),
                "tableNumber", "T-ZERO",
                "capacity", 0
        ));
        assertThat(zeroCapacity.status()).isEqualTo(400);
        assertThat(zeroCapacity.body().get("code").asString()).isEqualTo("invalid_argument");

        // Negative minSelection
        var negMin = api.call("POST", "/v1/restaurant/modifiers/groups", ownerToken, null, Map.of(
                "merchantId", merchantId.toString(),
                "name", "Neg Min",
                "minSelection", -1,
                "maxSelection", 2,
                "isRequired", false
        ));
        assertThat(negMin.status()).isEqualTo(400);
        assertThat(negMin.body().get("code").asString()).isEqualTo("invalid_argument");

        // Negative maxSelection
        var negMax = api.call("POST", "/v1/restaurant/modifiers/groups", ownerToken, null, Map.of(
                "merchantId", merchantId.toString(),
                "name", "Neg Max",
                "minSelection", 0,
                "maxSelection", -1,
                "isRequired", false
        ));
        assertThat(negMax.status()).isEqualTo(400);
        assertThat(negMax.body().get("code").asString()).isEqualTo("invalid_argument");

        // Inverted bounds (minSelection=5 > maxSelection=1)
        var inverted = api.call("POST", "/v1/restaurant/modifiers/groups", ownerToken, null, Map.of(
                "merchantId", merchantId.toString(),
                "name", "Inverted",
                "minSelection", 5,
                "maxSelection", 1,
                "isRequired", true
        ));
        assertThat(inverted.status()).isEqualTo(400);
        assertThat(inverted.body().get("code").asString()).isEqualTo("invalid_argument");

        // Valid bounds (maxSelection == 0 unlimited, and min <= max) succeed
        var unlimitedMax = api.call("POST", "/v1/restaurant/modifiers/groups", ownerToken, null, Map.of(
                "merchantId", merchantId.toString(),
                "name", "Extras",
                "minSelection", 1,
                "maxSelection", 0,
                "isRequired", true
        ));
        assertThat(unlimitedMax.status()).isEqualTo(200);

        var validRange = api.call("POST", "/v1/restaurant/modifiers/groups", ownerToken, null, Map.of(
                "merchantId", merchantId.toString(),
                "name", "Toppings",
                "minSelection", 1,
                "maxSelection", 3,
                "isRequired", true
        ));
        assertThat(validRange.status()).isEqualTo(200);
    }
}
