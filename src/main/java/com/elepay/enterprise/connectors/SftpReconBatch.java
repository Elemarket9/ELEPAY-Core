package com.elepay.enterprise.connectors;

import java.time.LocalDate;
import java.util.List;

/**
 * Parsed SFTP batch reconciliation data object.
 */
public record SftpReconBatch(
        String batchId,
        LocalDate batchDate,
        String currency,
        int totalRecords,
        long totalGrossPesewas,
        long totalNetPesewas,
        long totalFeePesewas,
        String checksumSha256,
        List<SftpReconRecord> records
) {
    public SftpReconBatch {
        records = List.copyOf(records);
    }
}
