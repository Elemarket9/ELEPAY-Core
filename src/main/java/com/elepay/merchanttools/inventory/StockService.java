package com.elepay.merchanttools.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {
    private final JdbcClient db;

    public StockService(JdbcClient db) {
        this.db = db;
    }

    public record Stock(
            UUID id,
            UUID warehouseId,
            UUID variantId,
            BigDecimal quantityOnHand,
            BigDecimal quantityReserved,
            BigDecimal reorderPoint,
            BigDecimal reorderQuantity,
            OffsetDateTime updatedAt
    ) {
        public BigDecimal quantityAvailable() {
            return quantityOnHand.subtract(quantityReserved);
        }
    }

    public record StockMovement(
            UUID id,
            UUID stockId,
            UUID variantId,
            UUID warehouseId,
            BigDecimal quantityChange,
            BigDecimal quantityAfter,
            MovementType movementType,
            UUID referenceId,
            String notes,
            OffsetDateTime createdAt
    ) {}

    public record LowStockAlert(
            UUID variantId,
            String sku,
            String variantName,
            String productName,
            UUID warehouseId,
            String warehouseName,
            BigDecimal quantityOnHand,
            BigDecimal reorderPoint,
            String severity
    ) {}

    @Transactional
    public Stock getOrCreateStock(UUID warehouseId, UUID variantId) {
        Optional<Stock> existing = db.sql("""
            SELECT id, warehouse_id, variant_id, quantity_on_hand, quantity_reserved,
                   reorder_point, reorder_quantity, updated_at
            FROM inventory_stock
            WHERE warehouse_id = :warehouseId AND variant_id = :variantId
            FOR UPDATE
            """)
            .param("warehouseId", warehouseId)
            .param("variantId", variantId)
            .query(Stock.class)
            .optional();

        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_stock (
                id, warehouse_id, variant_id, quantity_on_hand, quantity_reserved,
                reorder_point, reorder_quantity, updated_at
            ) VALUES (
                :id, :warehouseId, :variantId, 0, 0, 10, 50, now()
            )
            ON CONFLICT (warehouse_id, variant_id) DO NOTHING
            """)
            .param("id", id)
            .param("warehouseId", warehouseId)
            .param("variantId", variantId)
            .update();

        return db.sql("""
            SELECT id, warehouse_id, variant_id, quantity_on_hand, quantity_reserved,
                   reorder_point, reorder_quantity, updated_at
            FROM inventory_stock
            WHERE warehouse_id = :warehouseId AND variant_id = :variantId
            """)
            .param("warehouseId", warehouseId)
            .param("variantId", variantId)
            .query(Stock.class)
            .single();
    }

    @Transactional
    public Stock adjustStock(
            UUID warehouseId,
            UUID variantId,
            BigDecimal quantityChange,
            MovementType movementType,
            UUID referenceId,
            String notes
    ) {
        Stock stock = getOrCreateStock(warehouseId, variantId);
        BigDecimal newQuantity = stock.quantityOnHand().add(quantityChange);
        if (newQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Stock cannot become negative: current=" + stock.quantityOnHand() + ", change=" + quantityChange);
        }

        db.sql("""
            UPDATE inventory_stock
            SET quantity_on_hand = :newQuantity, updated_at = now()
            WHERE id = :id
            """)
            .param("newQuantity", newQuantity)
            .param("id", stock.id())
            .update();

        UUID movementId = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_stock_movement (
                id, stock_id, variant_id, warehouse_id, quantity_change,
                quantity_after, movement_type, reference_id, notes, created_at
            ) VALUES (
                :id, :stockId, :variantId, :warehouseId, :quantityChange,
                :quantityAfter, :movementType, :referenceId, :notes, now()
            )
            """)
            .param("id", movementId)
            .param("stockId", stock.id())
            .param("variantId", variantId)
            .param("warehouseId", warehouseId)
            .param("quantityChange", quantityChange)
            .param("quantityAfter", newQuantity)
            .param("movementType", movementType.name())
            .param("referenceId", referenceId)
            .param("notes", notes)
            .update();

        return new Stock(stock.id(), warehouseId, variantId, newQuantity, stock.quantityReserved(),
                stock.reorderPoint(), stock.reorderQuantity(), OffsetDateTime.now());
    }

    @Transactional
    public Stock reserveStock(UUID warehouseId, UUID variantId, BigDecimal quantity) {
        Stock stock = getOrCreateStock(warehouseId, variantId);
        BigDecimal available = stock.quantityAvailable();
        if (available.compareTo(quantity) < 0) {
            throw new IllegalStateException("Insufficient available stock to reserve: available=" + available + ", requested=" + quantity);
        }

        BigDecimal newReserved = stock.quantityReserved().add(quantity);
        db.sql("""
            UPDATE inventory_stock
            SET quantity_reserved = :newReserved, updated_at = now()
            WHERE id = :id
            """)
            .param("newReserved", newReserved)
            .param("id", stock.id())
            .update();

        return new Stock(stock.id(), warehouseId, variantId, stock.quantityOnHand(), newReserved,
                stock.reorderPoint(), stock.reorderQuantity(), OffsetDateTime.now());
    }

    @Transactional
    public Stock releaseStock(UUID warehouseId, UUID variantId, BigDecimal quantity) {
        Stock stock = getOrCreateStock(warehouseId, variantId);
        BigDecimal newReserved = stock.quantityReserved().subtract(quantity);
        if (newReserved.compareTo(BigDecimal.ZERO) < 0) {
            newReserved = BigDecimal.ZERO;
        }

        db.sql("""
            UPDATE inventory_stock
            SET quantity_reserved = :newReserved, updated_at = now()
            WHERE id = :id
            """)
            .param("newReserved", newReserved)
            .param("id", stock.id())
            .update();

        return new Stock(stock.id(), warehouseId, variantId, stock.quantityOnHand(), newReserved,
                stock.reorderPoint(), stock.reorderQuantity(), OffsetDateTime.now());
    }

    @Transactional
    public Stock deductStock(
            UUID warehouseId,
            UUID variantId,
            BigDecimal quantity,
            MovementType movementType,
            UUID referenceId,
            String notes
    ) {
        Stock stock = getOrCreateStock(warehouseId, variantId);
        if (stock.quantityOnHand().compareTo(quantity) < 0) {
            throw new IllegalStateException("Insufficient stock on hand: onHand=" + stock.quantityOnHand() + ", requested=" + quantity);
        }

        BigDecimal newOnHand = stock.quantityOnHand().subtract(quantity);
        BigDecimal newReserved = stock.quantityReserved().compareTo(quantity) >= 0
                ? stock.quantityReserved().subtract(quantity)
                : BigDecimal.ZERO;

        db.sql("""
            UPDATE inventory_stock
            SET quantity_on_hand = :newOnHand, quantity_reserved = :newReserved, updated_at = now()
            WHERE id = :id
            """)
            .param("newOnHand", newOnHand)
            .param("newReserved", newReserved)
            .param("id", stock.id())
            .update();

        UUID movementId = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_stock_movement (
                id, stock_id, variant_id, warehouse_id, quantity_change,
                quantity_after, movement_type, reference_id, notes, created_at
            ) VALUES (
                :id, :stockId, :variantId, :warehouseId, :quantityChange,
                :quantityAfter, :movementType, :referenceId, :notes, now()
            )
            """)
            .param("id", movementId)
            .param("stockId", stock.id())
            .param("variantId", variantId)
            .param("warehouseId", warehouseId)
            .param("quantityChange", quantity.negate())
            .param("quantityAfter", newOnHand)
            .param("movementType", movementType.name())
            .param("referenceId", referenceId)
            .param("notes", notes)
            .update();

        return new Stock(stock.id(), warehouseId, variantId, newOnHand, newReserved,
                stock.reorderPoint(), stock.reorderQuantity(), OffsetDateTime.now());
    }

    public List<LowStockAlert> listLowStockAlerts(UUID merchantId) {
        return db.sql("""
            SELECT v.id AS variant_id, v.sku, v.variant_name, p.name AS product_name,
                   w.id AS warehouse_id, w.name AS warehouse_name,
                   s.quantity_on_hand, s.reorder_point,
                   CASE WHEN s.quantity_on_hand <= 0 THEN 'CRITICAL' ELSE 'WARNING' END AS severity
            FROM inventory_stock s
            JOIN inventory_variant v ON v.id = s.variant_id
            JOIN inventory_product p ON p.id = v.product_id
            JOIN inventory_warehouse w ON w.id = s.warehouse_id
            WHERE w.merchant_id = :merchantId AND s.quantity_on_hand <= s.reorder_point
            ORDER BY s.quantity_on_hand ASC
            """)
            .param("merchantId", merchantId)
            .query(LowStockAlert.class)
            .list();
    }
}
