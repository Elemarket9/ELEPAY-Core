package com.elepay.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BatchTrackingService {
    private final JdbcClient db;

    public BatchTrackingService(JdbcClient db) {
        this.db = db;
    }

    public record Batch(
            UUID id,
            UUID variantId,
            UUID warehouseId,
            String batchNumber,
            LocalDate expiryDate,
            BigDecimal quantity,
            long costPricePesewas,
            String status,
            OffsetDateTime createdAt
    ) {}

    @Transactional
    public Batch createBatch(
            UUID warehouseId,
            UUID variantId,
            String batchNumber,
            LocalDate expiryDate,
            BigDecimal quantity,
            long costPricePesewas
    ) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_batch (
                id, variant_id, warehouse_id, batch_number, expiry_date,
                quantity, cost_price_pesewas, status, created_at
            ) VALUES (
                :id, :variantId, :warehouseId, :batchNumber, :expiryDate,
                :quantity, :costPricePesewas, 'ACTIVE', now()
            )
            """)
            .param("id", id)
            .param("variantId", variantId)
            .param("warehouseId", warehouseId)
            .param("batchNumber", batchNumber)
            .param("expiryDate", expiryDate)
            .param("quantity", quantity)
            .param("costPricePesewas", costPricePesewas)
            .update();

        return getBatch(id).orElseThrow();
    }

    public Optional<Batch> getBatch(UUID id) {
        return db.sql("""
            SELECT id, variant_id, warehouse_id, batch_number, expiry_date,
                   quantity, cost_price_pesewas, status, created_at
            FROM inventory_batch WHERE id = :id
            """)
            .param("id", id)
            .query(Batch.class)
            .optional();
    }

    public List<Batch> listBatches(UUID warehouseId, UUID variantId) {
        return db.sql("""
            SELECT id, variant_id, warehouse_id, batch_number, expiry_date,
                   quantity, cost_price_pesewas, status, created_at
            FROM inventory_batch
            WHERE warehouse_id = :warehouseId AND variant_id = :variantId AND status = 'ACTIVE'
            ORDER BY expiry_date ASC NULLS LAST, created_at ASC
            """)
            .param("warehouseId", warehouseId)
            .param("variantId", variantId)
            .query(Batch.class)
            .list();
    }

    // FEFO (First Expired, First Out) batch deduction
    @Transactional
    public BigDecimal deductFromBatches(UUID warehouseId, UUID variantId, BigDecimal quantityToDeduct) {
        List<Batch> batches = listBatches(warehouseId, variantId);
        BigDecimal remaining = quantityToDeduct;

        for (Batch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal currentBatchQty = batch.quantity();
            if (currentBatchQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal deductFromThisBatch = currentBatchQty.min(remaining);
            BigDecimal newBatchQty = currentBatchQty.subtract(deductFromThisBatch);

            db.sql("""
                UPDATE inventory_batch
                SET quantity = :newQty
                WHERE id = :id
                """)
                .param("newQty", newBatchQty)
                .param("id", batch.id())
                .update();

            remaining = remaining.subtract(deductFromThisBatch);
        }

        return quantityToDeduct.subtract(remaining); // actual deducted
    }
}
