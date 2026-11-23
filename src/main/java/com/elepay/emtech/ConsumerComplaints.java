package com.elepay.emtech;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer complaints beyond checkout disputes: fraud, service error, downtime and misconduct.
 * Every status, including REOPENED and PENDING_RESOLUTION, is its own Emtech submission
 * and keeps the same complaint id.
 */
@Service
public class ConsumerComplaints {
    public static final Set<String> TYPES = Set.of(
            "FRAUD", "DISPUTE", "SERVICE_ERROR", "SYSTEM_DOWNTIME", "MISCONDUCT OTHER");
    public static final Set<String> STATUSES = Set.of(
            "OPENED", "CLOSED", "REOPENED", "PENDING_RESOLUTION", "RESOLVED");

    private final JdbcClient db;
    private final EmtechReporter reporter;

    public ConsumerComplaints(JdbcClient db, EmtechReporter reporter) {
        this.db = db;
        this.reporter = reporter;
    }

    public record Complaint(UUID id, String userId, String type, String status, OffsetDateTime statusAt) {}

    @Transactional
    public Complaint open(UUID customerId, String userId, String type, String description, String openedBy) {
        requireType(type);
        return insert(customerId, userId, type, description, "OPENED", null, null, openedBy, OffsetDateTime.now());
    }

    @Transactional
    public Complaint transition(UUID id, String status, String resolution, String actor) {
        requireStatus(status);
        var current = db.sql("""
                SELECT user_id, complaint_type, type_description, status FROM consumer_complaint WHERE id = :id FOR UPDATE""")
                .param("id", id)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})
                .optional().orElseThrow(() -> com.elepay.common.ApiException.notFound("Complaint not found"));
        if (!allowed(current[3], status)) {
            throw com.elepay.common.ApiException.conflict("invalid_transition",
                    "Cannot move a complaint from " + current[3] + " to " + status);
        }
        OffsetDateTime when = OffsetDateTime.now();
        db.sql("""
                UPDATE consumer_complaint
                SET status = :status, status_at = :at, resolution_description = coalesce(:resolution, resolution_description),
                    updated_at = now()
                WHERE id = :id""")
                .param("status", status).param("at", when).param("resolution", resolution).param("id", id).update();
        reporter.complaint(new RegulatoryFacts.Complaint(current[0], id.toString(), current[1], current[2],
                status, EmtechReporter.instant(when), resolution));
        return new Complaint(id, current[0], current[1], status, when);
    }

    /** Checkout disputes are reported as DISPUTE complaints, one submission per status. */
    @Transactional
    public void syncDispute(UUID disputeId, UUID customerId, String userId, String description, String status,
                            String resolution, OffsetDateTime when, String actor) {
        OffsetDateTime at = when == null ? OffsetDateTime.now() : when;
        var existing = db.sql("SELECT id FROM consumer_complaint WHERE dispute_id = :id")
                .param("id", disputeId).query(UUID.class).optional();
        if (existing.isEmpty()) {
            insert(customerId, userId, "DISPUTE", description, status, resolution, disputeId, actor, at);
            return;
        }
        db.sql("""
                UPDATE consumer_complaint
                SET status = :status, status_at = :at, resolution_description = :resolution, updated_at = now()
                WHERE id = :id""")
                .param("status", status).param("at", at).param("resolution", resolution).param("id", existing.get()).update();
        reporter.complaint(new RegulatoryFacts.Complaint(userId, existing.get().toString(), "DISPUTE", description,
                status, EmtechReporter.instant(at), resolution));
    }

    private Complaint insert(UUID customerId, String userId, String type, String description, String status,
                             String resolution, UUID disputeId, String openedBy, OffsetDateTime when) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO consumer_complaint
                    (id, customer_id, user_id, complaint_type, type_description, status, status_at,
                     resolution_description, dispute_id, opened_by)
                VALUES (:id, :customer, :user, :type, :description, :status, :at, :resolution, :dispute, :by)""")
                .param("id", id).param("customer", customerId).param("user", userId).param("type", type)
                .param("description", description).param("status", status).param("at", when)
                .param("resolution", resolution).param("dispute", disputeId).param("by", openedBy).update();
        reporter.complaint(new RegulatoryFacts.Complaint(userId, id.toString(), type, description, status,
                EmtechReporter.instant(when), resolution));
        return new Complaint(id, userId, type, status, when);
    }

    public static void requireType(String type) {
        if (!TYPES.contains(type)) {
            throw com.elepay.common.ApiException.badRequest("invalid_complaint_type",
                    "complaintType must be FRAUD, DISPUTE, SERVICE_ERROR, SYSTEM_DOWNTIME or MISCONDUCT OTHER");
        }
    }

    public static void requireStatus(String status) {
        if (!STATUSES.contains(status)) {
            throw com.elepay.common.ApiException.badRequest("invalid_complaint_status",
                    "complaintStatus must be OPENED, CLOSED, REOPENED, PENDING_RESOLUTION or RESOLVED");
        }
    }

    static boolean allowed(String from, String to) {
        if (from.equals(to)) return false;
        return switch (from) {
            case "OPENED" -> Set.of("PENDING_RESOLUTION", "RESOLVED", "CLOSED").contains(to);
            case "PENDING_RESOLUTION" -> Set.of("RESOLVED", "CLOSED", "REOPENED").contains(to);
            case "REOPENED" -> Set.of("PENDING_RESOLUTION", "RESOLVED", "CLOSED").contains(to);
            case "RESOLVED", "CLOSED" -> "REOPENED".equals(to);
            default -> false;
        };
    }
}
