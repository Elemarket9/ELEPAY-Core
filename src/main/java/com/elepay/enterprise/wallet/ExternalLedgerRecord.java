package com.elepay.enterprise.wallet;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An individual ledger transaction line from external partner ledger.
 */
public record ExternalLedgerRecord(
        String externalReference,
        String internalReference,
        String type, // AUTHORIZATION, CAPTURE, REFUND, WITHDRAWAL
        long amountMinorUnits,
        String currency,
        String status,
        Instant timestamp
) {}
