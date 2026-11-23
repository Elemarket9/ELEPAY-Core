package com.elepay.enterprise.settlement;

import com.elepay.common.ApiException;
import com.elepay.enterprise.checkout.CalculatedMerchantAllocation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery Escrow Service.
 *
 * <p>Enforces conditional settlement release: all order items are held in escrow
 * upon external payment ingestion.
 *
 * <p><strong>CRITICAL INVARIANT:</strong> Missing delivery confirmation does NOT
 * automatically prove delivery. Delivery escrow hold prevents premature payout until
 * explicit delivery verification or authorized resolution.
 */
@Service
public class DeliveryEscrowService {

    private final JdbcClient db;

    public DeliveryEscrowService(JdbcClient db) {
        this.db = Objects.requireNonNull(db, "db cannot be null");
    }

    /**
     * Registers items into conditional delivery escrow upon checkout payment ingestion.
     */
    @Transactional
    public List<DeliveryEscrowRecord> registerOrderItems(
            UUID enterpriseId,
            String orderReference,
            List<CalculatedMerchantAllocation> allocations,
            String currency,
            UUID holdTxId
    ) {
        Objects.requireNonNull(enterpriseId, "enterpriseId cannot be null");
        Objects.requireNonNull(orderReference, "orderReference cannot be null");
        Objects.requireNonNull(allocations, "allocations cannot be null");
        String cur = currency != null ? currency : "GHS";

        List<DeliveryEscrowRecord> records = new ArrayList<>();

        for (CalculatedMerchantAllocation alloc : allocations) {
            UUID id = UUID.randomUUID();
            String itemRef = alloc.description() != null ? alloc.description() : "item-" + alloc.merchantId();

            db.sql("""
                    INSERT INTO enterprise_delivery_escrow (
                        id, enterprise_id, order_reference, item_reference, merchant_id,
                        gross_amount_pesewas, net_payable_pesewas, commission_pesewas,
                        currency, delivery_status, escrow_status, ledger_hold_tx_id
                    ) VALUES (
                        :id, :enterpriseId, :orderRef, :itemRef, :merchantId,
                        :gross, :net, :comm,
                        :cur, 'PENDING', 'HELD', :holdTxId
                    )
                    ON CONFLICT (enterprise_id, order_reference, item_reference) DO UPDATE
                    SET gross_amount_pesewas = EXCLUDED.gross_amount_pesewas,
                        net_payable_pesewas = EXCLUDED.net_payable_pesewas,
                        commission_pesewas = EXCLUDED.commission_pesewas,
                        updated_at = now()
                    """)
                    .param("id", id)
                    .param("enterpriseId", enterpriseId)
                    .param("orderRef", orderReference)
                    .param("itemRef", itemRef)
                    .param("merchantId", alloc.merchantId())
                    .param("gross", alloc.grossAmountPesewas())
                    .param("net", alloc.netPayablePesewas())
                    .param("comm", alloc.marketplaceCommissionPesewas())
                    .param("cur", cur)
                    .param("holdTxId", holdTxId)
                    .update();

            records.add(findEscrowRecord(enterpriseId, orderReference, itemRef)
                    .orElseThrow(() -> ApiException.internal("escrow_save_error", "Failed to retrieve saved escrow record")));
        }

        return records;
    }

    /**
     * Confirms delivery of an order item, marking it eligible for settlement release.
     */
    @Transactional
    public DeliveryEscrowRecord confirmDelivery(UUID enterpriseId, String orderReference, String itemReference, String confirmedBy) {
        var existing = findEscrowRecord(enterpriseId, orderReference, itemReference)
                .orElseThrow(() -> ApiException.notFound("Escrow item not found for order: " + orderReference + ", item: " + itemReference));

        if (existing.escrowStatus() != EscrowStatus.HELD) {
            throw ApiException.badRequest("invalid_state", "Cannot confirm delivery for item with escrow status: " + existing.escrowStatus());
        }

        db.sql("""
                UPDATE enterprise_delivery_escrow
                SET delivery_status = 'DELIVERED',
                    delivered_at = now(),
                    notes = :notes,
                    updated_at = now()
                WHERE enterprise_id = :enterpriseId AND order_reference = :orderRef AND item_reference = :itemRef
                """)
                .param("notes", "Delivery confirmed by: " + confirmedBy)
                .param("enterpriseId", enterpriseId)
                .param("orderRef", orderReference)
                .param("itemRef", itemReference)
                .update();

        return findEscrowRecord(enterpriseId, orderReference, itemReference).orElseThrow();
    }

    /**
     * Flags an order item as missing delivery confirmation.
     * Crucially, missing confirmation does NOT release funds; it strictly retains them in HELD escrow.
     */
    @Transactional
    public DeliveryEscrowRecord reportMissingDelivery(UUID enterpriseId, String orderReference, String itemReference, String reason) {
        var existing = findEscrowRecord(enterpriseId, orderReference, itemReference)
                .orElseThrow(() -> ApiException.notFound("Escrow item not found for order: " + orderReference));

        db.sql("""
                UPDATE enterprise_delivery_escrow
                SET delivery_status = 'MISSING_CONFIRMATION',
                    notes = :notes,
                    updated_at = now()
                WHERE enterprise_id = :enterpriseId AND order_reference = :orderRef AND item_reference = :itemRef
                """)
                .param("notes", "Missing delivery reported: " + reason)
                .param("enterpriseId", enterpriseId)
                .param("orderRef", orderReference)
                .param("itemRef", itemReference)
                .update();

        return findEscrowRecord(enterpriseId, orderReference, itemReference).orElseThrow();
    }

    /**
     * Rejects/cancels an item in delivery escrow (e.g. damaged goods or customer cancellation).
     * Prepares item for refund processing.
     */
    @Transactional
    public DeliveryEscrowRecord rejectItem(UUID enterpriseId, String orderReference, String itemReference, String reason) {
        var existing = findEscrowRecord(enterpriseId, orderReference, itemReference)
                .orElseThrow(() -> ApiException.notFound("Escrow item not found for order: " + orderReference));

        db.sql("""
                UPDATE enterprise_delivery_escrow
                SET delivery_status = 'REJECTED',
                    escrow_status = 'REFUNDED',
                    notes = :notes,
                    updated_at = now()
                WHERE enterprise_id = :enterpriseId AND order_reference = :orderRef AND item_reference = :itemRef
                """)
                .param("notes", "Rejected/Cancelled: " + reason)
                .param("enterpriseId", enterpriseId)
                .param("orderRef", orderReference)
                .param("itemRef", itemReference)
                .update();

        return findEscrowRecord(enterpriseId, orderReference, itemReference).orElseThrow();
    }

    /**
     * Updates an escrow item's status to RELEASED upon settlement release.
     */
    @Transactional
    public void markReleased(UUID escrowId, UUID ledgerReleaseTxId) {
        db.sql("""
                UPDATE enterprise_delivery_escrow
                SET escrow_status = 'RELEASED',
                    released_at = now(),
                    ledger_release_tx_id = :txId,
                    updated_at = now()
                WHERE id = :id
                """)
                .param("txId", ledgerReleaseTxId)
                .param("id", escrowId)
                .update();
    }

    public Optional<DeliveryEscrowRecord> findEscrowRecord(UUID enterpriseId, String orderReference, String itemReference) {
        return db.sql("""
                SELECT id, enterprise_id, order_reference, item_reference, merchant_id,
                       gross_amount_pesewas, net_payable_pesewas, commission_pesewas,
                       currency, delivery_status, escrow_status, delivered_at, released_at,
                       ledger_hold_tx_id, ledger_release_tx_id, notes, created_at, updated_at
                FROM enterprise_delivery_escrow
                WHERE enterprise_id = :enterpriseId AND order_reference = :orderRef AND item_reference = :itemRef
                """)
                .param("enterpriseId", enterpriseId)
                .param("orderRef", orderReference)
                .param("itemRef", itemReference)
                .query(this::mapRecord)
                .optional();
    }

    public List<DeliveryEscrowRecord> findEscrowRecordsByOrder(UUID enterpriseId, String orderReference) {
        return db.sql("""
                SELECT id, enterprise_id, order_reference, item_reference, merchant_id,
                       gross_amount_pesewas, net_payable_pesewas, commission_pesewas,
                       currency, delivery_status, escrow_status, delivered_at, released_at,
                       ledger_hold_tx_id, ledger_release_tx_id, notes, created_at, updated_at
                FROM enterprise_delivery_escrow
                WHERE enterprise_id = :enterpriseId AND order_reference = :orderRef
                ORDER BY created_at ASC
                """)
                .param("enterpriseId", enterpriseId)
                .param("orderRef", orderReference)
                .query(this::mapRecord)
                .list();
    }

    public List<DeliveryEscrowRecord> findEligibleForRelease(UUID enterpriseId) {
        return db.sql("""
                SELECT id, enterprise_id, order_reference, item_reference, merchant_id,
                       gross_amount_pesewas, net_payable_pesewas, commission_pesewas,
                       currency, delivery_status, escrow_status, delivered_at, released_at,
                       ledger_hold_tx_id, ledger_release_tx_id, notes, created_at, updated_at
                FROM enterprise_delivery_escrow
                WHERE enterprise_id = :enterpriseId AND delivery_status = 'DELIVERED' AND escrow_status = 'HELD'
                ORDER BY created_at ASC
                """)
                .param("enterpriseId", enterpriseId)
                .query(this::mapRecord)
                .list();
    }

    private DeliveryEscrowRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new DeliveryEscrowRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.getString("order_reference"),
                rs.getString("item_reference"),
                rs.getObject("merchant_id", UUID.class),
                rs.getLong("gross_amount_pesewas"),
                rs.getLong("net_payable_pesewas"),
                rs.getLong("commission_pesewas"),
                rs.getString("currency"),
                DeliveryStatus.valueOf(rs.getString("delivery_status")),
                EscrowStatus.valueOf(rs.getString("escrow_status")),
                rs.getObject("delivered_at", OffsetDateTime.class),
                rs.getObject("released_at", OffsetDateTime.class),
                rs.getObject("ledger_hold_tx_id", UUID.class),
                rs.getObject("ledger_release_tx_id", UUID.class),
                rs.getString("notes"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
