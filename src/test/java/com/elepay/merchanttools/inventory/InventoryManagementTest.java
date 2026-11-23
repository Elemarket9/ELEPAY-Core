package com.elepay.merchanttools.inventory;

import com.elepay.PostgresTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

public class InventoryManagementTest extends PostgresTestBase {

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private ProductCatalogService catalogService;

    @Autowired
    private StockService stockService;

    @Autowired
    private BatchTrackingService batchService;

    @Autowired
    private StockTransferService transferService;

    @Autowired
    private PurchaseOrderService poService;

    @Autowired
    private JdbcClient db;

    private UUID merchantId;

    @BeforeEach
    void setup() {
        merchantId = UUID.randomUUID();
        // Insert dummy merchant in merchant table if needed
        db.sql("""
            INSERT INTO merchant (
                id, legal_name, trading_name, kind, status, contact_phone,
                merchant_code, fee_bps, fee_fixed, settlement_delay_hours, created_by
            ) VALUES (
                :id, 'Test Retailer Ltd', 'Test Mart', 'merchant', 'active', '0241002003',
                :code, 0, 0, 24, 'system'
            ) ON CONFLICT DO NOTHING
            """)
            .param("id", merchantId)
            .param("code", String.format("%06d", (int)(Math.random() * 900000 + 100000)))
            .update();
    }

    @Test
    @DisplayName("Domain 3: Multi-location warehouses, product variants & scanner lookup")
    void testWarehousesAndProductCatalog() {
        // 1. Warehouse creation
        var warehouse1 = warehouseService.createWarehouse(merchantId, null, "WH-ACCRA", "Accra Central Hub", "High Street, Accra");
        var warehouse2 = warehouseService.createWarehouse(merchantId, null, "WH-KUMASI", "Kumasi Branch Depo", "Adum, Kumasi");

        assertThat(warehouse1.id()).isNotNull();
        assertThat(warehouse2.id()).isNotNull();

        var warehouses = warehouseService.listWarehouses(merchantId);
        assertThat(warehouses).hasSizeGreaterThanOrEqualTo(2);

        // 2. Product and Variants
        var category = catalogService.createCategory(merchantId, "Beverages");
        var product = catalogService.createProduct(merchantId, category.id(), "Malted Drink", "Rich malted drink", "EleBrew");

        var variantSmall = catalogService.createVariant(
                merchantId, product.id(), "MAL-330ML", "5012345678901",
                "Can 330ml", "{\"size\":\"330ml\"}", 450L, 700L, true
        );
        var variantLarge = catalogService.createVariant(
                merchantId, product.id(), "MAL-500ML", "5012345678902",
                "Bottle 500ml", "{\"size\":\"500ml\"}", 650L, 1000L, true
        );

        // 3. Fast Barcode & SKU Scanner Lookups
        var scanBarcode = catalogService.lookupByBarcode(merchantId, "5012345678901");
        assertThat(scanBarcode).isPresent();
        assertThat(scanBarcode.get().sku()).isEqualTo("MAL-330ML");
        assertThat(scanBarcode.get().productName()).isEqualTo("Malted Drink");
        assertThat(scanBarcode.get().retailPricePesewas()).isEqualTo(700L);

        var scanSku = catalogService.lookupBySku(merchantId, "MAL-500ML");
        assertThat(scanSku).isPresent();
        assertThat(scanSku.get().barcode()).isEqualTo("5012345678902");
    }

    @Test
    @DisplayName("Domain 3: Real-time stock levels, reservations, adjustments, and low-stock alerts")
    void testStockLevelsAndAlerts() {
        var warehouse = warehouseService.createWarehouse(merchantId, null, "WH-EAST", "East Legon Depo", "Lagos Ave");
        var category = catalogService.createCategory(merchantId, "Groceries");
        var product = catalogService.createProduct(merchantId, category.id(), "Basmati Rice 5kg", "Premium grain", "Royal");
        var variant = catalogService.createVariant(
                merchantId, product.id(), "RICE-5KG", "6001234567890",
                "5kg Bag", "{}", 8000L, 12000L, true
        );

        // 1. Initial stock adjustment (received 20 bags)
        var stock = stockService.adjustStock(
                warehouse.id(), variant.id(), BigDecimal.valueOf(20),
                MovementType.RECEIVED, UUID.randomUUID(), "Initial inventory delivery"
        );
        assertThat(stock.quantityOnHand()).isEqualByComparingTo("20");
        assertThat(stock.quantityAvailable()).isEqualByComparingTo("20");

        // 2. Reserve stock (3 bags reserved for customer checkout)
        var reserved = stockService.reserveStock(warehouse.id(), variant.id(), BigDecimal.valueOf(3));
        assertThat(reserved.quantityOnHand()).isEqualByComparingTo("20");
        assertThat(reserved.quantityReserved()).isEqualByComparingTo("3");
        assertThat(reserved.quantityAvailable()).isEqualByComparingTo("17");

        // 3. Deduct stock upon sale completion
        var deducted = stockService.deductStock(
                warehouse.id(), variant.id(), BigDecimal.valueOf(15),
                MovementType.SALE_DEDUCTION, UUID.randomUUID(), "Sold 15 bags"
        );
        // On hand is now 20 - 15 = 5 bags
        assertThat(deducted.quantityOnHand()).isEqualByComparingTo("5");

        // 4. Low-stock alert check (reorder point default is 10 bags, on hand is 5)
        var alerts = stockService.listLowStockAlerts(merchantId);
        assertThat(alerts).isNotEmpty();
        var alert = alerts.stream().filter(a -> a.variantId().equals(variant.id())).findFirst();
        assertThat(alert).isPresent();
        assertThat(alert.get().severity()).isEqualTo("WARNING");
        assertThat(alert.get().quantityOnHand()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("Domain 3: Batch and lot tracking with FEFO expiry deduction")
    void testBatchTrackingAndFefo() {
        var warehouse = warehouseService.createWarehouse(merchantId, null, "WH-DAIRY", "Dairy Cold Storage", "Tema Port");
        var category = catalogService.createCategory(merchantId, "Dairy");
        var product = catalogService.createProduct(merchantId, category.id(), "Fresh Milk 1L", "Pasteurized", "Cowbell");
        var variant = catalogService.createVariant(
                merchantId, product.id(), "MILK-1L", "7001234567890",
                "1 Liter", "{}", 1200L, 1800L, true
        );

        // Batch 1 expiring in 5 days
        var batch1 = batchService.createBatch(
                warehouse.id(), variant.id(), "LOT-2026-001",
                LocalDate.now().plusDays(5), BigDecimal.valueOf(10), 1200L
        );
        // Batch 2 expiring in 20 days
        var batch2 = batchService.createBatch(
                warehouse.id(), variant.id(), "LOT-2026-002",
                LocalDate.now().plusDays(20), BigDecimal.valueOf(25), 1200L
        );

        var activeBatches = batchService.listBatches(warehouse.id(), variant.id());
        assertThat(activeBatches).hasSize(2);
        assertThat(activeBatches.get(0).batchNumber()).isEqualTo("LOT-2026-001");

        // Deduct 12 units using FEFO: should consume all 10 from batch1 and 2 from batch2
        BigDecimal deducted = batchService.deductFromBatches(warehouse.id(), variant.id(), BigDecimal.valueOf(12));
        assertThat(deducted).isEqualByComparingTo("12");

        var updatedB1 = batchService.getBatch(batch1.id()).orElseThrow();
        var updatedB2 = batchService.getBatch(batch2.id()).orElseThrow();

        assertThat(updatedB1.quantity()).isEqualByComparingTo("0");
        assertThat(updatedB2.quantity()).isEqualByComparingTo("23");
    }

    @Test
    @DisplayName("Domain 3: Inter-warehouse stock transfers and Purchase Orders (PO)")
    void testStockTransfersAndPurchaseOrders() {
        var whSource = warehouseService.createWarehouse(merchantId, null, "WH-SRC", "Main Distribution", "Spintex");
        var whDest = warehouseService.createWarehouse(merchantId, null, "WH-DST", "Osu Retail Store", "Oxford St");

        var category = catalogService.createCategory(merchantId, "Electronics");
        var product = catalogService.createProduct(merchantId, category.id(), "USB-C Cable", "Fast charging", "Anker");
        var variant = catalogService.createVariant(
                merchantId, product.id(), "CABLE-USBC", "8001234567890",
                "2 Meter Cable", "{}", 2000L, 4500L, true
        );

        // Initial stock at source: 50 units
        stockService.adjustStock(whSource.id(), variant.id(), BigDecimal.valueOf(50), MovementType.RECEIVED, null, "Initial");

        // 1. Create Stock Transfer: send 15 units from whSource to whDest
        var transfer = transferService.createTransferOrder(
                merchantId, whSource.id(), whDest.id(),
                List.of(new StockTransferService.TransferItemInput(variant.id(), BigDecimal.valueOf(15))),
                "TRACK-GH-001", "Restock Osu branch"
        );
        assertThat(transfer.status()).isEqualTo("REQUESTED");

        // Dispatch transfer
        var dispatched = transferService.dispatchTransferOrder(transfer.id());
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");
        var srcStockAfterDispatch = stockService.getOrCreateStock(whSource.id(), variant.id());
        assertThat(srcStockAfterDispatch.quantityOnHand()).isEqualByComparingTo("35");

        // Receive transfer
        var received = transferService.receiveTransferOrder(transfer.id(), Map.of(variant.id(), BigDecimal.valueOf(15)));
        assertThat(received.status()).isEqualTo("RECEIVED");
        var dstStockAfterReceive = stockService.getOrCreateStock(whDest.id(), variant.id());
        assertThat(dstStockAfterReceive.quantityOnHand()).isEqualByComparingTo("15");

        // 2. Purchase Order lifecycle
        var supplier = poService.createSupplier(merchantId, "Global Tech Distributors", "orders@globaltech.com", "0302001122", "Accra North");
        var po = poService.createPurchaseOrder(
                merchantId, supplier.id(), whSource.id(), "PO-" + UUID.randomUUID().toString().substring(0, 8),
                List.of(new PurchaseOrderService.PoItemInput(variant.id(), 1800L, BigDecimal.valueOf(100)))
        );
        assertThat(po.status()).isEqualTo("DRAFT");
        assertThat(po.totalCostPesewas()).isEqualTo(180000L);

        poService.issuePurchaseOrder(po.id());
        var issuedPo = poService.getPurchaseOrder(po.id()).orElseThrow();
        assertThat(issuedPo.status()).isEqualTo("ISSUED");

        // Receive full PO shipment
        var completedPo = poService.receivePoItems(po.id(), Map.of(variant.id(), BigDecimal.valueOf(100)));
        assertThat(completedPo.status()).isEqualTo("COMPLETED");

        var finalWhStock = stockService.getOrCreateStock(whSource.id(), variant.id());
        // 35 + 100 = 135 units
        assertThat(finalWhStock.quantityOnHand()).isEqualByComparingTo("135");

        var updatedVariant = catalogService.getVariant(variant.id()).orElseThrow();
        assertThat(updatedVariant.costPricePesewas()).isEqualTo(1800L);
    }
}
