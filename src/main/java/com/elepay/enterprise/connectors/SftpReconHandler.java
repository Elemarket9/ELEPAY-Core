package com.elepay.enterprise.connectors;

import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Generates and parses standardized enterprise SFTP reconciliation batch files.
 * Format:
 * - Line 1 (Header): HEADER|{batchId}|{date}|{currency}|{recordCount}
 * - Lines 2..N (Records): TX|{ref}|{extRef}|{merchantId}|{type}|{gross}|{net}|{fee}|{status}|{date}
 * - Line N+1 (Trailer): TRAILER|{recordCount}|{totalGross}|{totalNet}|{totalFee}|{sha256}
 */
@Component
public class SftpReconHandler {

    /**
     * Formats a list of reconciliation records into a standard file string.
     */
    public String generateReconFile(String batchId, LocalDate batchDate, String currency, List<SftpReconRecord> records) {
        Objects.requireNonNull(batchId, "batchId cannot be null");
        Objects.requireNonNull(records, "records cannot be null");
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : "GHS";

        long totalGross = 0L;
        long totalNet = 0L;
        long totalFee = 0L;

        StringBuilder body = new StringBuilder();
        for (SftpReconRecord r : records) {
            totalGross += r.grossPesewas();
            totalNet += r.netPesewas();
            totalFee += r.feePesewas();

            body.append("TX|")
                    .append(r.transactionReference()).append("|")
                    .append(r.externalReference()).append("|")
                    .append(r.merchantId()).append("|")
                    .append(r.transactionType()).append("|")
                    .append(r.grossPesewas()).append("|")
                    .append(r.netPesewas()).append("|")
                    .append(r.feePesewas()).append("|")
                    .append(r.status()).append("|")
                    .append(r.transactionDate() != null ? r.transactionDate() : batchDate)
                    .append("\n");
        }

        String contentForChecksum = body.toString();
        String sha256 = computeSha256(contentForChecksum);

        StringBuilder sb = new StringBuilder();
        sb.append("HEADER|").append(batchId).append("|").append(batchDate).append("|").append(cur).append("|").append(records.size()).append("\n");
        sb.append(body);
        sb.append("TRAILER|").append(records.size()).append("|").append(totalGross).append("|").append(totalNet).append("|").append(totalFee).append("|").append(sha256).append("\n");

        return sb.toString();
    }

    /**
     * Parses an SFTP reconciliation file, validating header, trailer, record counts, and SHA-256 checksum.
     */
    public SftpReconBatch parseReconFile(String fileContent) {
        if (fileContent == null || fileContent.isBlank()) {
            throw ApiException.badRequest("empty_file", "Reconciliation file is empty");
        }

        String[] lines = fileContent.strip().split("\\r?\\n");
        if (lines.length < 2) {
            throw ApiException.badRequest("malformed_file", "Reconciliation file must contain at least header and trailer");
        }

        // Parse Header
        String[] headerParts = lines[0].split("\\|");
        if (headerParts.length < 5 || !"HEADER".equals(headerParts[0])) {
            throw ApiException.badRequest("invalid_header", "Malformed HEADER line");
        }
        String batchId = headerParts[1];
        LocalDate batchDate = LocalDate.parse(headerParts[2]);
        String currency = headerParts[3];
        int declaredCount = Integer.parseInt(headerParts[4]);

        // Parse Trailer
        String[] trailerParts = lines[lines.length - 1].split("\\|");
        if (trailerParts.length < 6 || !"TRAILER".equals(trailerParts[0])) {
            throw ApiException.badRequest("invalid_trailer", "Malformed TRAILER line");
        }
        int trailerCount = Integer.parseInt(trailerParts[1]);
        long trailerGross = Long.parseLong(trailerParts[2]);
        long trailerNet = Long.parseLong(trailerParts[3]);
        long trailerFee = Long.parseLong(trailerParts[4]);
        String expectedChecksum = trailerParts[5];

        if (declaredCount != trailerCount || declaredCount != (lines.length - 2)) {
            throw ApiException.badRequest("count_mismatch", "Record count mismatch: header=" + declaredCount
                    + " trailer=" + trailerCount + " actual=" + (lines.length - 2));
        }

        // Parse Body and compute checksum
        StringBuilder body = new StringBuilder();
        List<SftpReconRecord> records = new ArrayList<>();
        long calculatedGross = 0L;
        long calculatedNet = 0L;
        long calculatedFee = 0L;

        for (int i = 1; i < lines.length - 1; i++) {
            body.append(lines[i]).append("\n");
            String[] parts = lines[i].split("\\|");
            if (parts.length < 10 || !"TX".equals(parts[0])) {
                throw ApiException.badRequest("invalid_record", "Malformed TX line at " + (i + 1));
            }
            long gross = Long.parseLong(parts[5]);
            long net = Long.parseLong(parts[6]);
            long fee = Long.parseLong(parts[7]);

            calculatedGross += gross;
            calculatedNet += net;
            calculatedFee += fee;

            records.add(new SftpReconRecord(
                    parts[1],
                    parts[2],
                    UUID.fromString(parts[3]),
                    parts[4],
                    gross,
                    net,
                    fee,
                    parts[8],
                    LocalDate.parse(parts[9])
            ));
        }

        // Validate checksum
        String actualChecksum = computeSha256(body.toString());
        if (!MessageDigest.isEqual(actualChecksum.getBytes(StandardCharsets.UTF_8), expectedChecksum.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.badRequest("checksum_failure", "Checksum mismatch in reconciliation batch: declared="
                    + expectedChecksum + " computed=" + actualChecksum);
        }

        if (calculatedGross != trailerGross || calculatedNet != trailerNet || calculatedFee != trailerFee) {
            throw ApiException.badRequest("totals_mismatch", "Financial total mismatch in trailer");
        }

        return new SftpReconBatch(
                batchId,
                batchDate,
                currency,
                records.size(),
                calculatedGross,
                calculatedNet,
                calculatedFee,
                actualChecksum,
                records
        );
    }

    private String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw ApiException.internal("hash_error", "Failed to calculate SHA-256: " + e.getMessage());
        }
    }
}
