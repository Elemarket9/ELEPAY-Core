package com.elepay.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.common.OutboundUrlGuard;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("OutboxDispatcher & Webhooks Transaction Boundary Remediation Tests")
class OutboxAndWebhooksRemediationIT extends PostgresTestBase {

    @Autowired JdbcClient db;
    @Autowired PlatformTransactionManager tm;
    @Autowired DataSource dataSource;

    @Test
    void webhooksSendBatchDoesNotHoldDbConnectionDuringHttpIo() throws Exception {
        AtomicInteger activeConnectionsDuringHttp = new AtomicInteger(-1);
        HikariDataSource hikari = (HikariDataSource) dataSource;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            activeConnectionsDuringHttp.set(hikari.getHikariPoolMXBean().getActiveConnections());
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
            byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            OutboundUrlGuard permissiveGuard = new OutboundUrlGuard(
                    true, true, false, java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
            Webhooks webhooks = new Webhooks(db, false, permissiveGuard, tm);

            UUID merchantId = UUID.randomUUID();
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();

            String merchantCode = String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000, 1000000));
            new TransactionTemplate(tm).executeWithoutResult(s -> {
                db.sql("""
                        INSERT INTO merchant (id, legal_name, trading_name, kind, status, merchant_code, contact_phone, created_by)
                        VALUES (:id, 'Webhook Test', 'Webhook Test', 'marketplace', 'active', :code, '0241234567', 'test')""")
                        .param("id", merchantId).param("code", merchantCode).update();
                db.sql("""
                        INSERT INTO webhook_endpoint (id, merchant_id, url, secret, active, environment)
                        VALUES (:id, :m, :url, 'whsec_test', true, 'live')""")
                        .param("id", endpointId).param("m", merchantId).param("url", url).update();
                db.sql("""
                        INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload, merchant_ids)
                        VALUES (:id, 'test', '1', 'test.event', '{"ok":true}'::jsonb, :m)""")
                        .param("id", eventId).param("m", new UUID[]{merchantId}).update();
                db.sql("""
                        INSERT INTO webhook_delivery (event_id, endpoint_id, status, next_attempt_at)
                        VALUES (:e, :w, 'pending', now() - interval '1 second')""")
                        .param("e", eventId).param("w", endpointId).update();
            });

            int delivered = webhooks.sendBatch(1000);
            assertThat(delivered).isGreaterThanOrEqualTo(1);
            assertThat(activeConnectionsDuringHttp.get()).isZero();

            String status = db.sql("SELECT status FROM webhook_delivery WHERE event_id = :e AND endpoint_id = :w")
                    .param("e", eventId).param("w", endpointId).query(String.class).single();
            assertThat(status).isEqualTo("delivered");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void outboxDispatcherReleasesClaimTxBeforeConsumerTx() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        AtomicInteger activeDuringConsumer = new AtomicInteger(-1);

        OutboxConsumer consumer = new OutboxConsumer() {
            @Override public String name() { return "remediation-test-consumer-" + UUID.randomUUID(); }
            @Override public boolean accepts(String eventType) { return "remediation.test".equals(eventType); }
            @Override public void handle(Outbox.Event event) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                activeDuringConsumer.set(hikari.getHikariPoolMXBean().getActiveConnections());
            }
        };

        OutboxDispatcher dispatcher = new OutboxDispatcher(db, List.of(consumer), tm, false);
        UUID eventId = UUID.randomUUID();
        db.sql("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload, next_attempt_at)
                VALUES (:id, 'test', '1', 'remediation.test', '{"ok":true}'::jsonb, now() - interval '1 second')""")
                .param("id", eventId).update();

        int published = dispatcher.dispatchBatch(1000);
        assertThat(published).isGreaterThanOrEqualTo(1);
        assertThat(db.sql("SELECT published_at IS NOT NULL FROM outbox_event WHERE id = :id")
                .param("id", eventId).query(Boolean.class).single()).isTrue();
        // Only consumerTx holds 1 connection (not 2 concurrent connections from claimTx + consumerTx)
        assertThat(activeDuringConsumer.get()).isEqualTo(1);
    }
}
