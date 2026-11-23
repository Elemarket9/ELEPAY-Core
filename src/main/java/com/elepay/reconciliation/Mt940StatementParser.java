package com.elepay.reconciliation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance parser and ingestion service for SWIFT MT940 / MT942 electronic bank statements.
 * Parses Tag :20:, :25:, :28C:, :60F:, :61:, :86:, and :62F: fields, calculates SHA-256 audit hashes,
 * and persists statement batches and line items into authoritative reconciliation tables.
 */
@Service
public class Mt940StatementParser {

    private static final DateTimeFormatter DATE_FORMAT_6_DIGIT = DateTimeFormatter.ofPattern("yyMMdd");

    // Pattern for Field 61: :61:YYMMDD[MMDD]D/C[funds_code]AmountN[trans_type]Reference[//BankReference]
    private static final Pattern FIELD_61_PATTERN = Pattern.compile(
            "^(\\d{6})(\\d{4})?(C|D|RC|RD)([A-Z])?([0-9,.]+)(?:N|F)([A-Z0-9]{3})([^/\\n\\r]+)?(?://([\\S]+))?",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for Field 60F/62F: :60F:C/D YYMMDD Currency Amount
    private static final Pattern BALANCE_PATTERN = Pattern.compile(
            "^(C|D)(\\d{6})([A-Z]{3})([0-9,.]+)",
            Pattern.CASE_INSENSITIVE
    );

    public record StatementLine(
            int lineNumber,
            LocalDate valueDate,
            LocalDate entryDate,
            String debitCreditMark,
            long amountPesewas,
            String transactionTypeCode,
            String customerReference,
            String bankReference,
            String supplementaryDetails
    ) {}

    public record ParsedStatement(
            String transactionReference,
            String accountNumber,
            String statementNumber,
            String sequenceNumber,
            String currency,
            long openingBalancePesewas,
            long closingBalancePesewas,
            LocalDate statementDate,
            List<StatementLine> lines,
            String rawSha256
    ) {}

    public record BatchIngestionResult(
            UUID batchId,
            String batchReference,
            int totalLines,
            long openingBalancePesewas,
            long closingBalancePesewas,
            String status
    ) {}

    private final JdbcClient db;

    public Mt940StatementParser(JdbcClient db) {
        this.db = db;
    }

    public ParsedStatement parse(String mt940Content) {
        if (mt940Content == null || mt940Content.isBlank()) {
            throw new IllegalArgumentException("MT940 statement content cannot be empty");
        }

        String sha256 = calculateSha256(mt940Content);
        String[] lines = mt940Content.split("\\r?\\n");

        String transactionReference = null;
        String accountNumber = null;
        String statementNumber = "1";
        String sequenceNumber = "1";
        String currency = "GHS";
        long openingBalance = 0L;
        long closingBalance = 0L;
        LocalDate statementDate = LocalDate.now();

        List<StatementLine> statementLines = new ArrayList<>();
        StatementLine currentLine = null;
        int lineCounter = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("-")) continue;

            if (line.startsWith(":20:")) {
                transactionReference = line.substring(4).trim();
            } else if (line.startsWith(":25:")) {
                accountNumber = line.substring(4).trim();
            } else if (line.startsWith(":28C:") || line.startsWith(":28:")) {
                String val = line.substring(line.indexOf(':') + 1).replace("28C:", "").replace("28:", "").trim();
                String[] parts = val.split("/");
                statementNumber = parts[0];
                if (parts.length > 1) sequenceNumber = parts[1];
            } else if (line.startsWith(":60F:") || line.startsWith(":60M:")) {
                String val = line.substring(5).trim();
                Matcher m = BALANCE_PATTERN.matcher(val);
                if (m.find()) {
                    String dOrC = m.group(1);
                    statementDate = parseDate6(m.group(2));
                    currency = m.group(3);
                    long rawAmt = parseAmountPesewas(m.group(4));
                    openingBalance = "D".equalsIgnoreCase(dOrC) ? -rawAmt : rawAmt;
                }
            } else if (line.startsWith(":61:")) {
                lineCounter++;
                String val = line.substring(4).trim();
                Matcher m = FIELD_61_PATTERN.matcher(val);
                if (m.find()) {
                    LocalDate valDate = parseDate6(m.group(1));
                    LocalDate entryDate = m.group(2) != null ? parseDate4(m.group(1).substring(0, 2), m.group(2)) : valDate;
                    String dcMark = m.group(3).toUpperCase();
                    long amt = parseAmountPesewas(m.group(5));
                    String typeCode = m.group(6);
                    String custRef = m.group(7) != null ? m.group(7).trim() : null;
                    String bankRef = m.group(8) != null ? m.group(8).trim() : (custRef != null ? custRef : "REF-" + lineCounter);

                    currentLine = new StatementLine(
                            lineCounter, valDate, entryDate, dcMark, amt, typeCode, custRef, bankRef, null
                    );
                    statementLines.add(currentLine);
                } else {
                    // Fallback lenient parser for simpler lines
                    LocalDate valDate = LocalDate.now();
                    currentLine = new StatementLine(
                            lineCounter, valDate, valDate, "C", 0L, "MSC", null, "LINE-" + lineCounter, val
                    );
                    statementLines.add(currentLine);
                }
            } else if (line.startsWith(":86:")) {
                String narrative = line.substring(4).trim();
                if (currentLine != null && !statementLines.isEmpty()) {
                    int lastIdx = statementLines.size() - 1;
                    StatementLine existing = statementLines.get(lastIdx);
                    statementLines.set(lastIdx, new StatementLine(
                            existing.lineNumber(), existing.valueDate(), existing.entryDate(),
                            existing.debitCreditMark(), existing.amountPesewas(), existing.transactionTypeCode(),
                            existing.customerReference(), existing.bankReference(), narrative
                    ));
                }
            } else if (line.startsWith(":62F:") || line.startsWith(":62M:")) {
                String val = line.substring(5).trim();
                Matcher m = BALANCE_PATTERN.matcher(val);
                if (m.find()) {
                    String dOrC = m.group(1);
                    currency = m.group(3);
                    long rawAmt = parseAmountPesewas(m.group(4));
                    closingBalance = "D".equalsIgnoreCase(dOrC) ? -rawAmt : rawAmt;
                }
            }
        }

        if (transactionReference == null) {
            transactionReference = "MT940-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        if (accountNumber == null) {
            accountNumber = "UNKNOWN_ACC";
        }

        return new ParsedStatement(
                transactionReference, accountNumber, statementNumber, sequenceNumber,
                currency, openingBalance, closingBalance, statementDate, statementLines, sha256
        );
    }

    @Transactional
    public BatchIngestionResult ingestStatement(String bankCode, String mt940Content) {
        ParsedStatement parsed = parse(mt940Content);
        UUID batchId = UUID.randomUUID();
        String batchRef = "BATCH-" + bankCode.toUpperCase() + "-" + parsed.statementNumber() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        OffsetDateTime now = OffsetDateTime.now();

        db.sql("""
            INSERT INTO recon_bank_statement_batch (
                id, batch_reference, bank_code, account_number, statement_number,
                sequence_number, currency, opening_balance_pesewas, closing_balance_pesewas,
                statement_date, total_lines, matched_lines, discrepancy_lines, status,
                raw_content_sha256, created_at
            ) VALUES (
                :id, :batchRef, :bankCode, :accNum, :stmtNum,
                :seqNum, :currency, :openBal, :closeBal,
                :stmtDate, :totalLines, 0, 0, 'INGESTED',
                :sha256, :now
            )
        """)
                .param("id", batchId)
                .param("batchRef", batchRef)
                .param("bankCode", bankCode.toUpperCase())
                .param("accNum", parsed.accountNumber())
                .param("stmtNum", parsed.statementNumber())
                .param("seqNum", parsed.sequenceNumber())
                .param("currency", parsed.currency())
                .param("openBal", parsed.openingBalancePesewas())
                .param("closeBal", parsed.closingBalancePesewas())
                .param("stmtDate", parsed.statementDate())
                .param("totalLines", parsed.lines().size())
                .param("sha256", parsed.rawSha256())
                .param("now", now)
                .update();

        for (StatementLine line : parsed.lines()) {
            UUID lineId = UUID.randomUUID();
            db.sql("""
                INSERT INTO recon_statement_line (
                    id, batch_id, line_number, value_date, entry_date,
                    debit_credit_mark, amount_pesewas, transaction_type_code,
                    bank_reference, customer_reference, supplementary_details,
                    match_status, created_at
                ) VALUES (
                    :id, :batchId, :lineNum, :valDate, :entryDate,
                    :dcMark, :amt, :transType,
                    :bankRef, :custRef, :details,
                    'UNMATCHED', :now
                )
            """)
                    .param("id", lineId)
                    .param("batchId", batchId)
                    .param("lineNum", line.lineNumber())
                    .param("valDate", line.valueDate())
                    .param("entryDate", line.entryDate())
                    .param("dcMark", line.debitCreditMark())
                    .param("amt", line.amountPesewas())
                    .param("transType", line.transactionTypeCode() != null ? line.transactionTypeCode() : "MSC")
                    .param("bankRef", line.bankReference())
                    .param("custRef", line.customerReference())
                    .param("details", line.supplementaryDetails())
                    .param("now", now)
                    .update();
        }

        return new BatchIngestionResult(
                batchId, batchRef, parsed.lines().size(),
                parsed.openingBalancePesewas(), parsed.closingBalancePesewas(), "INGESTED"
        );
    }

    private LocalDate parseDate6(String str) {
        try {
            return LocalDate.parse(str, DATE_FORMAT_6_DIGIT);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private LocalDate parseDate4(String yearPrefix, String mmdd) {
        try {
            return LocalDate.parse(yearPrefix + mmdd, DATE_FORMAT_6_DIGIT);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private long parseAmountPesewas(String amtStr) {
        if (amtStr == null || amtStr.isBlank()) return 0L;
        String normalized = amtStr.replace(',', '.').trim();
        return new java.math.BigDecimal(normalized).multiply(java.math.BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
    }

    private String calculateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
