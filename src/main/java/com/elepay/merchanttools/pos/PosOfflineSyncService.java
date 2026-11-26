package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
            OffsetDateTime syncedAt,
            String payloadHash,
            UUID serverOrderId
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

    public Optional<OfflineSyncLog> findByTerminalAndIdempotencyKey(UUID terminalId, String idempotencyKey) {
        return db.sql("""
            SELECT id, terminal_id, offline_tx_id, idempotency_key, sync_status,
                   conflict_reason, resolution_applied, synced_at, payload_hash, server_order_id
            FROM pos_offline_sync_log WHERE terminal_id = :terminalId AND idempotency_key = :key
            """)
            .param("terminalId", terminalId)
            .param("key", idempotencyKey)
            .query(OfflineSyncLog.class)
            .optional();
    }

    public Optional<OfflineSyncLog> findByTerminalAndOfflineTxId(UUID terminalId, String offlineTxId) {
        return db.sql("""
            SELECT id, terminal_id, offline_tx_id, idempotency_key, sync_status,
                   conflict_reason, resolution_applied, synced_at, payload_hash, server_order_id
            FROM pos_offline_sync_log WHERE terminal_id = :terminalId AND offline_tx_id = :offlineTxId
            """)
            .param("terminalId", terminalId)
            .param("offlineTxId", offlineTxId)
            .query(OfflineSyncLog.class)
            .optional();
    }

    static String computePayloadHash(OfflineOrderPayload payload) {
        return Idempotency.hash(payload);
    }

    @Transactional
    public SyncResult syncOfflineOrder(OfflineOrderPayload payload) {
        if (payload == null) {
            throw ApiException.badRequest("invalid_argument", "Offline sync payload is required");
        }
        if (payload.terminalId() == null) {
            throw ApiException.badRequest("invalid_argument", "terminalId is required");
        }
        if (payload.idempotencyKey() == null || payload.idempotencyKey().isBlank()) {
            throw ApiException.badRequest("invalid_argument", "idempotencyKey is required");
        }
        if (payload.offlineTxId() == null || payload.offlineTxId().isBlank()) {
            throw ApiException.badRequest("invalid_argument", "offlineTxId is required");
        }
        if (payload.items() == null || payload.items().isEmpty()) {
            throw ApiException.badRequest("invalid_argument", "Order items are required");
        }

        // Serialize concurrent retries for the same (terminal_id, idempotency_key)
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended('pos-sync:' || :t || ':' || :k, 0))")
                .param("t", payload.terminalId().toString())
                .param("k", payload.idempotencyKey())
                .query((rs, n) -> 1)
                .single();

        String payloadHash = computePayloadHash(payload);

        // 1. Idempotency check: duplicate sync rejection/return scoped by (terminal_id, idempotency_key) or (terminal_id, offline_tx_id)
        Optional<OfflineSyncLog> existingLog = findByTerminalAndIdempotencyKey(payload.terminalId(), payload.idempotencyKey())
                .or(() -> findByTerminalAndOfflineTxId(payload.terminalId(), payload.offlineTxId()));
        if (existingLog.isPresent()) {
            OfflineSyncLog existing = existingLog.get();
            if (existing.payloadHash() != null
                    && !MessageDigest.isEqual(
                            existing.payloadHash().getBytes(StandardCharsets.UTF_8),
                            payloadHash.getBytes(StandardCharsets.UTF_8))) {
                throw ApiException.idempotencyConflict();
            }
            return new SyncResult(
                    payload.offlineTxId(),
                    payload.idempotencyKey(),
                    existing.syncStatus(),
                    existing.serverOrderId(),
                    existing.resolutionApplied(),
                    "Already processed under idempotency key " + existing.idempotencyKey()
            );
        }

        // 2. Validate calculations / detect potential price conflicts using BigDecimal & checked math
        long expectedSubtotal = 0L;
        for (PosOrderService.OrderItemInput item : payload.items()) {
            if (item == null || item.quantity() == null || item.quantity().signum() <= 0
                    || item.unitPricePesewas() < 0 || item.discountPesewas() < 0 || item.taxPesewas() < 0) {
                throw new IllegalArgumentException("Invalid offline order item amounts");
            }
            long gross = item.quantity()
                    .multiply(java.math.BigDecimal.valueOf(item.unitPricePesewas()))
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
            long itemTotal = Math.addExact(Math.subtractExact(gross, item.discountPesewas()), item.taxPesewas());
            expectedSubtotal = Math.addExact(expectedSubtotal, itemTotal);
        }
        long expectedTotal = Math.addExact(
                Math.addExact(Math.subtractExact(expectedSubtotal, payload.discountPesewas()), payload.taxPesewas()),
                payload.tipPesewas());

        boolean priceConflict = expectedTotal != payload.totalPesewas();
        String resolutionApplied = "NONE";
        String syncStatus = "SYNCED";
        String conflictReason = null;

        if (priceConflict) {
            String strategy = payload.conflictStrategy() != null ? payload.conflictStrategy() : "SERVER_ADJUSTED";
            if ("REJECT_FLAGGED".equalsIgnoreCase(strategy)) {
                UUID logId = UUID.randomUUID();
                db.sql("""
                    INSERT INTO pos_offline_sync_log (
                        id, terminal_id, offline_tx_id, idempotency_key,
                        sync_status, conflict_reason, resolution_applied, synced_at,
                        payload_hash, server_order_id
                    ) VALUES (
                        :id, :terminalId, :offlineTxId, :idempotencyKey,
                        'REJECTED', :reason, 'REJECT_FLAGGED', now(),
                        :payloadHash, NULL
                    )
                    """)
                    .param("id", logId)
                    .param("terminalId", payload.terminalId())
                    .param("offlineTxId", payload.offlineTxId())
                    .param("idempotencyKey", payload.idempotencyKey())
                    .param("reason", "Total mismatch: payload=" + payload.totalPesewas() + ", expected=" + expectedTotal)
                    .param("payloadHash", payloadHash)
                    .update();

                return new SyncResult(payload.offlineTxId(), payload.idempotencyKey(), "REJECTED", null, "REJECT_FLAGGED", "Offline sync rejected due to calculation mismatch");
            } else if ("ACCEPT_LOCAL_FORCE".equalsIgnoreCase(strategy)) {
                conflictReason = "Local total " + payload.totalPesewas() + " accepted over server calculated " + expectedTotal;
                resolutionApplied = "ACCEPT_LOCAL_FORCE";
                syncStatus = "CONFLICT_RESOLVED";
            } else {
                // SERVER_ADJUSTED (default)
                conflictReason = "Total adjusted from " + payload.totalPesewas() + " to server calculated " + expectedTotal;
                resolutionApplied = "SERVER_ADJUSTED";
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
                sync_status, conflict_reason, resolution_applied, synced_at,
                payload_hash, server_order_id
            ) VALUES (
                :id, :terminalId, :offlineTxId, :idempotencyKey,
                :syncStatus, :conflictReason, :resolutionApplied, now(),
                :payloadHash, :serverOrderId
            )
            """)
            .param("id", logId)
            .param("terminalId", payload.terminalId())
            .param("offlineTxId", payload.offlineTxId())
            .param("idempotencyKey", payload.idempotencyKey())
            .param("syncStatus", syncStatus)
            .param("conflictReason", conflictReason)
            .param("resolutionApplied", resolutionApplied)
            .param("payloadHash", payloadHash)
            .param("serverOrderId", order.id())
            .update();

        return new SyncResult(payload.offlineTxId(), payload.idempotencyKey(), syncStatus, order.id(), resolutionApplied, "Successfully synced");
    }
}
