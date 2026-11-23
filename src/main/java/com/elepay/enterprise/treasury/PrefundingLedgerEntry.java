package com.elepay.enterprise.treasury;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entry in the append-only enterprise prefunding and receivables ledger.
 */
public record PrefundingLedgerEntry(
        UUID id,
        UUID enterpriseId,
        UUID treasuryAccountId,
        String transactionReference,
        PrefundingEntryType entryType,
        long amountPesewas,
        long balanceAfterPesewas,
        String currency,
        UUID ledgerTxId,
        String description,
        OffsetDateTime createdAt
) {}
