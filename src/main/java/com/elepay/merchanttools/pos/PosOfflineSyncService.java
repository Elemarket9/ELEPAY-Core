package com.elepay.merchanttools.pos;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosOfflineSyncService {
    private final JdbcClient db;
    private final PosOrderService orderService;

    public PosOfflineSyncService(JdbcClient db, PosOrderService orderService) {
        this.db = db;
        this.orderService = orderService;
    }

    public record OfflineSyncLog(
            UUID id,
            UUID terminalId,
            String offlineTxId,
            String idempotencyKey,
            String syncStatus,
            String conflictReason,
            String resolutionApplied,
            OffsetDateTime syncedAt
    ) {}

    public record OfflineOrderPayload(
            String offlineTxId,
            String idempotencyKey,
            UUID merchantId,
            UUID branchId,
            UUID terminalId,
            UUID cashierId,
            UUID customerId,
            List<PosOrderService.OrderItemInput> items,
            long discountPesewas,
            long taxPesewas,
            long tipPesewas,
            long totalPesewas,
            List<OfflinePayment> payments,
            String conflictStrategy // ACCEPT_LOCAL_FORCE, SERVER_ADJUSTED, REJECT_FLAGGED
    ) {}

    public record OfflinePayment(
            TenderType tenderType,
            long amountPesewas,
            String reference
    ) {}

    public record SyncResult(
            String offlineTxId,
            String idempotencyKey,
            String status,
            UUID serverOrderId,
            String resolutionApplied,
            String message
    ) {}

    public Optional<OfflineSyncLog> findByIdempotencyKey(String idempotencyKey) {
        return db.sql("""
            SELECT id, terminal_id, offline_tx_id, idempotency_key, sync_status,
                   conflict_reason, resolution_applied, synced_at
            FROM pos_offline_sync_log WHERE idempotency_key = :key
            """)
            .param("key", idempotencyKey)
            .query(OfflineSyncLog.class)
            .optional();
    }

    @Transactional
    public SyncResult syncOfflineOrder(OfflineOrderPayload payload) {
        // 1. Idempotency check: duplicate sync rejection/return
        Optional<OfflineSyncLog> existingLog = findByIdempotencyKey(payload.idempotencyKey());
        if (existingLog.isPresent()) {
            return new SyncResult(
                    payload.offlineTxId(),
                    payload.idempotencyKey(),
                    existingLog.get().syncStatus(),
                    null,
                    existingLog.get().resolutionApplied(),
                    "Already processed under idempotency key " + payload.idempotencyKey()
            );
        }

        // 2. Validate calculations / detect potential price conflicts
        long expectedSubtotal = 0;
        for (PosOrderService.OrderItemInput item : payload.items()) {
            long itemTotal = (item.unitPricePesewas() * item.quantity().longValue()) - item.discountPesewas() + item.taxPesewas();
            expectedSubtotal += itemTotal;
        }
        long expectedTotal = expectedSubtotal - payload.discountPesewas() + payload.taxPesewas() + payload.tipPesewas();

        boolean priceConflict = expectedTotal != payload.totalPesewas();
        String resolutionApplied = "NONE";
        String syncStatus = "SYNCED";
        String conflictReason = null;

        if (priceConflict) {
            String strategy = payload.conflictStrategy() != null ? payload.conflictStrategy() : "ACCEPT_LOCAL_FORCE";
            if ("REJECT_FLAGGED".equalsIgnoreCase(strategy)) {
                UUID logId = UUID.randomUUID();
                db.sql("""
                    INSERT INTO pos_offline_sync_log (
                        id, terminal_id, offline_tx_id, idempotency_key,
                        sync_status, conflict_reason, resolution_applied, synced_at
                    ) VALUES (
                        :id, :terminalId, :offlineTxId, :idempotencyKey,
                        'REJECTED', :reason, 'REJECT_FLAGGED', now()
                    )
                    """)
                    .param("id", logId)
                    .param("terminalId", payload.terminalId())
                    .param("offlineTxId", payload.offlineTxId())
                    .param("idempotencyKey", payload.idempotencyKey())
                    .param("reason", "Total mismatch: payload=" + payload.totalPesewas() + ", expected=" + expectedTotal)
                    .update();

                return new SyncResult(payload.offlineTxId(), payload.idempotencyKey(), "REJECTED", null, "REJECT_FLAGGED", "Offline sync rejected due to calculation mismatch");
            } else if ("SERVER_ADJUSTED".equalsIgnoreCase(strategy)) {
                conflictReason = "Total adjusted from " + payload.totalPesewas() + " to server calculated " + expectedTotal;
                resolutionApplied = "SERVER_ADJUSTED";
                syncStatus = "CONFLICT_RESOLVED";
            } else {
                // ACCEPT_LOCAL_FORCE
                conflictReason = "Local total " + payload.totalPesewas() + " accepted over server calculated " + expectedTotal;
                resolutionApplied = "ACCEPT_LOCAL_FORCE";
                syncStatus = "CONFLICT_RESOLVED";
            }
        }

        // 3. Create Server Order
        PosOrderService.PosOrder order = orderService.createOrder(
                payload.merchantId(),
                payload.branchId(),
                payload.terminalId(),
                payload.cashierId(),
                payload.customerId(),
                payload.items(),
                payload.discountPesewas(),
                payload.taxPesewas(),
                payload.tipPesewas(),
                "OFFLINE_SYNC"
        );

        // 4. Apply payments
        if (payload.payments() != null) {
            for (OfflinePayment payment : payload.payments()) {
                orderService.addTenderPayment(
                        order.id(),
                        payment.tenderType(),
                        payment.amountPesewas(),
                        payment.reference() == null ? "OFFLINE:" + payload.offlineTxId() : payment.reference()
                );
            }
        }

        // 5. Record sync audit log
        UUID logId = UUID.randomUUID();
        db.sql("""
            INSERT INTO pos_offline_sync_log (
                id, terminal_id, offline_tx_id, idempotency_key,
                sync_status, conflict_reason, resolution_applied, synced_at
            ) VALUES (
                :id, :terminalId, :offlineTxId, :idempotencyKey,
                :syncStatus, :conflictReason, :resolutionApplied, now()
            )
            """)
            .param("id", logId)
            .param("terminalId", payload.terminalId())
            .param("offlineTxId", payload.offlineTxId())
            .param("idempotencyKey", payload.idempotencyKey())
            .param("syncStatus", syncStatus)
            .param("conflictReason", conflictReason)
            .param("resolutionApplied", resolutionApplied)
            .update();

        return new SyncResult(payload.offlineTxId(), payload.idempotencyKey(), syncStatus, order.id(), resolutionApplied, "Successfully synced");
    }
}
