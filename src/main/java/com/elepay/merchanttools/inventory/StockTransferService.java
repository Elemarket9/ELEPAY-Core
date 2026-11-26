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
public class StockTransferService {
    private final JdbcClient db;
    private final StockService stockService;

    public StockTransferService(JdbcClient db, StockService stockService) {
        this.db = db;
        this.stockService = stockService;
    }

    public record TransferOrder(
            UUID id,
            UUID merchantId,
            UUID sourceWarehouseId,
            UUID destinationWarehouseId,
            String status,
            String trackingNumber,
            String notes,
            OffsetDateTime dispatchedAt,
            OffsetDateTime receivedAt,
            OffsetDateTime createdAt
    ) {}

    public record TransferItem(
            UUID id,
            UUID transferId,
            UUID variantId,
            BigDecimal quantitySent,
            BigDecimal quantityReceived
    ) {}

    public record TransferItemInput(
            UUID variantId,
            BigDecimal quantity
    ) {}

    @Transactional
    public TransferOrder createTransferOrder(
            UUID merchantId,
            UUID sourceWarehouseId,
            UUID destinationWarehouseId,
            List<TransferItemInput> items,
            String trackingNumber,
            String notes
    ) {
        if (sourceWarehouseId.equals(destinationWarehouseId)) {
            throw new IllegalArgumentException("Source and destination warehouses cannot be identical");
        }

        UUID transferId = UUID.randomUUID();
        db.sql("""
            INSERT INTO inventory_transfer_order (
                id, merchant_id, source_warehouse_id, destination_warehouse_id,
                status, tracking_number, notes, created_at
            ) VALUES (
                :id, :merchantId, :sourceWarehouseId, :destinationWarehouseId,
                'REQUESTED', :trackingNumber, :notes, now()
            )
            """)
            .param("id", transferId)
            .param("merchantId", merchantId)
            .param("sourceWarehouseId", sourceWarehouseId)
            .param("destinationWarehouseId", destinationWarehouseId)
            .param("trackingNumber", trackingNumber)
            .param("notes", notes)
            .update();

        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Transfer order must include at least one item");
        }
        for (TransferItemInput item : items) {
            if (item == null || item.quantity() == null || item.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Transfer item quantity must be positive");
            }
            UUID itemId = UUID.randomUUID();
            db.sql("""
                INSERT INTO inventory_transfer_item (
                    id, transfer_id, variant_id, quantity_sent, quantity_received
                ) VALUES (
                    :id, :transferId, :variantId, :quantitySent, 0
                )
                """)
                .param("id", itemId)
                .param("transferId", transferId)
                .param("variantId", item.variantId())
                .param("quantitySent", item.quantity())
                .update();
        }

        return getTransferOrder(transferId).orElseThrow();
    }

    public Optional<TransferOrder> getTransferOrder(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, source_warehouse_id, destination_warehouse_id,
                   status, tracking_number, notes, dispatched_at, received_at, created_at
            FROM inventory_transfer_order WHERE id = :id
            """)
            .param("id", id)
            .query(TransferOrder.class)
            .optional();
    }

    private TransferOrder lockTransferOrder(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, source_warehouse_id, destination_warehouse_id,
                   status, tracking_number, notes, dispatched_at, received_at, created_at
            FROM inventory_transfer_order WHERE id = :id FOR UPDATE
            """)
            .param("id", id)
            .query(TransferOrder.class)
            .optional()
            .orElseThrow();
    }

    public List<TransferItem> listTransferItems(UUID transferId) {
        return db.sql("""
            SELECT id, transfer_id, variant_id, quantity_sent, quantity_received
            FROM inventory_transfer_item WHERE transfer_id = :transferId
            """)
            .param("transferId", transferId)
            .query(TransferItem.class)
            .list();
    }

    @Transactional
    public TransferOrder dispatchTransferOrder(UUID transferId) {
        TransferOrder order = lockTransferOrder(transferId);
        if (!"REQUESTED".equals(order.status())) {
            throw new IllegalStateException("Transfer order cannot be dispatched from status: " + order.status());
        }

        List<TransferItem> items = listTransferItems(transferId);
        for (TransferItem item : items) {
            stockService.deductStock(
                    order.sourceWarehouseId(),
                    item.variantId(),
                    item.quantitySent(),
                    MovementType.TRANSFER_OUT,
                    transferId,
                    "Dispatched in transfer order " + transferId
            );
        }

        db.sql("""
            UPDATE inventory_transfer_order
            SET status = 'DISPATCHED', dispatched_at = now()
            WHERE id = :id
            """)
            .param("id", transferId)
            .update();

        return getTransferOrder(transferId).orElseThrow();
    }

    @Transactional
    public TransferOrder receiveTransferOrder(UUID transferId, Map<UUID, BigDecimal> receivedQuantities) {
        TransferOrder order = lockTransferOrder(transferId);
        if (!"DISPATCHED".equals(order.status())) {
            throw new IllegalStateException("Transfer order cannot be received from status: " + order.status());
        }

        List<TransferItem> items = listTransferItems(transferId);
        for (TransferItem item : items) {
            BigDecimal qtyReceived = receivedQuantities != null && receivedQuantities.containsKey(item.variantId())
                    ? receivedQuantities.get(item.variantId())
                    : item.quantitySent();
            if (qtyReceived == null || qtyReceived.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Received quantity cannot be negative");
            }
            if (qtyReceived.compareTo(item.quantitySent()) > 0) {
                throw new IllegalArgumentException("Received quantity (" + qtyReceived
                        + ") cannot exceed dispatched quantity (" + item.quantitySent() + ")");
            }

            db.sql("""
                UPDATE inventory_transfer_item
                SET quantity_received = :qtyReceived
                WHERE id = :id
                """)
                .param("qtyReceived", qtyReceived)
                .param("id", item.id())
                .update();

            stockService.adjustStock(
                    order.destinationWarehouseId(),
                    item.variantId(),
                    qtyReceived,
                    MovementType.TRANSFER_IN,
                    transferId,
                    "Received in transfer order " + transferId
            );
        }

        db.sql("""
            UPDATE inventory_transfer_order
            SET status = 'RECEIVED', received_at = now()
            WHERE id = :id
            """)
            .param("id", transferId)
            .update();

        return getTransferOrder(transferId).orElseThrow();
    }

    @Transactional
    public TransferOrder cancelTransferOrder(UUID transferId) {
        TransferOrder order = getTransferOrder(transferId).orElseThrow();
        if (!"REQUESTED".equals(order.status())) {
            throw new IllegalStateException("Only REQUESTED transfer orders can be cancelled");
        }

        db.sql("""
            UPDATE inventory_transfer_order
            SET status = 'CANCELLED'
            WHERE id = :id
            """)
            .param("id", transferId)
            .update();

        return getTransferOrder(transferId).orElseThrow();
    }
}
