package com.elepay.enterprise.checkout;

import com.elepay.ledger.Ledger;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Result of ingesting and ledger-posting an externally collected enterprise checkout payment.
 */
public record EnterpriseCheckoutResult(
        UUID checkoutId,
        UUID journalTxId,
        String idempotencyKey,
        UUID enterpriseId,
        String orderReference,
        String externalPaymentReference,
        String paymentChannel,
        String currency,
        long totalPesewas,
        String status,
        OffsetDateTime processedAt,
        EnterpriseSplitResult splitResult,
        List<Ledger.Leg> journalLegs,
        boolean isReplay
) {
    public EnterpriseCheckoutResult {
        journalLegs = List.copyOf(journalLegs);
    }

    public UUID ledgerTransactionId() {
        return journalTxId;
    }
}
