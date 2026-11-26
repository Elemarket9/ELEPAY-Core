package com.elepay.merchanttools.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseOrderService {
    private final JdbcClient db;
    private final StockService stockService;

    public PurchaseOrderService(JdbcClient db, StockService stockService) {
        this.db = db;
        this.stockService = stockService;
    }

    public record Supplier(
            UUID id,
            UUID merchantId,
            String name,
            String contactEmail,
            String contactPhone,
            String address,
            OffsetDateTime createdAt
    ) {}

    public record PurchaseOrder(
            UUID id,
            UUID merchantId,
            UUID supplierId,
            UUID warehouseId,
            String poNumber,
            String status,
            long totalCostPesewas,
            OffsetDateTime issuedAt,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt
    ) {}

    public record PoItem(
            UUID id,
            UUID poId,
            UUID variantId,
            long unitCostPesewas,
            BigDecimal quantityOrdered,
            BigDecimal quantityReceived
    ) {}

    public record PoItemInput(
            UUID variantId,
            long unitCostPesewas,
            BigDecimal quantityOrdered
    ) {}

    @Transactional
    public Supplier createSupplier(UUID merchantId, String name, String email, String phone, String address) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_supplier (id, merchant_id, name, contact_email, contact_phone, address, created_at)
            VALUES (:id, :merchantId, :name, :email, :phone, :address, now())
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("name", name)
            .param("email", email)
            .param("phone", phone)
            .param("address", address)
            .update();

        return getSupplier(id).orElseThrow();
    }

    public Optional<Supplier> getSupplier(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, name, contact_email, contact_phone, address, created_at
            FROM inventory_supplier WHERE id = :id
            """)
            .param("id", id)
            .query(Supplier.class)
            .optional();
    }

    public List<Supplier> listSuppliers(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, name, contact_email, contact_phone, address, created_at
            FROM inventory_supplier WHERE merchant_id = :merchantId
            ORDER BY name ASC
            """)
            .param("merchantId", merchantId)
            .query(Supplier.class)
            .list();
    }

    @Transactional
    public PurchaseOrder createPurchaseOrder(
            UUID merchantId,
            UUID supplierId,
            UUID warehouseId,
            String poNumber,
            List<PoItemInput> items
    ) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Purchase order must include at least one item");
        }
        long totalCostPesewas = 0;
        for (PoItemInput item : items) {
            if (item == null || item.quantityOrdered() == null || item.quantityOrdered().compareTo(BigDecimal.ZERO) <= 0
                    || item.unitCostPesewas() < 0) {
                throw new IllegalArgumentException("PO item quantity must be positive and unit cost non-negative");
            }
            long lineCost = Math.multiplyExact(item.unitCostPesewas(), item.quantityOrdered().longValue());
            totalCostPesewas = Math.addExact(totalCostPesewas, lineCost);
        }

        UUID poId = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_purchase_order (
                id, merchant_id, supplier_id, warehouse_id, po_number, status,
                total_cost_pesewas, created_at
            ) VALUES (
                :id, :merchantId, :supplierId, :warehouseId, :poNumber, 'DRAFT',
                :totalCostPesewas, now()
            )
            """)
            .param("id", poId)
            .param("merchantId", merchantId)
            .param("supplierId", supplierId)
            .param("warehouseId", warehouseId)
            .param("poNumber", poNumber)
            .param("totalCostPesewas", totalCostPesewas)
            .update();

        for (PoItemInput item : items) {
            UUID itemId = UUID.randomUUID();
            db.sql("""
                INSERT INTO inventory_po_item (
                    id, po_id, variant_id, unit_cost_pesewas, quantity_ordered, quantity_received
                ) VALUES (
                    :id, :poId, :variantId, :unitCostPesewas, :quantityOrdered, 0
                )
                """)
                .param("id", itemId)
                .param("poId", poId)
                .param("variantId", item.variantId())
                .param("unitCostPesewas", item.unitCostPesewas())
                .param("quantityOrdered", item.quantityOrdered())
                .update();
        }

        return getPurchaseOrder(poId).orElseThrow();
    }

    public Optional<PurchaseOrder> getPurchaseOrder(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, supplier_id, warehouse_id, po_number, status,
                   total_cost_pesewas, issued_at, completed_at, created_at
            FROM inventory_purchase_order WHERE id = :id
            """)
            .param("id", id)
            .query(PurchaseOrder.class)
            .optional();
    }

    private PurchaseOrder lockPurchaseOrder(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, supplier_id, warehouse_id, po_number, status,
                   total_cost_pesewas, issued_at, completed_at, created_at
            FROM inventory_purchase_order WHERE id = :id FOR UPDATE
            """)
            .param("id", id)
            .query(PurchaseOrder.class)
            .optional()
            .orElseThrow();
    }

    public List<PoItem> listPoItems(UUID poId) {
        return db.sql("""
            SELECT id, po_id, variant_id, unit_cost_pesewas, quantity_ordered, quantity_received
            FROM inventory_po_item WHERE po_id = :poId
            """)
            .param("poId", poId)
            .query(PoItem.class)
            .list();
    }

    @Transactional
    public PurchaseOrder issuePurchaseOrder(UUID poId) {
        db.sql("""
            UPDATE inventory_purchase_order
            SET status = 'ISSUED', issued_at = now()
            WHERE id = :id AND status = 'DRAFT'
            """)
            .param("id", poId)
            .update();

        return getPurchaseOrder(poId).orElseThrow();
    }

    @Transactional
    public PurchaseOrder receivePoItems(UUID poId, Map<UUID, BigDecimal> receivedQuantities) {
        PurchaseOrder po = lockPurchaseOrder(poId);
        if (!"ISSUED".equals(po.status()) && !"PARTIAL_RECEIVED".equals(po.status())) {
            throw new IllegalStateException("PO cannot be received in status: " + po.status());
        }

        List<PoItem> items = listPoItems(poId);
        boolean allComplete = true;

        for (PoItem item : items) {
            BigDecimal alreadyReceived = item.quantityReceived() == null ? BigDecimal.ZERO : item.quantityReceived();
            BigDecimal newlyReceived = receivedQuantities != null && receivedQuantities.containsKey(item.variantId())
                    ? receivedQuantities.get(item.variantId())
                    : item.quantityOrdered().subtract(alreadyReceived);

            if (newlyReceived == null || newlyReceived.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Received quantity cannot be negative");
            }
            if (alreadyReceived.add(newlyReceived).compareTo(item.quantityOrdered()) > 0) {
                throw new IllegalArgumentException("Cumulative received quantity exceeds ordered quantity (" + item.quantityOrdered() + ")");
            }

            if (newlyReceived.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal totalReceived = alreadyReceived.add(newlyReceived);
                db.sql("""
                    UPDATE inventory_po_item
                    SET quantity_received = :totalReceived
                    WHERE id = :id
                    """)
                    .param("totalReceived", totalReceived)
                    .param("id", item.id())
                    .update();

                // Increase warehouse stock
                stockService.adjustStock(
                        po.warehouseId(),
                        item.variantId(),
                        newlyReceived,
                        MovementType.RECEIVED,
                        poId,
                        "Received on PO " + po.poNumber()
                );

                // Update variant unit cost
                db.sql("""
                    UPDATE inventory_variant
                    SET cost_price_pesewas = :cost
                    WHERE id = :variantId
                    """)
                    .param("cost", item.unitCostPesewas())
                    .param("variantId", item.variantId())
                    .update();

                if (totalReceived.compareTo(item.quantityOrdered()) < 0) {
                    allComplete = false;
                }
            } else if (alreadyReceived.compareTo(item.quantityOrdered()) < 0) {
                allComplete = false;
            }
        }

        String nextStatus = allComplete ? "COMPLETED" : "PARTIAL_RECEIVED";
        db.sql("""
            UPDATE inventory_purchase_order
            SET status = :status, completed_at = (CASE WHEN :status = 'COMPLETED' THEN now() ELSE NULL END)
            WHERE id = :id
            """)
            .param("status", nextStatus)
            .param("id", poId)
            .update();

        return getPurchaseOrder(poId).orElseThrow();
    }
}
