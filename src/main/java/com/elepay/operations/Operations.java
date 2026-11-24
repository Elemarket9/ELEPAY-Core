package com.elepay.operations;

import com.elepay.common.OpsQueue;
import com.elepay.identity.Actor;
import com.elepay.ledger.Ledger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/** Operations console APIs: exception queue, ledger integrity, overview and audit trail. */
@RestController
@RequestMapping("/v1/ops")
class Operations {
    private final OpsQueue queue;
    private final Ledger ledger;
    private final JdbcClient db;

    Operations(OpsQueue queue, Ledger ledger, JdbcClient db) {
        this.queue = queue;
        this.ledger = ledger;
        this.db = db;
    }

    record Resolution(@NotBlank @Size(max = 1000) String resolution) {}

    @GetMapping("/queue")
    public List<OpsQueue.Item> list(@RequestParam(defaultValue = "open") String status) {
        Actor.current().requireOps();
        return queue.list(status);
    }

    @PostMapping("/queue/{id}/resolve")
    public Map<String, String> resolve(@PathVariable UUID id, @Valid @RequestBody Resolution request) {
        Actor actor = Actor.current();
        actor.requireOps();
        var item = queue.find(id).orElseThrow(() -> com.elepay.common.ApiException.notFound("Ops item not found"));
        String kind = item.kind() == null ? "" : item.kind().toLowerCase(java.util.Locale.ROOT);
        boolean sensitive = "high".equalsIgnoreCase(item.severity())
                || kind.contains("recon") || kind.contains("rail") || kind.contains("payout")
                || kind.contains("custodian") || kind.contains("security") || kind.contains("fraud")
                || kind.contains("ledger") || kind.contains("audit");
        if (sensitive && !actor.canManageFinance() && !actor.canFreezeRisk()) {
            throw com.elepay.common.ApiException.forbidden("Finance or Risk/Compliance role required to resolve this exception");
        }
        queue.resolve(id, actor.subject(), request.resolution());
        return Map.of("status", "resolved");
    }

    /** Every balance must equal the sum of its journal entries and every currency must net to zero. */
    @GetMapping("/ledger/integrity")
    public Ledger.Integrity integrity() {
        Actor.current().requireOps();
        return ledger.integrity();
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Actor.current().requireOps();
        return db.sql("""
                SELECT (SELECT count(*) FROM ops_item WHERE status = 'open') AS open_items,
                       (SELECT count(*) FROM merchant WHERE status = 'pending_review') AS merchants_pending_review,
                       (SELECT count(*) FROM rail_transaction WHERE status IN ('created', 'pending', 'unknown')) AS rail_open,
                       (SELECT count(*) FROM dispute WHERE status = 'open') AS disputes_open,
                       (SELECT count(*) FROM outbox_event WHERE published_at IS NULL) AS outbox_backlog,
                       (SELECT count(*) FROM webhook_delivery WHERE status = 'dead') AS webhooks_dead,
                       (SELECT coalesce(sum(total_paid), 0) FROM checkout WHERE paid_at > now() - interval '1 day') AS paid_24h,
                       (SELECT count(*) FROM checkout WHERE paid_at > now() - interval '1 day') AS checkouts_24h""")
                .query().singleRow();
    }

    @GetMapping("/disputes")
    public List<Map<String, Object>> disputes(@RequestParam(defaultValue = "open") String status) {
        Actor.current().requireOps();
        return db.sql("""
                SELECT d.id, c.reference AS checkout, d.line, d.amount, d.reason, d.status, d.created_at, m.trading_name AS merchant
                FROM dispute d JOIN checkout c ON c.id = d.checkout_id
                JOIN checkout_allocation a ON a.checkout_id = d.checkout_id AND a.line = d.line
                JOIN merchant m ON m.id = a.merchant_id WHERE d.status = :s ORDER BY d.created_at DESC LIMIT 200""")
                .param("s", status).query().listOfRows();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam String subject) {
        Actor actor = Actor.current();
        if (!actor.canFreezeRisk()) {
            throw com.elepay.common.ApiException.forbidden("Risk/Compliance or Operations Administrator role required for audit log");
        }
        if (subject == null || subject.isBlank()) {
            throw com.elepay.common.ApiException.badRequest("blank_subject", "Audit subject must not be blank");
        }
        String trimmedSubject = subject.trim();
        String normalized = trimmedSubject.startsWith("customer:") ? trimmedSubject.substring("customer:".length()) : trimmedSubject;
        return db.sql("""
                SELECT actor, action, subject, details::text AS details, created_at
                FROM audit_log
                WHERE subject = :s OR subject = :norm OR subject = ('customer:' || :norm)
                ORDER BY id DESC LIMIT 200""")
                .param("s", trimmedSubject).param("norm", normalized).query().listOfRows();
    }

    @GetMapping("/reconciliation/runs")
    public List<Map<String, Object>> runs() {
        Actor.current().requireOps();
        return db.sql("SELECT id, rail, period, imported_by, summary::text AS summary, created_at FROM reconciliation_run ORDER BY created_at DESC LIMIT 100")
                .query().listOfRows();
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        Actor actor = Actor.current();
        actor.requireOps();
        return Map.of(
                "subject", actor.subject(),
                "roles", actor.roles(),
                "primaryRole", actor.primaryRole(),
                "permissions", Map.of(
                        "canApproveMakerChecker", actor.isOpsAdmin(),
                        "canFreezeRisk", actor.canFreezeRisk(),
                        "canManageFinance", actor.canManageFinance(),
                        "canOnboardMerchants", actor.canOnboardMerchants(),
                        "canManageSupport", actor.canManageSupport()
                )
        );
    }

    @GetMapping("/merchants/search")
    public List<Map<String, Object>> searchMerchants(@RequestParam(defaultValue = "") String q) {
        Actor.current().requireOps();
        String trimmed = q == null ? "" : q.trim();
        String escaped = trimmed.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String like = "%" + escaped + "%";
        return db.sql("""
                SELECT m.id, m.merchant_code, m.legal_name, m.trading_name, m.status, m.contact_phone,
                       m.fee_bps, m.settlement_delay_hours, m.created_at,
                       COALESCE(k.payouts_frozen, false) AS frozen, k.reason AS freeze_reason
                FROM merchant m
                LEFT JOIN merchant_kill_switch k ON k.merchant_id = m.id
                WHERE (:empty = true
                       OR lower(m.trading_name) LIKE :like ESCAPE '\\'
                       OR lower(m.legal_name) LIKE :like ESCAPE '\\'
                       OR lower(COALESCE(m.merchant_code, '')) LIKE :like ESCAPE '\\'
                       OR lower(COALESCE(m.contact_phone, '')) LIKE :like ESCAPE '\\'
                       OR m.id::text = :exact)
                ORDER BY m.created_at DESC
                LIMIT 50""")
                .param("empty", trimmed.isEmpty())
                .param("like", like)
                .param("exact", trimmed)
                .query().listOfRows();
    }

    @GetMapping("/merchants/{id}/controls")
    public Map<String, Object> merchantControls(@PathVariable UUID id) {
        Actor.current().requireOps();
        var merchant = db.sql("""
                SELECT m.id, m.merchant_code, m.legal_name, m.trading_name, m.status, m.contact_phone,
                       m.fee_bps, m.settlement_delay_hours, m.created_at,
                       COALESCE(k.payouts_frozen, false) AS frozen, k.reason AS freeze_reason
                FROM merchant m
                LEFT JOIN merchant_kill_switch k ON k.merchant_id = m.id
                WHERE m.id = :id""")
                .param("id", id)
                .query().listOfRows();
        if (merchant.isEmpty()) {
            throw com.elepay.common.ApiException.notFound("Merchant not found");
        }
        var apiKeys = db.sql("""
                SELECT id, prefix AS key_prefix, COALESCE(key_type, 'legacy') AS key_type,
                       CASE WHEN revoked_at IS NULL THEN 'active' ELSE 'revoked' END AS status,
                       label, created_at, revoked_at
                FROM api_credential
                WHERE merchant_id = :id
                ORDER BY created_at DESC
                LIMIT 50""")
                .param("id", id)
                .query().listOfRows();
        var terminals = db.sql("""
                SELECT id, serial_number, model, status, paired_at, created_at
                FROM pos_terminal
                WHERE merchant_id = :id
                ORDER BY created_at DESC
                LIMIT 50""")
                .param("id", id)
                .query().listOfRows();
        var balances = db.sql("""
                SELECT bucket, currency, balance
                FROM ledger_account
                WHERE owner_type = 'merchant' AND owner_id = :id
                ORDER BY currency, bucket""")
                .param("id", id)
                .query().listOfRows();
        return Map.of(
                "merchant", merchant.get(0),
                "balances", balances,
                "apiKeys", apiKeys,
                "terminals", terminals
        );
    }
}
