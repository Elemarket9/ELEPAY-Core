package com.elepay.security;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.identity.KillSwitchService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Mandatory Maker-Checker Dual Control Service (Dead-End Gate 2).
 *
 * <p>No single ops/admin user can approve large payouts, override escrow/reserve holds,
 * force settlement releases, change settlement rules, or unfreeze global kill switches alone.
 * A single stolen admin account can only propose — never execute. Self-approval throws
 * {@code 403 Forbidden} ({@code maker_checker_self_approval}) and records an
 * {@link AdminAnomalyDetector.AnomalyType#SELF_APPROVAL} audit event.
 */
@Service
public class MakerCheckerService {
    public static final Set<String> ACTION_TYPES = Set.of(
            "LARGE_PAYOUT",
            "ESCROW_RELEASE",
            "FORCE_SETTLEMENT",
            "DISPUTE_RESOLUTION",
            "SETTLEMENT_RULE_CHANGE",
            "FEE_SCHEDULE_CHANGE",
            "DESTINATION_OPS_VERIFY",
            "KILL_SWITCH_CHANGE",
            "VELOCITY_CEILING_CHANGE",
            "PHONE_CHANGE",
            "CUSTODIAN_CONFIG",
            "MODULE_ENABLE",
            "MERCHANT_UNFREEZE"
    );

    private final JdbcClient db;
    private final Audit audit;
    private final OpsQueue ops;
    private final ObjectMapper json;
    private final KillSwitchService killSwitchService;
    private final java.util.List<ApprovedActionHandler> handlers;
    private final AdminAnomalyDetector anomalyDetector;
    private final TransactionTemplate requiresNewTx;

    public MakerCheckerService(
            JdbcClient db,
            Audit audit,
            OpsQueue ops,
            ObjectMapper json,
            KillSwitchService killSwitchService,
            java.util.List<ApprovedActionHandler> handlers
    ) {
        this(db, audit, ops, json, killSwitchService, handlers, null);
    }

    @Autowired
    public MakerCheckerService(
            JdbcClient db,
            Audit audit,
            OpsQueue ops,
            ObjectMapper json,
            @Autowired(required = false) KillSwitchService killSwitchService,
            @Autowired(required = false) java.util.List<ApprovedActionHandler> handlers,
            @Autowired(required = false) PlatformTransactionManager txManager
    ) {
        this.db = db;
        this.audit = audit;
        this.ops = ops;
        this.json = json;
        this.killSwitchService = killSwitchService;
        this.handlers = handlers == null ? java.util.List.of() : handlers;
        this.anomalyDetector = new AdminAnomalyDetector(db);
        if (txManager != null) {
            TransactionTemplate template = new TransactionTemplate(txManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = template;
        } else {
            this.requiresNewTx = null;
        }
    }

    public record Proposal(
            UUID id,
            String actionType,
            String resourceType,
            String resourceId,
            UUID merchantId,
            Map<String, Object> payload,
            String reason,
            String status,
            String proposedBy,
            OffsetDateTime proposedAt,
            String reviewedBy,
            OffsetDateTime reviewedAt,
            String reviewNote,
            OffsetDateTime expiresAt
    ) {}

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Proposal propose(
            String actionType,
            String resourceType,
            String resourceId,
            UUID merchantId,
            Map<String, Object> payload,
            String reason,
            String proposerSubject
    ) {
        if (actionType == null || !ACTION_TYPES.contains(actionType.toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("invalid_action_type", "Valid action types: " + ACTION_TYPES);
        }
        if (proposerSubject == null || proposerSubject.isBlank()) {
            throw ApiException.badRequest("missing_proposer", "Proposer subject is required");
        }
        String normalizedType = actionType.toUpperCase(Locale.ROOT);
        Map<String, Object> safePayload = payload != null ? payload : Map.of();
        validateProposalPayload(normalizedType, resourceId, merchantId, safePayload);
        UUID id = UUID.randomUUID();
        String payloadJson = json.writeValueAsString(safePayload);

        db.sql("""
                INSERT INTO maker_checker_proposal (
                    id, action_type, resource_type, resource_id, merchant_id,
                    payload, reason, status, proposed_by, proposed_at, expires_at
                ) VALUES (
                    :id, :action, :rt, :rid, :mid,
                    CAST(:payload AS jsonb), :reason, 'PENDING', :by, now(), now() + interval '24 hours'
                )""")
                .param("id", id)
                .param("action", normalizedType)
                .param("rt", resourceType != null ? resourceType : "resource")
                .param("rid", resourceId != null ? resourceId : id.toString())
                .param("mid", merchantId)
                .param("payload", payloadJson)
                .param("reason", reason)
                .param("by", proposerSubject)
                .update();

        if (audit != null) {
            audit.record(proposerSubject, "maker_checker.proposed",
                    normalizedType + ":" + resourceId,
                    Map.of("proposalId", id.toString(), "actionType", normalizedType,
                            "resourceType", String.valueOf(resourceType), "resourceId", String.valueOf(resourceId)));
        }

        return getProposal(id);
    }

    @Transactional
    public Proposal approve(UUID proposalId, String approverSubject, String note) {
        Proposal existing = lockProposal(proposalId);
        if (!"PENDING".equals(existing.status())) {
            throw ApiException.conflict("proposal_not_pending",
                    "Maker-checker proposal is already in terminal state: " + existing.status());
        }
        if (existing.expiresAt() != null && existing.expiresAt().isBefore(OffsetDateTime.now())) {
            db.sql("UPDATE maker_checker_proposal SET status = 'EXPIRED' WHERE id = :id")
                    .param("id", proposalId).update();
            throw ApiException.conflict("proposal_expired", "Maker-checker proposal has expired");
        }

        // Core Invariant: proposed_by != approved_by
        if (existing.proposedBy().equals(approverSubject)) {
            runInNewTransaction(() -> recordSelfApprovalAttempt(proposalId, existing, approverSubject));
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "maker_checker_self_approval",
                    "Dual control violation: Maker (" + existing.proposedBy() + ") cannot approve their own proposal");
        }

        db.sql("""
                UPDATE maker_checker_proposal
                SET status = 'APPROVED',
                    reviewed_by = :by,
                    reviewed_at = now(),
                    review_note = :note,
                    executed_at = now()
                WHERE id = :id""")
                .param("by", approverSubject)
                .param("note", note)
                .param("id", proposalId)
                .update();

        Proposal approved = getProposal(proposalId);
        for (ApprovedActionHandler handler : handlers) {
            if (handler.supports(approved.actionType())) handler.execute(approved);
        }

        if (audit != null) {
            audit.record(approverSubject, "maker_checker.approved",
                    "proposal:" + proposalId,
                    Map.of("actionType", existing.actionType(),
                            "maker", existing.proposedBy(),
                            "checker", approverSubject));
        }

        runAfterCommit(() -> applyKillSwitchMutation(approved, approverSubject));

        return getProposal(proposalId);
    }

    private void applyKillSwitchMutation(Proposal approved, String approverSubject) {
        if (killSwitchService == null) return;
        if ("KILL_SWITCH_CHANGE".equals(approved.actionType())) {
            Object target = approved.payload().get("target");
            Object frozenObj = approved.payload().get("frozen");
            boolean frozen = frozenObj instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(frozenObj));
            if ("global".equalsIgnoreCase(String.valueOf(target))) {
                killSwitchService.setGlobalFrozen(frozen, approved.reason(), approverSubject);
            } else if ("merchant".equalsIgnoreCase(String.valueOf(target)) && approved.merchantId() != null) {
                killSwitchService.setMerchantFrozen(approved.merchantId(), frozen, approved.reason(), approverSubject);
            } else if ("module".equalsIgnoreCase(String.valueOf(target))) {
                killSwitchService.setModuleEnabled(String.valueOf(approved.payload().get("module")), !frozen,
                        approved.reason(), approverSubject);
            }
        } else if ("MERCHANT_UNFREEZE".equals(approved.actionType()) && approved.merchantId() != null) {
            killSwitchService.setMerchantFrozen(approved.merchantId(), false, approved.reason(), approverSubject);
        } else if ("MODULE_ENABLE".equals(approved.actionType())) {
            killSwitchService.setModuleEnabled(String.valueOf(approved.payload().get("module")), true,
                    approved.reason(), approverSubject);
        }
    }

    private static void runAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    static void validateProposalPayload(String actionType, String resourceId, UUID merchantId, Map<String, Object> payload) {
        switch (actionType) {
            case "KILL_SWITCH_CHANGE" -> {
                Object targetObj = payload.get("target");
                String target = targetObj == null ? "" : String.valueOf(targetObj).trim();
                if (!Set.of("global", "merchant", "module").contains(target.toLowerCase(Locale.ROOT))) {
                    throw ApiException.badRequest("invalid_proposal_payload", "KILL_SWITCH_CHANGE requires payload.target in {global, merchant, module}");
                }
                if (!(payload.get("frozen") instanceof Boolean)) {
                    throw ApiException.badRequest("invalid_proposal_payload", "KILL_SWITCH_CHANGE requires boolean payload.frozen");
                }
                if ("merchant".equalsIgnoreCase(target) && merchantId == null) {
                    throw ApiException.badRequest("invalid_proposal_payload", "KILL_SWITCH_CHANGE target=merchant requires merchantId");
                }
                if ("module".equalsIgnoreCase(target)) {
                    Object mod = payload.get("module");
                    if (mod == null || String.valueOf(mod).isBlank()) {
                        throw ApiException.badRequest("invalid_proposal_payload", "KILL_SWITCH_CHANGE target=module requires non-blank payload.module");
                    }
                }
            }
            case "MODULE_ENABLE" -> {
                Object mod = payload.get("module");
                if (mod == null || String.valueOf(mod).isBlank()) {
                    throw ApiException.badRequest("invalid_proposal_payload", "MODULE_ENABLE requires non-blank payload.module");
                }
            }
            case "MERCHANT_UNFREEZE" -> {
                if (merchantId == null) {
                    throw ApiException.badRequest("invalid_proposal_payload", "MERCHANT_UNFREEZE requires merchantId");
                }
            }
            case "ESCROW_RELEASE" -> {
                if (merchantId == null) {
                    throw ApiException.badRequest("invalid_proposal_payload", "ESCROW_RELEASE requires merchantId");
                }
                Object ref = payload.get("reference");
                if (ref == null || String.valueOf(ref).isBlank() || "null".equalsIgnoreCase(String.valueOf(ref).trim())) {
                    throw ApiException.badRequest("invalid_proposal_payload", "ESCROW_RELEASE requires non-blank payload.reference");
                }
                if (parseWholeNonNegativeInt(payload.get("line")) == null) {
                    throw ApiException.badRequest("invalid_proposal_payload", "ESCROW_RELEASE requires non-negative integer payload.line");
                }
                Long amt = parseWholePositiveLong(payload.get("amount"));
                if (amt == null || amt <= 0) {
                    throw ApiException.badRequest("invalid_proposal_payload", "ESCROW_RELEASE requires positive integer payload.amount");
                }
            }
            default -> {
                if ((resourceId == null || resourceId.isBlank()) && payload.isEmpty()) {
                    throw ApiException.badRequest("invalid_proposal_payload", actionType + " requires a resourceId or non-empty payload");
                }
            }
        }
    }

    private static Integer parseWholeNonNegativeInt(Object value) {
        Long whole = parseWholeLong(value);
        if (whole == null || whole < 0 || whole > Integer.MAX_VALUE) {
            return null;
        }
        return whole.intValue();
    }

    private static Long parseWholePositiveLong(Object value) {
        Long whole = parseWholeLong(value);
        return (whole != null && whole > 0) ? whole : null;
    }

    private static Long parseWholeLong(Object value) {
        if (value == null) return null;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof java.math.BigInteger bi) {
            try {
                return bi.longValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
        }
        if (value instanceof java.math.BigDecimal bd) {
            try {
                return bd.longValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d)) return null;
            try {
                return java.math.BigDecimal.valueOf(d).longValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
        }
        if (value instanceof Number n) {
            try {
                return new java.math.BigDecimal(n.toString()).longValueExact();
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return null;
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Transactional
    public Proposal reject(UUID proposalId, String reviewerSubject, String note) {
        Proposal existing = lockProposal(proposalId);
        if (!"PENDING".equals(existing.status())) {
            throw ApiException.conflict("proposal_not_pending",
                    "Maker-checker proposal is already in terminal state: " + existing.status());
        }
        db.sql("""
                UPDATE maker_checker_proposal
                SET status = 'REJECTED',
                    reviewed_by = :by,
                    reviewed_at = now(),
                    review_note = :note
                WHERE id = :id""")
                .param("by", reviewerSubject)
                .param("note", note)
                .param("id", proposalId)
                .update();

        if (audit != null) {
            audit.record(reviewerSubject, "maker_checker.rejected",
                    "proposal:" + proposalId,
                    Map.of("actionType", existing.actionType(), "maker", existing.proposedBy()));
        }
        return getProposal(proposalId);
    }

    public Proposal getProposal(UUID id) {
        return db.sql("""
                SELECT id, action_type, resource_type, resource_id, merchant_id,
                       payload::text, reason, status, proposed_by, proposed_at,
                       reviewed_by, reviewed_at, review_note, expires_at
                FROM maker_checker_proposal WHERE id = :id""")
                .param("id", id)
                .query(this::mapProposal)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Maker-checker proposal not found: " + id));
    }

    public List<Proposal> listProposals(String status) {
        boolean filter = status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status);
        String sql = """
                SELECT id, action_type, resource_type, resource_id, merchant_id,
                       payload::text, reason, status, proposed_by, proposed_at,
                       reviewed_by, reviewed_at, review_note, expires_at
                FROM maker_checker_proposal"""
                + (filter ? " WHERE status = :st" : "")
                + " ORDER BY proposed_at DESC LIMIT 200";
        var client = db.sql(sql);
        if (filter) client = client.param("st", status.toUpperCase(Locale.ROOT));
        return client.query(this::mapProposal).list();
    }

    private Proposal lockProposal(UUID id) {
        return db.sql("""
                SELECT id, action_type, resource_type, resource_id, merchant_id,
                       payload::text, reason, status, proposed_by, proposed_at,
                       reviewed_by, reviewed_at, review_note, expires_at
                FROM maker_checker_proposal WHERE id = :id FOR UPDATE""")
                .param("id", id)
                .query(this::mapProposal)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Maker-checker proposal not found: " + id));
    }

    @SuppressWarnings("unchecked")
    private Proposal mapProposal(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        String rawPayload = rs.getString(6);
        Map<String, Object> payloadMap = (rawPayload == null || rawPayload.isBlank())
                ? Map.of()
                : json.readValue(rawPayload, Map.class);
        return new Proposal(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getObject(5, UUID.class),
                payloadMap,
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getObject(10, OffsetDateTime.class),
                rs.getString(11),
                rs.getObject(12, OffsetDateTime.class),
                rs.getString(13),
                rs.getObject(14, OffsetDateTime.class)
        );
    }

    private void runInNewTransaction(Runnable action) {
        if (requiresNewTx != null) {
            requiresNewTx.executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    private void recordSelfApprovalAttempt(UUID proposalId, Proposal existing, String approverSubject) {
        UUID operatorUuid = toUuid(approverSubject);
        anomalyDetector.detectAnomalies(new AdminAnomalyDetector.AdminActionEvent(
                UUID.randomUUID(),
                operatorUuid,
                "ops_admin",
                "APPROVE_" + existing.actionType(),
                existing.resourceType(),
                existing.resourceId(),
                "127.0.0.1",
                "MakerCheckerService",
                null,
                null,
                operatorUuid,
                Instant.now()
        ));
        if (audit != null) {
            audit.record(approverSubject, "maker_checker.self_approval_blocked",
                    "proposal:" + proposalId,
                    Map.of("actionType", existing.actionType(), "anomaly", AdminAnomalyDetector.AnomalyType.SELF_APPROVAL.name()));
        }
        if (ops != null) {
            ops.raise("maker_checker_self_approval_attempt", "critical", "proposal:" + proposalId,
                    Map.of("actor", approverSubject, "actionType", existing.actionType()));
        }
    }

    private static UUID toUuid(String subject) {
        if (subject == null) return UUID.randomUUID();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8));
        }
    }
}
