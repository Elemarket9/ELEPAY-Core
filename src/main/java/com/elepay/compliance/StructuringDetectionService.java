package com.elepay.compliance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Structuring (smurfing) detection engine (Algorithm 044).
 * Identifies transaction splitting patterns designed to evade regulatory
 * Cash Transaction Reporting (CTR) thresholds (e.g. Bank of Ghana / FIC 20,000 GHS threshold)
 * across configurable rolling time windows.
 */
public class StructuringDetectionService {

    public record TransactionRecord(
            UUID transactionId,
            UUID customerId,
            long amountPesewas,
            String currency,
            Instant timestamp
    ) {}

    public record StructuringAlert(
            UUID caseId,
            String caseNumber,
            UUID customerId,
            long aggregatePesewas,
            long reportingThresholdPesewas,
            int transactionCount,
            double structuringConfidence,
            List<UUID> transactionIds,
            Instant detectedAt
    ) {}

    private final JdbcClient db;
    private final long reportingThresholdPesewas; // Default: 20,000 GHS = 2,000,000 pesewas
    private final Duration rollingWindow;         // Default: 24 hours
    private final double smurfRatioMin;           // 0.70 of threshold
    private final double smurfRatioMax;           // 0.99 of threshold
    private final int minTxnCount;                // Minimum 3 transactions

    public StructuringDetectionService(JdbcClient db) {
        this(db, 2_000_000L, Duration.ofHours(24), 0.70, 0.99, 3);
    }

    public StructuringDetectionService(
            JdbcClient db,
            long reportingThresholdPesewas,
            Duration rollingWindow,
            double smurfRatioMin,
            double smurfRatioMax,
            int minTxnCount
    ) {
        this.db = db;
        this.reportingThresholdPesewas = reportingThresholdPesewas;
        this.rollingWindow = rollingWindow;
        this.smurfRatioMin = smurfRatioMin;
        this.smurfRatioMax = smurfRatioMax;
        this.minTxnCount = minTxnCount;
    }

    public StructuringAlert evaluateCustomerTransactions(UUID customerId, List<TransactionRecord> transactions) {
        Objects.requireNonNull(customerId, "customerId cannot be null");
        if (transactions == null || transactions.size() < minTxnCount) {
            return null;
        }

        Instant now = Instant.now();
        Instant windowStart = now.minus(rollingWindow);

        // Filter transactions in rolling window for currency GHS
        List<TransactionRecord> windowTxns = transactions.stream()
                .filter(t -> t.customerId().equals(customerId))
                .filter(t -> t.timestamp().isAfter(windowStart))
                .filter(t -> "GHS".equalsIgnoreCase(t.currency()))
                .toList();

        if (windowTxns.size() < minTxnCount) {
            return null;
        }

        long totalPesewas = 0;
        int subThresholdCount = 0;
        long lowerBound = (long) (reportingThresholdPesewas * smurfRatioMin);
        long upperBound = (long) (reportingThresholdPesewas * smurfRatioMax);
        List<UUID> matchedTxnIds = new ArrayList<>();

        for (TransactionRecord t : windowTxns) {
            totalPesewas += t.amountPesewas();
            matchedTxnIds.add(t.transactionId());
            if (t.amountPesewas() >= lowerBound && t.amountPesewas() <= upperBound) {
                subThresholdCount++;
            }
        }

        // Smurfing Pattern 1: Multiple individual transactions clustered just below the threshold
        boolean patternJustBelowThreshold = subThresholdCount >= 2;
        // Smurfing Pattern 2: Aggregate volume exceeds CTR reporting threshold while each individual tx < threshold
        boolean patternAggregateExceeded = totalPesewas >= reportingThresholdPesewas
                && windowTxns.stream().allMatch(t -> t.amountPesewas() < reportingThresholdPesewas);

        if (!patternJustBelowThreshold && !patternAggregateExceeded) {
            return null;
        }

        double confidence = 0.50;
        if (patternJustBelowThreshold) confidence += 0.25;
        if (patternAggregateExceeded) confidence += 0.25;
        confidence = Math.min(1.0, confidence);

        UUID caseId = UUID.randomUUID();
        String caseNumber = "STRUC-" + System.currentTimeMillis() % 1_000_000;
        StructuringAlert alert = new StructuringAlert(
                caseId,
                caseNumber,
                customerId,
                totalPesewas,
                reportingThresholdPesewas,
                windowTxns.size(),
                confidence,
                matchedTxnIds,
                now
        );

        if (db != null) {
            try {
                String evidence = String.format("{\"threshold\":%d,\"total\":%d,\"txnCount\":%d,\"txns\":%s}",
                        reportingThresholdPesewas, totalPesewas, windowTxns.size(), matchedTxnIds.toString());
                db.sql("""
                    INSERT INTO compliance_case
                    (id, case_number, case_type, customer_id, priority, priority_score, exposure_amount_pesewas, confidence_score, triggered_rule, status, evidence_payload, created_at, updated_at)
                    VALUES (?, ?, 'STRUCTURING', ?, 'HIGH', 75.0, ?, ?, 'SMURFING_ROLLING_WINDOW', 'OPEN', ?, now(), now())
                    """)
                    .params(caseId, caseNumber, customerId, totalPesewas, confidence, evidence)
                    .update();
            } catch (Exception ignored) {}
        }

        return alert;
    }
}
