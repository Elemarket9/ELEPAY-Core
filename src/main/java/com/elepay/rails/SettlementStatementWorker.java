package com.elepay.rails;

import com.elepay.common.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Poll authenticated statements after rail completion; retry outstanding items independently. */
@Component
class SettlementStatementWorker {
    private final JdbcClient db;
    private final SettlementFunding funding;

    SettlementStatementWorker(JdbcClient db, SettlementFunding funding) {
        this.db = db;
        this.funding = funding;
    }

    @Scheduled(fixedDelayString = "${elepay.settlement.statement-poll-ms:15000}")
    void poll() {
        record Work(UUID id, String reference, String direction) {}
        var items = db.sql("""
                WITH due AS (
                    SELECT rail_tx FROM settlement_statement_work WHERE next_check_at<=now()
                    ORDER BY next_check_at LIMIT 32 FOR UPDATE SKIP LOCKED
                )
                UPDATE settlement_statement_work w SET next_check_at=now()+interval '2 minutes'
                FROM due JOIN rail_transaction t ON t.id=due.rail_tx
                WHERE w.rail_tx=due.rail_tx RETURNING t.id,t.reference,t.direction""")
                .query((r,i) -> new Work(r.getObject(1, UUID.class), r.getString(2), r.getString(3))).list();
        for (Work item : items) {
            try {
                if ("charge".equals(item.direction())) {
                    String reference = funding.bank().creditReference(funding.source(), item.reference());
                    if (reference == null) continue;
                    funding.confirmCredit(reference);
                    boolean matched = db.sql("""
                            SELECT EXISTS(SELECT 1 FROM payment_attempt a JOIN settlement_funded_checkout f
                            ON f.checkout_id=a.checkout_id JOIN settlement_bank_collection c
                            ON c.rail_tx=a.rail_tx AND c.source_key=f.source_key
                            WHERE a.rail_tx=:id AND f.bank_reference=:ref)""")
                            .param("id", item.id()).param("ref", reference).query(Boolean.class).single();
                    if (!matched) throw ApiException.conflict("unmatched_collection", "Credit does not include this collection");
                } else {
                    funding.reconcileDebit(item.reference());
                }
                db.sql("DELETE FROM settlement_statement_work WHERE rail_tx=:id").param("id", item.id()).update();
            } catch (RuntimeException failure) {
                String reason = failure instanceof ApiException e ? e.code() : "statement_sync_failed";
                db.sql("UPDATE settlement_statement_work SET last_error=:reason WHERE rail_tx=:id")
                        .param("reason", reason).param("id", item.id()).update();
            }
        }
    }
}
