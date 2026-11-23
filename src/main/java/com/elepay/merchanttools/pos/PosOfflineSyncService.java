package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cloud's replay-safe intake for durable offline POS orders. The local browser
 * records cash as locally received; the server alone decides whether it can be
 * reconciled. Card, MoMo, and NFC must go through a verified online rail.
 */
@Service
public class PosOfflineSyncService {
    private final JdbcClient db;
    private final PosOrderService orderService;

    public PosOfflineSyncService(JdbcClient db, PosOrderService orderService) {
        this.db = db;
        this.orderService = orderService;
    }

    public record OfflineSyncLog(
            UUID id, UUID merchantId, UUID terminalId, String offlineTxId, String idempotencyKey,
            String syncStatus, String conflictReason, String resolutionApplied,
            OffsetDateTime syncedAt, UUID serverOrderId, String payloadHash
    ) {}

    public record OfflineOrderPayload(
            String offlineTxId, String idempotencyKey, UUID merchantId, UUID branchId,
            UUID terminalId, UUID cashierId, UUID customerId,
            List<PosOrderService.OrderItemInput> items,
            long discountPesewas, long taxPesewas, long tipPesewas, long totalPesewas,
            List<OfflinePayment> payments, String conflictStrategy,
            String orderType, UUID tableId
    ) {
        /** Keep existing integrations source-compatible while offline channels are introduced. */
        public OfflineOrderPayload(
                String offlineTxId, String idempotencyKey, UUID merchantId, UUID branchId,
                UUID terminalId, UUID cashierId, UUID customerId,
                List<PosOrderService.OrderItemInput> items,
                long discountPesewas, long taxPesewas, long tipPesewas, long totalPesewas,
                List<OfflinePayment> payments, String conflictStrategy) {
            this(offlineTxId, idempotencyKey, merchantId, branchId, terminalId, cashierId, customerId,
                    items, discountPesewas, taxPesewas, tipPesewas, totalPesewas,
                    payments, conflictStrategy, "OFFLINE_SYNC", null);
        }
    }

    public record OfflinePayment(TenderType tenderType, long amountPesewas, String reference) {}

    public record SyncResult(
            String offlineTxId, String idempotencyKey, String status,
            UUID serverOrderId, String resolutionApplied, String message
    ) {}

    public Optional<OfflineSyncLog> findByIdempotencyKey(String idempotencyKey) {
        return db.sql("""
                SELECT id, merchant_id, terminal_id, offline_tx_id, idempotency_key, sync_status,
                       conflict_reason, resolution_applied, synced_at, server_order_id, payload_hash
                FROM pos_offline_sync_log WHERE idempotency_key = :key
                """).param("key", idempotencyKey).query(OfflineSyncLog.class).optional();
    }

    private Optional<OfflineSyncLog> findPrevious(OfflineOrderPayload payload) {
        return db.sql("""
                SELECT id, merchant_id, terminal_id, offline_tx_id, idempotency_key, sync_status,
                       conflict_reason, resolution_applied, synced_at, server_order_id, payload_hash
                FROM pos_offline_sync_log
                WHERE (merchant_id = :merchant AND offline_tx_id = :tx)
                   OR idempotency_key = :key
                ORDER BY synced_at ASC LIMIT 1
                """)
                .param("merchant", payload.merchantId())
                .param("tx", payload.offlineTxId())
                .param("key", payload.idempotencyKey())
                .query(OfflineSyncLog.class).optional();
    }

    @Transactional
    public SyncResult syncOfflineOrder(OfflineOrderPayload payload) {
        return syncOfflineOrder(payload, null);
    }

    @Transactional
    public SyncResult syncOfflineOrder(OfflineOrderPayload payload, String actorSubject) {
        validate(payload);
        long expected = calculateTotal(payload);
        String hash = fingerprint(payload);
        // The UNIQUE merchant/transaction constraint serializes concurrent retries, including
        // a caller retrying the *same* offline transaction with a new idempotency key.
        int reserved = db.sql("""
                INSERT INTO pos_offline_sync_log
                    (id, merchant_id, terminal_id, offline_tx_id, idempotency_key, sync_status,
                     payload_hash, resolution_applied, synced_at)
                VALUES (:id, :merchant, :terminal, :tx, :key, 'PROCESSING',
                        :hash, 'NONE', now())
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("merchant", payload.merchantId())
                .param("terminal", payload.terminalId()).param("tx", payload.offlineTxId())
                .param("key", payload.idempotencyKey()).param("hash", hash).update();
        if (reserved == 0) {
            OfflineSyncLog prior = findPrevious(payload).orElseThrow(() ->
                    ApiException.conflict("offline_sync_conflict", "Offline transaction reference is already reserved"));
            if (!payload.merchantId().equals(prior.merchantId())
                    || !payload.offlineTxId().equals(prior.offlineTxId())
                    || !hash.equals(prior.payloadHash()))
                throw ApiException.conflict("offline_replay_changed", "Offline order was already submitted with different details");
            return new SyncResult(payload.offlineTxId(), prior.idempotencyKey(), prior.syncStatus(),
                    prior.serverOrderId(), prior.resolutionApplied(), "Already processed offline transaction");
        }

        if (expected != payload.totalPesewas()) {
            db.sql("""
                    UPDATE pos_offline_sync_log
                    SET sync_status = 'REJECTED', resolution_applied = 'REJECT_FLAGGED',
                        conflict_reason = :reason, synced_at = now()
                    WHERE merchant_id = :merchant AND offline_tx_id = :tx
                    """)
                    .param("reason", "Offline total does not match integer-pesewa item calculation")
                    .param("merchant", payload.merchantId()).param("tx", payload.offlineTxId()).update();
            return new SyncResult(payload.offlineTxId(), payload.idempotencyKey(), "REJECTED",
                    null, "REJECT_FLAGGED", "Offline totals conflict; retain local order for manager review");
        }

        // No payment rail is invoked here. The only permitted offline tender is physical cash.
        String channel = payload.orderType() == null ? "OFFLINE_SYNC" : payload.orderType();
        PosOrderService.PosOrder order = orderService.createOrder(
                payload.merchantId(), payload.branchId(), payload.terminalId(), payload.cashierId(),
                payload.customerId(), payload.items(), payload.discountPesewas(),
                payload.taxPesewas(), payload.tipPesewas(), channel, payload.tableId(), actorSubject);
        if (payload.payments() != null) {
            for (OfflinePayment p : payload.payments()) {
                orderService.addTenderPayment(order.id(), TenderType.CASH, p.amountPesewas(),
                        p.reference() == null ? "OFFLINE:" + payload.offlineTxId() : p.reference());
            }
        }
        db.sql("""
                UPDATE pos_offline_sync_log
                SET sync_status = 'SYNCED', server_order_id = :order,
                    resolution_applied = 'NONE', synced_at = now()
                WHERE merchant_id = :merchant AND offline_tx_id = :tx
                """)
                .param("order", order.id()).param("merchant", payload.merchantId())
                .param("tx", payload.offlineTxId()).update();
        return new SyncResult(payload.offlineTxId(), payload.idempotencyKey(), "SYNCED",
                order.id(), "NONE", "Offline order reconciled");
    }

    private static void validate(OfflineOrderPayload p) {
        if (p == null || p.merchantId() == null || p.offlineTxId() == null
                || !p.offlineTxId().matches("[A-Za-z0-9._:-]{1,128}")
                || p.idempotencyKey() == null || !p.idempotencyKey().matches("[A-Za-z0-9._:-]{1,128}"))
            throw ApiException.badRequest("offline_reference_invalid", "Offline order needs stable transaction and replay keys");
        if (p.customerId() != null)
            throw ApiException.badRequest("offline_customer_unverified", "Link customers after reconciliation");
        if (p.items() == null || p.items().isEmpty() || p.items().size() > 100
                || p.discountPesewas() < 0 || p.taxPesewas() < 0 || p.tipPesewas() < 0
                || p.totalPesewas() <= 0)
            throw ApiException.badRequest("offline_order_invalid", "Offline order totals or items invalid");
        if (p.payments() != null) {
            if (p.payments().size() > 20)
                throw ApiException.badRequest("offline_tender_invalid", "Too many cash tenders");
            for (OfflinePayment tender : p.payments()) {
                if (tender == null || tender.tenderType() != TenderType.CASH || tender.amountPesewas() <= 0)
                    throw ApiException.unprocessable("offline_digital_not_allowed",
                            "Offline card, NFC and MoMo payments require verified online authorization");
            }
        }
        if (p.orderType() != null && !java.util.Set.of(
                "RETAIL", "STANDARD", "OFFLINE_SYNC", "DINE_IN", "TAKEOUT", "DELIVERY").contains(p.orderType()))
            throw ApiException.badRequest("offline_channel_invalid", "Unsupported offline order channel");
    }

    private static long calculateTotal(OfflineOrderPayload p) {
        long subtotal = 0;
        for (PosOrderService.OrderItemInput i : p.items()) {
            if (i == null || i.quantity() == null || i.quantity().signum() <= 0
                    || i.unitPricePesewas() < 0 || i.discountPesewas() < 0 || i.taxPesewas() < 0)
                throw ApiException.badRequest("offline_item_invalid", "Invalid offline order item");
            long gross = i.quantity().multiply(BigDecimal.valueOf(i.unitPricePesewas()))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (i.discountPesewas() > gross)
                throw ApiException.badRequest("offline_discount_invalid", "Discount exceeds item price");
            subtotal = Math.addExact(subtotal,
                    Math.addExact(Math.subtractExact(gross, i.discountPesewas()), i.taxPesewas()));
        }
        if (p.discountPesewas() > subtotal)
            throw ApiException.badRequest("offline_discount_invalid", "Order discount exceeds subtotal");
        return Math.addExact(Math.addExact(Math.subtractExact(subtotal, p.discountPesewas()),
                p.taxPesewas()), p.tipPesewas());
    }

    private static String fingerprint(OfflineOrderPayload p) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : List.of(p.merchantId(), p.offlineTxId())) feed(digest, value);
            // null-safe, length-prefixed fields prevent ambiguous delimiters in customer notes.
            for (Object value : new Object[]{p.branchId(), p.terminalId(), p.cashierId(), p.customerId(),
                    p.discountPesewas(), p.taxPesewas(), p.tipPesewas(), p.totalPesewas(),
                    p.orderType(), p.tableId()}) feed(digest, value);
            for (PosOrderService.OrderItemInput i : p.items()) {
                feed(digest, "item");
                for (Object value : new Object[]{i.variantId(), i.itemName(), i.quantity(),
                        i.unitPricePesewas(), i.discountPesewas(), i.taxPesewas(),
                        i.modifiersJson(), i.seatNumber(), i.course(), i.notes()}) feed(digest, value);
            }
            if (p.payments() != null) {
                for (OfflinePayment x : p.payments()) {
                    feed(digest, "payment");
                    for (Object value : new Object[]{x.tenderType(), x.amountPesewas(), x.reference()})
                        feed(digest, value);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void feed(MessageDigest digest, Object value) {
        byte[] b = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(b.length).array());
        digest.update(b);
    }
}
