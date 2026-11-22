package com.elepay.compliance;

import com.elepay.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AML transaction monitoring engine (Algorithm 045).
 * Evaluates transactional behavior against scenario-based rules (rapid flow-through,
 * volume spikes, high-risk jurisdictions, and dormant reactivation) to generate auditable cases.
 */
public class AmlMonitoringService {

    public enum AmlRule {
        RAPID_MOVEMENT,
        VELOCITY_SPIKE,
        HIGH_RISK_JURISDICTION,
        DORMANT_ACTIVATION
    }

    public enum CaseStatus {
        OPEN,
        INVESTIGATING,
        ESCALATED_STR,
        CLOSED_FALSE_POSITIVE
    }

    public record AmlCase(
            UUID id,
            String caseNumber,
            UUID customerId,
            AmlRule triggeredRule,
            String priority,
            double priorityScore,
            long exposurePesewas,
            double confidenceScore,
            CaseStatus status,
            String evidencePayload,
            UUID assignedTo,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record AmlEvent(
            UUID transactionId,
            UUID customerId,
            String direction, // "INFLOW" or "OUTFLOW"
            long amountPesewas,
            String currency,
            String destinationCountry,
            Instant lastActivityTimestamp,
            long averageDailyBaselinePesewas,
            Instant timestamp
    ) {}

    private final JdbcClient db;
    private final Map<UUID, AmlCase> cases = new ConcurrentHashMap<>();
    private static final Set<String> HIGH_RISK_JURISDICTIONS = Set.of("IR", "KP", "MM", "SY", "YE");

    public AmlMonitoringService(JdbcClient db) {
        this.db = db;
    }

    public List<AmlCase> evaluateTransaction(AmlEvent event, List<AmlEvent> recentCustomerEvents) {
        Objects.requireNonNull(event, "event cannot be null");
        List<AmlCase> generated = new ArrayList<>();

        // Rule 1: High-risk jurisdiction
        if (event.destinationCountry() != null && HIGH_RISK_JURISDICTIONS.contains(event.destinationCountry().toUpperCase())) {
            generated.add(createCase(
                    event.customerId(),
                    AmlRule.HIGH_RISK_JURISDICTION,
                    "CRITICAL",
                    90.0,
                    event.amountPesewas(),
                    0.95,
                    String.format("{\"jurisdiction\":\"%s\",\"amount\":%d}", event.destinationCountry(), event.amountPesewas())
            ));
        }

        // Rule 2: Dormant account reactivation (> 180 days inactive with txn > 10,000 GHS = 1,000,000 pesewas)
        if (event.lastActivityTimestamp() != null) {
            long daysInactive = Duration.between(event.lastActivityTimestamp(), event.timestamp()).toDays();
            if (daysInactive >= 180 && event.amountPesewas() >= 1_000_000L) {
                generated.add(createCase(
                        event.customerId(),
                        AmlRule.DORMANT_ACTIVATION,
                        "HIGH",
                        75.0,
                        event.amountPesewas(),
                        0.85,
                        String.format("{\"daysInactive\":%d,\"amount\":%d}", daysInactive, event.amountPesewas())
                ));
            }
        }

        // Rule 3: Velocity Spike (> 4x average daily baseline)
        if (event.averageDailyBaselinePesewas() > 0) {
            if (event.amountPesewas() >= event.averageDailyBaselinePesewas() * 4) {
                generated.add(createCase(
                        event.customerId(),
                        AmlRule.VELOCITY_SPIKE,
                        "MEDIUM",
                        60.0,
                        event.amountPesewas(),
                        0.80,
                        String.format("{\"baseline\":%d,\"spikeAmount\":%d}", event.averageDailyBaselinePesewas(), event.amountPesewas())
                ));
            }
        }

        // Rule 4: Rapid Movement of Funds (Inflow followed by > 80% outflow within 15 minutes)
        if ("OUTFLOW".equalsIgnoreCase(event.direction()) && recentCustomerEvents != null) {
            Instant fifteenMinutesAgo = event.timestamp().minus(Duration.ofMinutes(15));
            for (AmlEvent prior : recentCustomerEvents) {
                if ("INFLOW".equalsIgnoreCase(prior.direction()) && prior.timestamp().isAfter(fifteenMinutesAgo)) {
                    if (event.amountPesewas() >= (long) (prior.amountPesewas() * 0.80)) {
                        generated.add(createCase(
                                event.customerId(),
                                AmlRule.RAPID_MOVEMENT,
                                "HIGH",
                                70.0,
                                event.amountPesewas(),
                                0.85,
                                String.format("{\"inflow\":%d,\"outflow\":%d,\"deltaMinutes\":%d}",
                                        prior.amountPesewas(), event.amountPesewas(), Duration.between(prior.timestamp(), event.timestamp()).toMinutes())
                        ));
                        break;
                    }
                }
            }
        }

        return generated;
    }

    public AmlCase updateCaseStatus(UUID caseId, CaseStatus newStatus, UUID analystId) {
        AmlCase existing = cases.get(caseId);
        if (existing == null) {
            throw ApiException.notFound("AML case not found: " + caseId);
        }

        Instant now = Instant.now();
        AmlCase updated = new AmlCase(
                existing.id(),
                existing.caseNumber(),
                existing.customerId(),
                existing.triggeredRule(),
                existing.priority(),
                existing.priorityScore(),
                existing.exposurePesewas(),
                existing.confidenceScore(),
                newStatus,
                existing.evidencePayload(),
                analystId != null ? analystId : existing.assignedTo(),
                existing.createdAt(),
                now
        );

        cases.put(caseId, updated);

        if (db != null) {
            try {
                db.sql("UPDATE compliance_case SET status = ?, assigned_to = ?, updated_at = ? WHERE id = ?")
                        .params(newStatus.name(), analystId, now, caseId)
                        .update();
            } catch (Exception ignored) {}
        }

        return updated;
    }

    private AmlCase createCase(UUID customerId, AmlRule rule, String priority, double priorityScore, long exposure, double confidence, String evidence) {
        UUID id = UUID.randomUUID();
        String caseNumber = "AML-" + rule.name().substring(0, 4) + "-" + System.currentTimeMillis() % 1_000_000;
        Instant now = Instant.now();
        AmlCase c = new AmlCase(id, caseNumber, customerId, rule, priority, priorityScore, exposure, confidence, CaseStatus.OPEN, evidence, null, now, now);
        cases.put(id, c);

        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO compliance_case
                    (id, case_number, case_type, customer_id, priority, priority_score, exposure_amount_pesewas, confidence_score, triggered_rule, status, evidence_payload, created_at, updated_at)
                    VALUES (?, ?, 'AML_MONITORING', ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                    """)
                    .params(id, caseNumber, customerId, priority, priorityScore, exposure, confidence, rule.name(), evidence, now, now)
                    .update();
            } catch (Exception ignored) {}
        }

        return c;
    }
}
