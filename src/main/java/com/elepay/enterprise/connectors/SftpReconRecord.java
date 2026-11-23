package com.elepay.enterprise.connectors;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Record within an SFTP batch reconciliation file.
 */
public record SftpReconRecord(
        String transactionReference,
        String externalReference,
        UUID merchantId,
        String transactionType, // PAYMENT, REFUND, WITHDRAWAL, SETTLEMENT
        long grossPesewas,
        long netPesewas,
        long feePesewas,
        String status,
        LocalDate transactionDate
) {}
