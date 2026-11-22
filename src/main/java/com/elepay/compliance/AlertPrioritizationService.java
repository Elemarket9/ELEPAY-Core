package com.elepay.compliance;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Multi-factor alert prioritization engine (Algorithm 046).
 * Computes explainable priority scores for compliance cases across
 * rule severity, financial exposure, match confidence, and anti-starvation age weighting.
 */
public class AlertPrioritizationService {

    public enum PriorityTier {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    public record PrioritizationBreakdown(
            double severityPoints,
            double exposurePoints,
            double confidencePoints,
            double agePoints,
            double totalScore,
            PriorityTier calculatedTier
    ) {}

    public record PrioritizedCase(
            UUID caseId,
            String caseNumber,
            String caseType,
            UUID customerId,
            long exposurePesewas,
            double confidenceScore,
            String triggeredRule,
            Instant createdAt,
            PrioritizationBreakdown breakdown
    ) {}

    private final JdbcClient db;
    private final Map<UUID, PrioritizedCase> caseQueue = new ConcurrentHashMap<>();

    public AlertPrioritizationService(JdbcClient db) {
        this.db = db;
    }

    public PrioritizationBreakdown calculateScore(
            String basePriority,
            long exposurePesewas,
            double confidence,
            Instant createdAt
    ) {
        // 1. Severity Points (max 40)
        double severityPoints = switch (basePriority != null ? basePriority.toUpperCase() : "MEDIUM") {
            case "CRITICAL" -> 40.0;
            case "HIGH" -> 30.0;
            case "MEDIUM" -> 20.0;
            default -> 10.0;
        };

        // 2. Exposure Points (max 30) - 100,000 GHS = 10,000,000 pesewas; 20,000 GHS = 2,000,000 pesewas
        double exposurePoints;
        if (exposurePesewas >= 10_000_000L) {
            exposurePoints = 30.0;
        } else if (exposurePesewas >= 2_000_000L) {
            exposurePoints = 20.0;
        } else if (exposurePesewas >= 500_000L) {
            exposurePoints = 15.0;
        } else {
            exposurePoints = 10.0;
        }

        // 3. Confidence Points (max 20)
        double confidencePoints = Math.max(0.0, Math.min(1.0, confidence)) * 20.0;

        // 4. Age Points (max 15) - anti-starvation: +1 point per elapsed hour, capped at 15
        long hoursElapsed = Duration.between(createdAt != null ? createdAt : Instant.now(), Instant.now()).toHours();
        double agePoints = Math.min(15.0, Math.max(0.0, hoursElapsed * 1.0));

        double totalScore = severityPoints + exposurePoints + confidencePoints + agePoints;

        PriorityTier tier;
        if (totalScore >= 75.0) {
            tier = PriorityTier.CRITICAL;
        } else if (totalScore >= 55.0) {
            tier = PriorityTier.HIGH;
        } else if (totalScore >= 35.0) {
            tier = PriorityTier.MEDIUM;
        } else {
            tier = PriorityTier.LOW;
        }

        return new PrioritizationBreakdown(severityPoints, exposurePoints, confidencePoints, agePoints, totalScore, tier);
    }

    public PrioritizedCase enqueueCase(
            UUID caseId,
            String caseNumber,
            String caseType,
            UUID customerId,
            String basePriority,
            long exposurePesewas,
            double confidenceScore,
            String triggeredRule,
            Instant createdAt
    ) {
        PrioritizationBreakdown breakdown = calculateScore(basePriority, exposurePesewas, confidenceScore, createdAt);
        PrioritizedCase pCase = new PrioritizedCase(
                caseId,
                caseNumber,
                caseType,
                customerId,
                exposurePesewas,
                confidenceScore,
                triggeredRule,
                createdAt,
                breakdown
        );

        caseQueue.put(caseId, pCase);

        if (db != null) {
            try {
                db.sql("UPDATE compliance_case SET priority = ?, priority_score = ?, updated_at = now() WHERE id = ?")
                        .params(breakdown.calculatedTier().name(), breakdown.totalScore(), caseId)
                        .update();
            } catch (Exception ignored) {}
        }

        return pCase;
    }

    public List<PrioritizedCase> getPrioritizedQueue() {
        return caseQueue.values().stream()
                .sorted(Comparator.comparingDouble((PrioritizedCase c) -> c.breakdown().totalScore()).reversed())
                .toList();
    }
}
