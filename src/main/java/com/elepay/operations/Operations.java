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
                JOIN merchant m ON m.id = a.merchant_id WHERE d.status = :s ORDER BY d.created_at""")
                .param("s", status).query().listOfRows();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam String subject) {
        Actor.current().requireOps();
        return db.sql("SELECT actor, action, subject, details::text AS details, created_at FROM audit_log WHERE subject = :s ORDER BY id DESC LIMIT 200")
                .param("s", subject).query().listOfRows();
    }

    @GetMapping("/reconciliation/runs")
    public List<Map<String, Object>> runs() {
        Actor.current().requireOps();
        return db.sql("SELECT id, rail, period, imported_by, summary::text AS summary, created_at FROM reconciliation_run ORDER BY created_at DESC LIMIT 100")
                .query().listOfRows();
    }
}
