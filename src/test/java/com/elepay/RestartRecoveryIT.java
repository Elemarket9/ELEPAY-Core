package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.ledger.Ledger;
import com.elepay.outbox.OutboxDispatcher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Acceptance test 6: the service stops after the journal posting commits but
 * before the outbox event is delivered. A fresh instance must deliver the
 * event exactly once in effect, with balances and status unchanged, and must
 * absorb duplicate deliveries.
 */
class RestartRecoveryIT {

    static ConfigurableApplicationContext start() {
        var db = PostgresTestBase.DATABASE;
        return new SpringApplicationBuilder(ElepayApplication.class).profiles("test").run(
                "--server.port=0",
                "--spring.datasource.url=" + db.url(),
                "--spring.datasource.username=" + db.username(),
                "--spring.datasource.password=" + db.password(),
                "--elepay.outbox.enabled=false",
                "--elepay.rails.recovery-enabled=false",
                "--elepay.checkout.jobs-enabled=false");
    }

    static int port(ConfigurableApplicationContext ctx) {
        return ((WebServerApplicationContext) ctx).getWebServer().getPort();
    }

    @Test
    void restartBetweenPostingAndDeliveryPreservesBalancesAndDeliversOnce() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String reference;
        UUID merchant;
        UUID customer;
        long merchantPending;

        // ---- instance 1: post the payment, then stop before the dispatcher runs ----
        try (var first = start()) {
            var api = new TestApi(port(first));
            String ops = api.token("ops-" + s, "ops");
            String owner = api.token("owner-" + s);
            merchant = UUID.fromString(api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", "Shop " + s,
                    "tradingName", "Shop " + s, "kind", "merchant", "contactPhone", TestApi.uniquePhone()))
                    .body().get("id").asString());
            api.call("POST", "/v1/ops/merchants/" + merchant + "/review", ops, null, Map.of("decision", "approve", "feeBps", 100));
            api.call("POST", "/v1/merchants/" + merchant + "/webhooks", owner, null, Map.of("url", "http://127.0.0.1:9/hook"));
            customer = api.enroll(ops, "cust-" + s, TestApi.uniquePhone(), "Restart Customer");
            first.getBean(Ledger.class).post(Ledger.Posting.of("test-funding", s, "topup", List.of(
                    Ledger.Leg.debit(Ledger.clearing("sandbox"), 10_000),
                    Ledger.Leg.credit(Ledger.code("customer", customer, "available", "GHS"), 10_000))));
            reference = api.call("POST", "/v1/merchants/" + merchant + "/checkouts", owner, UUID.randomUUID().toString(),
                    Map.of("amount", 10_000)).body().get("reference").asString();
            var paid = api.call("POST", "/v1/checkouts/" + reference + "/pay/wallet", api.token("cust-" + s),
                    UUID.randomUUID().toString(), null);
            assertThat(paid.body().get("status").asString()).isEqualTo("paid");
            merchantPending = first.getBean(Ledger.class).balance(Ledger.code("merchant", merchant, "pending", "GHS"));
            assertThat(merchantPending).isEqualTo(9_900);
        } // context closed: simulated stop before delivery

        JdbcClient db = JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PostgresTestBase.DATABASE.url(), PostgresTestBase.DATABASE.username(), PostgresTestBase.DATABASE.password()));
        UUID event = db.sql("""
                SELECT id FROM outbox_event WHERE event_type = 'checkout.paid' AND aggregate_id = :r""")
                .param("r", reference).query(UUID.class).single();
        assertThat(db.sql("SELECT published_at IS NULL FROM outbox_event WHERE id = :id").param("id", event)
                .query(Boolean.class).single()).isTrue();

        // ---- instance 2: recover ----
        try (var second = start()) {
            Ledger ledger = second.getBean(Ledger.class);
            assertThat(ledger.balance(Ledger.code("merchant", merchant, "pending", "GHS"))).isEqualTo(merchantPending);
            assertThat(ledger.balance(Ledger.code("customer", customer, "available", "GHS"))).isZero();
            var api = new TestApi(port(second));
            assertThat(api.call("GET", "/v1/public/checkouts/" + reference + "/status", null, null, null)
                    .body().get("status").asString()).isEqualTo("paid");

            OutboxDispatcher dispatcher = second.getBean(OutboxDispatcher.class);
            while (dispatcher.dispatchBatch(500) > 0) { /* drain */ }
            assertThat(db.sql("SELECT published_at IS NOT NULL FROM outbox_event WHERE id = :id").param("id", event)
                    .query(Boolean.class).single()).isTrue();
            assertThat(notifications(db, event)).isEqualTo(1);
            assertThat(webhookDeliveries(db, event)).isEqualTo(1);

            // Crash after consumers committed but before the event was marked published: redelivered.
            db.sql("UPDATE outbox_event SET published_at = NULL WHERE id = :id").param("id", event).update();
            while (dispatcher.dispatchBatch(500) > 0) { /* drain */ }
            assertThat(notifications(db, event)).isEqualTo(1);
            assertThat(webhookDeliveries(db, event)).isEqualTo(1);
            assertThat(db.sql("SELECT attempts FROM outbox_event WHERE id = :id").param("id", event)
                    .query(Integer.class).single()).isEqualTo(2);

            // Balances unchanged by delivery; every balance reconstructs from the journal.
            assertThat(ledger.balance(Ledger.code("merchant", merchant, "pending", "GHS"))).isEqualTo(merchantPending);
            assertThat(ledger.integrity().ok()).isTrue();
        }
    }

    static long notifications(JdbcClient db, UUID event) {
        return db.sql("SELECT count(*) FROM notification WHERE event_id = :e").param("e", event).query(Long.class).single();
    }

    static long webhookDeliveries(JdbcClient db, UUID event) {
        return db.sql("SELECT count(*) FROM webhook_delivery WHERE event_id = :e").param("e", event).query(Long.class).single();
    }
}
