package com.elepay.merchanttools.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/inventory")
public class InventoryController {
    private final WarehouseService warehouseService;
    private final ProductCatalogService catalogService;
    private final StockService stockService;
    private final BatchTrackingService batchService;
    private final StockTransferService transferService;
    private final PurchaseOrderService poService;

    public InventoryController(
            WarehouseService warehouseService,
            ProductCatalogService catalogService,
            StockService stockService,
            BatchTrackingService batchService,
            StockTransferService transferService,
            PurchaseOrderService poService
    ) {
        this.warehouseService = warehouseService;
        this.catalogService = catalogService;
        this.stockService = stockService;
        this.batchService = batchService;
        this.transferService = transferService;
        this.poService = poService;
    }

    // Warehouse endpoints
    public record CreateWarehouseRequest(UUID merchantId, UUID branchId, String code, String name, String address) {}

    @PostMapping("/warehouses")
    public WarehouseService.Warehouse createWarehouse(@RequestBody CreateWarehouseRequest req) {
        return warehouseService.createWarehouse(req.merchantId(), req.branchId(), req.code(), req.name(), req.address());
    }

    @GetMapping("/warehouses/merchant/{merchantId}")
    public List<WarehouseService.Warehouse> listWarehouses(@PathVariable UUID merchantId) {
        return warehouseService.listWarehouses(merchantId);
    }

    // Product & Variant endpoints
    public record CreateCategoryRequest(UUID merchantId, String name) {}

    @PostMapping("/categories")
    public ProductCatalogService.Category createCategory(@RequestBody CreateCategoryRequest req) {
        return catalogService.createCategory(req.merchantId(), req.name());
    }

    public record CreateProductRequest(UUID merchantId, UUID categoryId, String name, String description, String brand) {}

    @PostMapping("/products")
    public ProductCatalogService.Product createProduct(@RequestBody CreateProductRequest req) {
        return catalogService.createProduct(req.merchantId(), req.categoryId(), req.name(), req.description(), req.brand());
    }

    public record CreateVariantRequest(
            UUID merchantId, UUID productId, String sku, String barcode,
            String variantName, String attributesJson, long costPricePesewas,
            long retailPricePesewas, boolean trackInventory
    ) {}

    @PostMapping("/variants")
    public ProductCatalogService.Variant createVariant(@RequestBody CreateVariantRequest req) {
        return catalogService.createVariant(
                req.merchantId(), req.productId(), req.sku(), req.barcode(),
                req.variantName(), req.attributesJson(), req.costPricePesewas(),
                req.retailPricePesewas(), req.trackInventory()
        );
    }

    // Fast Scanner Lookups (Barcode & SKU)
    @GetMapping("/scan/barcode")
    public ResponseEntity<ProductCatalogService.VariantDetail> scanBarcode(
            @RequestParam UUID merchantId,
            @RequestParam String barcode
    ) {
        return catalogService.lookupByBarcode(merchantId, barcode)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/scan/sku")
    public ResponseEntity<ProductCatalogService.VariantDetail> scanSku(
            @RequestParam UUID merchantId,
            @RequestParam String sku
    ) {
        return catalogService.lookupBySku(merchantId, sku)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Stock endpoints
    public record StockAdjustmentRequest(
            UUID warehouseId, UUID variantId, BigDecimal quantityChange,
            MovementType movementType, UUID referenceId, String notes
    ) {}

    @PostMapping("/stock/adjust")
    public StockService.Stock adjustStock(@RequestBody StockAdjustmentRequest req) {
        return stockService.adjustStock(
                req.warehouseId(), req.variantId(), req.quantityChange(),
                req.movementType(), req.referenceId(), req.notes()
        );
    }

    @GetMapping("/stock/alerts/{merchantId}")
    public List<StockService.LowStockAlert> listAlerts(@PathVariable UUID merchantId) {
        return stockService.listLowStockAlerts(merchantId);
    }

    // Batch tracking endpoints
    public record CreateBatchRequest(
            UUID warehouseId, UUID variantId, String batchNumber,
            LocalDate expiryDate, BigDecimal quantity, long costPricePesewas
    ) {}

    @PostMapping("/batches")
    public BatchTrackingService.Batch createBatch(@RequestBody CreateBatchRequest req) {
        return batchService.createBatch(
                req.warehouseId(), req.variantId(), req.batchNumber(),
                req.expiryDate(), req.quantity(), req.costPricePesewas()
        );
    }

    // Stock Transfer endpoints
    public record CreateTransferRequest(
            UUID merchantId, UUID sourceWarehouseId, UUID destWarehouseId,
            List<StockTransferService.TransferItemInput> items,
            String trackingNumber, String notes
    ) {}

    @PostMapping("/transfers")
    public StockTransferService.TransferOrder createTransfer(@RequestBody CreateTransferRequest req) {
        return transferService.createTransferOrder(
                req.merchantId(), req.sourceWarehouseId(), req.destWarehouseId(),
                req.items(), req.trackingNumber(), req.notes()
        );
    }

    @PostMapping("/transfers/{id}/dispatch")
    public StockTransferService.TransferOrder dispatchTransfer(@PathVariable UUID id) {
        return transferService.dispatchTransferOrder(id);
    }

    @PostMapping("/transfers/{id}/receive")
    public StockTransferService.TransferOrder receiveTransfer(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<UUID, BigDecimal> receivedQuantities
    ) {
        return transferService.receiveTransferOrder(id, receivedQuantities);
    }

    // Purchase Order endpoints
    public record CreateSupplierRequest(UUID merchantId, String name, String email, String phone, String address) {}

    @PostMapping("/suppliers")
    public PurchaseOrderService.Supplier createSupplier(@RequestBody CreateSupplierRequest req) {
        return poService.createSupplier(req.merchantId(), req.name(), req.email(), req.phone(), req.address());
    }

    public record CreatePoRequest(
            UUID merchantId, UUID supplierId, UUID warehouseId,
            String poNumber, List<PurchaseOrderService.PoItemInput> items
    ) {}

    @PostMapping("/purchase-orders")
    public PurchaseOrderService.PurchaseOrder createPo(@RequestBody CreatePoRequest req) {
        return poService.createPurchaseOrder(req.merchantId(), req.supplierId(), req.warehouseId(), req.poNumber(), req.items());
    }

    @PostMapping("/purchase-orders/{id}/issue")
    public PurchaseOrderService.PurchaseOrder issuePo(@PathVariable UUID id) {
        return poService.issuePurchaseOrder(id);
    }

    @PostMapping("/purchase-orders/{id}/receive")
    public PurchaseOrderService.PurchaseOrder receivePo(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<UUID, BigDecimal> receivedQuantities
    ) {
        return poService.receivePoItems(id, receivedQuantities);
    }
}
