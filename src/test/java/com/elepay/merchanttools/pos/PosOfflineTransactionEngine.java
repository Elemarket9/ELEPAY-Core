package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS Offline Transaction Store-and-Forward Engine.
 * Handles:
 * 1. Offline floor limit verification (e.g. 50 GHS max offline charge when network is unreachable).
 * 2. Terminal STAN and replay attack prevention.
 * 3. Local offline transaction enqueueing with cryptographic offline authorization code.
 * 4. Batch offline reconciliation and double-entry ledger capture when terminal connects online.
 */
@Service
public class PosOfflineTransactionEngine {

    public static final long DEFAULT_OFFLINE_FLOOR_LIMIT_PESEWAS = 5_000L; // 50.00 GHS
    public static final String POS_OFFLINE_CLEARING_ACCOUNT = "system:clearing:pos_offline:GHS";

    private final JdbcClient db;
    private final Ledger ledger;

    public PosOfflineTransactionEngine(JdbcClient db, Ledger ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    public record OfflineTransactionRequest(
            String terminalId,
            UUID merchantId,
            String offlineStan,
            long amountPesewas,
            String currency,
            String mcc,
            String maskedPan,
            String entryMode,
            OffsetDateTime storedAt
    ) {}

    public record OfflineAuthorizationResult(
            boolean approved,
            UUID queueId,
            String terminalId,
            String offlineStan,
            String authCode,
            long amountPesewas,
            String status,
            String declineReason
    ) {}

    public record BatchSyncOutcome(
            UUID queueId,
            String offlineStan,
            long amountPesewas,
            String syncStatus,
            UUID ledgerTxId
    ) {}

    public record TerminalSyncResult(
            String terminalId,
            int totalProcessed,
            long totalAmountPesewas,
            int syncedCount,
            int rejectedCount,
            List<BatchSyncOutcome> outcomes
    ) {}

    @Transactional
    public OfflineAuthorizationResult processOfflineTransaction(OfflineTransactionRequest req) {
        if (req.amountPesewas() <= 0) {
            throw ApiException.badRequest("invalid_amount", "Amount must be positive minor units");
        }

        // 1. Verify offline floor limit
        if (req.amountPesewas() > DEFAULT_OFFLINE_FLOOR_LIMIT_PESEWAS) {
            return new OfflineAuthorizationResult(
                    false, null, req.terminalId(), req.offlineStan(), null,
                    req.amountPesewas(), "DECLINED", "OFFLINE_FLOOR_LIMIT_EXCEEDED"
            );
        }

        // 2. Replay protection: Check if (terminal_id, offline_stan) already exists
        boolean duplicate = db.sql("SELECT count(*) FROM pos_offline_transaction_queue WHERE terminal_id = :tid AND offline_stan = :stan")
                .param("tid", req.terminalId())
                .param("stan", req.offlineStan())
                .query(Long.class)
                .single() > 0;

        if (duplicate) {
            return new OfflineAuthorizationResult(
                    false, null, req.terminalId(), req.offlineStan(), null,
                    req.amountPesewas(), "DECLINED", "DUPLICATE_OFFLINE_STAN_REPLAY"
            );
        }

        UUID queueId = UUID.randomUUID();
        String authCode = "OFF-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        OffsetDateTime storedTime = req.storedAt() != null ? req.storedAt() : OffsetDateTime.now();

        db.sql("""
            INSERT INTO pos_offline_transaction_queue (
                id, terminal_id, merchant_id, offline_stan, auth_code,
                amount_pesewas, currency, mcc, masked_pan, entry_mode,
                stored_at, sync_status, created_at, updated_at
            ) VALUES (
                :id, :tid, :mid, :stan, :auth,
                :amt, :curr, :mcc, :pan, :entry,
                :storedAt, 'PENDING_SYNC', now(), now()
            )
        """)
                .param("id", queueId)
                .param("tid", req.terminalId())
                .param("mid", req.merchantId())
                .param("stan", req.offlineStan())
                .param("auth", authCode)
                .param("amt", req.amountPesewas())
                .param("curr", req.currency() != null ? req.currency() : "GHS")
                .param("mcc", req.mcc() != null ? req.mcc() : "5411")
                .param("pan", req.maskedPan() != null ? req.maskedPan() : "402234******1234")
                .param("entry", req.entryMode() != null ? req.entryMode() : "051")
                .param("storedAt", storedTime)
                .update();

        return new OfflineAuthorizationResult(
                true, queueId, req.terminalId(), req.offlineStan(), authCode,
                req.amountPesewas(), "STORED", null
        );
    }

    @Transactional
    public TerminalSyncResult syncTerminalBatch(String terminalId) {
        var pendingList = db.sql("""
            SELECT id, terminal_id, merchant_id, offline_stan, amount_pesewas, currency, sync_status
            FROM pos_offline_transaction_queue
            WHERE terminal_id = :tid AND sync_status = 'PENDING_SYNC'
            ORDER BY stored_at ASC
            FOR UPDATE
        """).param("tid", terminalId).query((rs, rowNum) -> new Object[] {
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("offline_stan"),
                rs.getLong("amount_pesewas"),
                rs.getString("currency")
        }).list();

        List<BatchSyncOutcome> outcomes = new ArrayList<>();
        int synced = 0;
        int rejected = 0;
        long totalAmt = 0L;

        ledger.openSystem(POS_OFFLINE_CLEARING_ACCOUNT, "clearing", Ledger.GHS, true);

        for (Object[] row : pendingList) {
            UUID qId = (UUID) row[0];
            UUID merchantId = (UUID) row[1];
            String stan = (String) row[2];
            long amt = (Long) row[3];
            String curr = (String) row[4];

            totalAmt += amt;

            String merchantAccount = Ledger.code("merchant", merchantId, "available", curr);
            ledger.open("merchant", merchantId, "available", curr);

            var posting = Ledger.Posting.of(
                    "pos_offline_capture",
                    "POS-SYNC-" + terminalId + "-" + stan,
                    "POS_OFFLINE_SETTLEMENT",
                    List.of(
                            Ledger.Leg.debit(POS_OFFLINE_CLEARING_ACCOUNT, amt),
                            Ledger.Leg.credit(merchantAccount, amt)
                    )
            ).describedAs("POS offline settlement capture for terminal " + terminalId + " STAN " + stan);

            UUID ledgerTxId = ledger.post(posting).transactionId();

            db.sql("""
                UPDATE pos_offline_transaction_queue
                SET sync_status = 'SYNCED',
                    ledger_tx_id = :ledgerTx,
                    updated_at = now()
                WHERE id = :id
            """)
                    .param("ledgerTx", ledgerTxId)
                    .param("id", qId)
                    .update();

            synced++;
            outcomes.add(new BatchSyncOutcome(qId, stan, amt, "SYNCED", ledgerTxId));
        }

        return new TerminalSyncResult(terminalId, pendingList.size(), totalAmt, synced, rejected, outcomes);
    }
}
