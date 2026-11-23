package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Legacy POS offline-card compatibility boundary.
 * No issuer-authorization substitute or invented settlement is permitted.
 * Offline cash orders use PosOfflineSyncService; offline cards need certified acquirer evidence.
 */
@Service
public class PosOfflineTransactionEngine {

    public static final long DEFAULT_OFFLINE_FLOOR_LIMIT_PESEWAS = 5_000L; // 50.00 GHS

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

    /**
     * Unverified offline card data must never create an authorization code or a balance.
     * Certified terminal partners may later implement a separate verified store-and-forward
     * adapter with real issuer/acquirer evidence. Cash POS order sync is a different path.
     */
    public OfflineAuthorizationResult processOfflineTransaction(OfflineTransactionRequest req) {
        if (req == null || req.amountPesewas() <= 0)
            throw ApiException.badRequest("invalid_amount", "Amount must be positive minor units");
        if (req.amountPesewas() > DEFAULT_OFFLINE_FLOOR_LIMIT_PESEWAS)
            return new OfflineAuthorizationResult(false, null, req.terminalId(), req.offlineStan(),
                    null, req.amountPesewas(), "DECLINED", "OFFLINE_FLOOR_LIMIT_EXCEEDED");
        return new OfflineAuthorizationResult(false, null, req.terminalId(), req.offlineStan(),
                null, req.amountPesewas(), "DECLINED", "RAIL_NOT_CONFIGURED");
    }

    /** Historical queued offline card records remain intact, but cannot mint settlement. */
    public TerminalSyncResult syncTerminalBatch(String terminalId) {
        throw ApiException.unprocessable("RAIL_NOT_CONFIGURED",
                "Offline card settlement requires independently verified acquirer evidence");
    }
}
