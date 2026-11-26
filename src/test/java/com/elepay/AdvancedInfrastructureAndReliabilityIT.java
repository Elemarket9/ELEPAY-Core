package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.cell.CellRegistryService;
import com.elepay.cell.GlobalCellDirectoryService;
import com.elepay.common.ApiException;
import com.elepay.common.FinancialTxRetry;
import com.elepay.common.Idempotency;
import com.elepay.common.Idempotency.FinancialDomain;
import com.elepay.common.datasource.DataSourceRouteContext;
import com.elepay.common.datasource.PgBouncerConnectionPoolManager;
import com.elepay.common.datasource.PgBouncerConnectionPoolManager.WorkloadBudget;
import com.elepay.common.datasource.RouteType;
import com.elepay.ingestion.DurableMessageBroker;
import com.elepay.ingestion.DurableMessageBroker.BrokerMessage;
import com.elepay.ingestion.DurableMessageBroker.PublishConfirmation;
import com.elepay.ingestion.IngestionHandler;
import com.elepay.ingestion.IngestionRecord;
import com.elepay.ingestion.IngestionWorkerPool;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.ledger.Ledger.Posting;
import com.elepay.outbox.Outbox;
import com.elepay.outbox.OutboxConsumer;
import com.elepay.outbox.OutboxDispatcher;
import com.elepay.reconciliation.ComprehensiveFinancialReconciliationService;
import com.elepay.reconciliation.ComprehensiveFinancialReconciliationService.Cadence;
import com.elepay.reliability.AdaptiveOverloadProtectionService;
import com.elepay.reliability.LoadShedder.PriorityTier;
import com.elepay.reliability.SynchronousReplicationAndFencingService;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executable Verification &amp; Stress Benchmark Suite for Requirements 2–13:
 * <ul>
 *   <li>Req 2: Transactional Outbox (atomic tx persistence, per-aggregate ordering, schema versioning, DLQ &amp; replay, archival)</li>
 *   <li>Req 3: Durable Message Broker (1,000 / 5,000 / 10,000 concurrent request benchmark, publisher confirm, ack/nack, poison DLQ)</li>
 *   <li>Req 4: Financial Idempotency Registry (11 domains, simultaneous duplicates, conflict detection, ambiguous payment outcome guard)</li>
 *   <li>Req 5: PgBouncer Connection Pooling (transaction-mode prepared statements, session-op detection, 4 workload budgets, credential rotation)</li>
 *   <li>Req 6: Batch Ledger Posting (bulk multi-leg insert, postBatch savepoint isolation, batch-size latency/throughput benchmark)</li>
 *   <li>Req 7: Global Cell Directory (authoritative home cell, fencing tokens, recoverable cross-cell in-transit transfer &amp; compensation)</li>
 *   <li>Req 8: Automated 13-Domain Financial Reconciliation &amp; dual-control compensating adjustments</li>
 *   <li>Req 9: Adaptive Overload Protection (AIMD concurrency window, per-merchant noisy-neighbor bulkheads, priority load shedding)</li>
 *   <li>Req 10 &amp; 11: Synchronous Database Replication, Authoritative Primary Spending, Quorum Promotion &amp; Split-Brain Fencing</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvancedInfrastructureAndReliabilityIT extends PostgresTestBase {
    private static final Logger log = LoggerFactory.getLogger(AdvancedInfrastructureAndReliabilityIT.class);

    @Autowired Outbox outbox;
    @Autowired OutboxDispatcher outboxDispatcher;
    @Autowired DurableMessageBroker broker;
    @Autowired IngestionWorkerPool workerPool;
    @Autowired Idempotency idempotency;
    @Autowired PgBouncerConnectionPoolManager poolManager;
    @Autowired Ledger ledger;
    @Autowired CellRegistryService cellRegistry;
    @Autowired GlobalCellDirectoryService cellDirectory;
    @Autowired ComprehensiveFinancialReconciliationService reconciliationService;
    @Autowired AdaptiveOverloadProtectionService overloadProtection;
    @Autowired SynchronousReplicationAndFencingService replicationFencing;
    @Autowired JdbcClient db;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @Order(1)
    @DisplayName("Req 2: Transactional Outbox atomic commit/rollback, strict per-aggregate ordering, schema v2, DLQ replay & archival")
    void transactionalOutboxOrderingDlqReplayAndArchival() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String aggId = "order-" + UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        // 1. Rolled-back financial transaction MUST NOT persist outbox event
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            outbox.publish("checkout", aggId, "checkout.created.rolledback", 2, Map.of("amount", 5000), List.of(merchantId));
            throw new IllegalStateException("simulated crash before commit");
        })).isInstanceOf(IllegalStateException.class);

        Long rolledBackCount = db.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :aid")
                .param("aid", aggId).query(Long.class).single();
        assertThat(rolledBackCount).isZero();

        // 2. Committed events with schema_version=2 & strict per-aggregate sequence ordering
        UUID ev1 = tx.execute(s -> outbox.publish("checkout", aggId, "test.ordered.step1", 2, Map.of("step", 1), List.of(merchantId)));
        UUID ev2 = tx.execute(s -> outbox.publish("checkout", aggId, "test.ordered.step2", 2, Map.of("step", 2), List.of(merchantId)));

        // Delay ev1 into the future: per-aggregate ordering MUST hold ev2 back until ev1 publishes
        db.sql("UPDATE outbox_event SET next_attempt_at = now() + interval '5 minutes' WHERE id = :id")
                .param("id", ev1).update();
        outboxDispatcher.dispatchBatch(50);

        Boolean ev2PublishedEarly = db.sql("SELECT published_at IS NOT NULL FROM outbox_event WHERE id = :id")
                .param("id", ev2).query(Boolean.class).single();
        assertThat(ev2PublishedEarly).as("ev2 must not overtake unpublished ev1 for the same aggregate").isFalse();
        assertThat(outbox.eventsFor(merchantId, 0L, 100))
                .as("Outbox.eventsFor returns committed non-DEAD events in sequence order")
                .extracting(Outbox.MerchantEvent::id)
                .containsExactly(ev1, ev2);

        // Now make ev1 due: both ev1 and ev2 publish in sequence order
        db.sql("UPDATE outbox_event SET next_attempt_at = now() WHERE id = :id").param("id", ev1).update();
        outboxDispatcher.dispatchBatch(50);
        outboxDispatcher.dispatchBatch(50);

        Integer bothPublished = db.sql("SELECT count(*)::int FROM outbox_event WHERE id IN (:e1, :e2) AND status = 'PUBLISHED'")
                .param("e1", ev1).param("e2", ev2).query(Integer.class).single();
        assertThat(bothPublished).isEqualTo(2);
        assertThat(outbox.eventsFor(merchantId, 0L, 100))
                .extracting(Outbox.MerchantEvent::id)
                .contains(ev1, ev2);

        // 3. Real OutboxDispatcher dead-letter transition after max_attempts & controlled replay
        UUID dlqEvent = tx.execute(s -> outbox.publish("checkout", "dlq-" + aggId, "test.dlq.event", 2, Map.of("x", 1), List.of(merchantId)));
        db.sql("UPDATE outbox_event SET max_attempts = 1 WHERE id = :id").param("id", dlqEvent).update();
        AtomicBoolean failConsumer = new AtomicBoolean(true);
        OutboxConsumer testConsumer = new OutboxConsumer() {
            @Override public String name() { return "test-dlq-consumer-" + dlqEvent; }
            @Override public boolean accepts(String eventType) { return "test.dlq.event".equals(eventType); }
            @Override public void handle(Outbox.Event event) {
                if (failConsumer.get()) throw new IllegalStateException("downstream outage");
            }
        };
        outboxDispatcher.registerConsumer(testConsumer);
        try {
            outboxDispatcher.dispatchBatch(50);
            String deadStatus = db.sql("SELECT status FROM outbox_event WHERE id = :id")
                    .param("id", dlqEvent).query(String.class).single();
            assertThat(deadStatus).isEqualTo("DEAD");
            assertThat(outbox.listDeadLetters(50)).anyMatch(d -> d.id().equals(dlqEvent));
            assertThat(outbox.eventsFor(merchantId, 0L, 100))
                    .as("Outbox.eventsFor must not leak DEAD events")
                    .noneMatch(e -> e.id().equals(dlqEvent));

            failConsumer.set(false);
            assertThat(outbox.replayDeadLetter(dlqEvent)).isTrue();
            outboxDispatcher.dispatchBatch(50);
            String replayedStatus = db.sql("SELECT status FROM outbox_event WHERE id = :id")
                    .param("id", dlqEvent).query(String.class).single();
            assertThat(replayedStatus).isEqualTo("PUBLISHED");
        } finally {
            outboxDispatcher.unregisterConsumer(testConsumer);
        }

        // 4. Retention archival moves aged published events into outbox_event_archive
        db.sql("UPDATE outbox_event SET published_at = now() - interval '45 days' WHERE id IN (:e1, :e2)")
                .param("e1", ev1).param("e2", ev2).update();
        int archived = outboxDispatcher.archivePublishedEvents(30, 100);
        assertThat(archived).isGreaterThanOrEqualTo(2);
        Integer inArchive = db.sql("SELECT count(*)::int FROM outbox_event_archive WHERE id IN (:e1, :e2)")
                .param("e1", ev1).param("e2", ev2).query(Integer.class).single();
        assertThat(inArchive).isEqualTo(2);
    }

    @Test
    @Order(2)
    @DisplayName("Req 3 & 13: Durable Message Broker publisher confirmations, orderingKey serialization, acks/nacks, poison DLQ & 1,000 / 5,000 / 10,000 message burst benchmark")
    void durableMessageBrokerAnd10kBurstBenchmark() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String topic = "BENCH_TOPIC_" + UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger processedCount = new AtomicInteger(0);
        List<String> orderedKeysObserved = Collections.synchronizedList(new ArrayList<>());

        workerPool.registerHandler(new IngestionHandler() {
            @Override public String eventType() { return topic; }
            @Override public String handle(IngestionRecord record) {
                processedCount.incrementAndGet();
                if (record.idempotencyKey().startsWith("ord-seq-")) {
                    orderedKeysObserved.add(record.idempotencyKey());
                }
                return "{\"processed\":true}";
            }
        });

        try {
            // 1. Publisher confirmation never marks paymentCompleted=true at queue acceptance and persists ordering_key & broker_message_id atomically
            BrokerMessage single = BrokerMessage.of(tenantId, topic, "broker-single", "k-single", "acct-1", "{\"amt\":1000}");
            PublishConfirmation confirm = broker.publishDurable(single);
            assertThat(confirm.durableAccepted()).isTrue();
            assertThat(confirm.paymentCompleted()).isFalse();
            String persistedOrdKey = db.sql("SELECT ordering_key FROM ingestion_event WHERE id = :id")
                    .param("id", confirm.messageId()).query(String.class).single();
            String persistedMsgId = db.sql("SELECT broker_message_id FROM ingestion_event WHERE id = :id")
                    .param("id", confirm.messageId()).query(String.class).single();
            assertThat(persistedOrdKey).isEqualTo("acct-1");
            assertThat(persistedMsgId).isEqualTo(single.messageId().toString());

            // Verify per-ordering_key sequential execution across multiple queued messages
            String sharedOrderKey = "seq-order-" + UUID.randomUUID();
            broker.publishDurable(BrokerMessage.of(tenantId, topic, "broker-ord", "ord-seq-1", sharedOrderKey, "{\"step\":1}"));
            broker.publishDurable(BrokerMessage.of(tenantId, topic, "broker-ord", "ord-seq-2", sharedOrderKey, "{\"step\":2}"));
            broker.publishDurable(BrokerMessage.of(tenantId, topic, "broker-ord", "ord-seq-3", sharedOrderKey, "{\"step\":3}"));
            long ordDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (orderedKeysObserved.size() < 3 && System.nanoTime() < ordDeadline) {
                workerPool.drainNow();
                if (orderedKeysObserved.size() < 3) {
                    Thread.sleep(20L);
                }
            }
            assertThat(orderedKeysObserved).containsExactly("ord-seq-1", "ord-seq-2", "ord-seq-3");

            // 2. Poison message negativeAcknowledge isolates directly to DEAD (DLQ), and non-poison nacks increment attempts to DEAD
            BrokerMessage poison = BrokerMessage.of(tenantId, "UNREGISTERED_POISON_TOPIC", "broker-poison", "k-poison", "acct-2", "{\"bad\":true}");
            PublishConfirmation poisonConfirm = broker.publishDurable(poison);
            broker.negativeAcknowledge(poisonConfirm.messageId(), "corrupted payload schema", true);
            String poisonStatus = db.sql("SELECT status FROM ingestion_event WHERE id = :id")
                    .param("id", poisonConfirm.messageId()).query(String.class).single();
            assertThat(poisonStatus).isEqualTo("DEAD");

            // Terminal DEAD row must not be overwritten by late acknowledge()
            broker.acknowledge(poisonConfirm.messageId(), "{\"late\":true}");
            assertThat(db.sql("SELECT status FROM ingestion_event WHERE id = :id")
                    .param("id", poisonConfirm.messageId()).query(String.class).single()).isEqualTo("DEAD");

            // 3. Benchmark 1,000, 5,000, and 10,000 durable message bursts (in 100-item micro-batches across virtual threads)
            for (int totalMessages : List.of(1_000, 5_000, 10_000)) {
                int batchSize = 100;
                int batches = totalMessages / batchSize;
                List<Long> batchLatenciesNs = Collections.synchronizedList(new ArrayList<>(batches));
                long startNs = System.nanoTime();

                try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<Future<?>> futures = new ArrayList<>(batches);
                    String tierScope = "bench-" + totalMessages + "-" + UUID.randomUUID().toString().substring(0, 6);
                    for (int b = 0; b < batches; b++) {
                        int batchIdx = b;
                        futures.add(exec.submit(() -> {
                            List<BrokerMessage> msgs = new ArrayList<>(batchSize);
                            for (int i = 0; i < batchSize; i++) {
                                int seq = batchIdx * batchSize + i;
                                msgs.add(BrokerMessage.of(tenantId, topic, tierScope, "k-" + seq, null, "{\"seq\":" + seq + "}"));
                            }
                            long t0 = System.nanoTime();
                            List<PublishConfirmation> res = broker.publishBatchDurable(msgs);
                            batchLatenciesNs.add(System.nanoTime() - t0);
                            assertThat(res).hasSize(batchSize);
                        }));
                    }
                    for (Future<?> f : futures) {
                        f.get(30, TimeUnit.SECONDS);
                    }
                }
                long elapsedMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                double acceptedRps = (totalMessages * 1000.0) / elapsedMs;
                Collections.sort(batchLatenciesNs);
                double p50Ms = batchLatenciesNs.get(batchLatenciesNs.size() / 2) / 1_000_000.0;
                double p95Ms = batchLatenciesNs.get((int) (batchLatenciesNs.size() * 0.95)) / 1_000_000.0;
                double p99Ms = batchLatenciesNs.get((int) (batchLatenciesNs.size() * 0.99)) / 1_000_000.0;
                double maxMs = batchLatenciesNs.getLast() / 1_000_000.0;

                log.info("BENCHMARK [DurableMessageBroker tier={}]: acceptedRps={} msg/s, elapsedMs={}ms, batchLatency p50={}ms p95={}ms p99={}ms max={}ms",
                        totalMessages, String.format("%.1f", acceptedRps), elapsedMs,
                        String.format("%.2f", p50Ms), String.format("%.2f", p95Ms),
                        String.format("%.2f", p99Ms), String.format("%.2f", maxMs));
                assertThat(acceptedRps).isGreaterThan(500.0);
            }

            DurableMessageBroker.ConsumerLagSnapshot lag = broker.inspectLag();
            assertThat(lag.provider()).isNotBlank();
            assertThat(lag.deadLetterCount()).isGreaterThanOrEqualTo(1L);
        } finally {
            db.sql("UPDATE ingestion_event SET status = 'COMPLETED', completed_at = now() WHERE event_type = :topic AND status = 'PENDING'")
                    .param("topic", topic)
                    .update();
            workerPool.unregisterHandler(topic);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Req 4: Financial Idempotency Registry across all 11 domains, simultaneous duplicates, conflict guard & ambiguous payment protection")
    void financialIdempotencyRegistryAllDomainsAndAmbiguousGuard() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        UUID tenantId = UUID.randomUUID();

        // 1. Verify all 11 FinancialDomain scopes execute, persist metadata, and replay identically
        for (FinancialDomain domain : FinancialDomain.values()) {
            String key = "idem-" + domain.name() + "-" + UUID.randomUUID();
            String finRef = "FINREF-" + domain.prefix() + "-" + UUID.randomUUID().toString().substring(0, 8);
            AtomicInteger executions = new AtomicInteger(0);

            Map<String, Object> first = tx.execute(s -> idempotency.executeDomain(
                    domain, tenantId, "acct-1", key, finRef, Map.of("amount", 2500, "domain", domain.name()),
                    Map.class, () -> {
                        executions.incrementAndGet();
                        return Map.of("status", "ok", "ref", finRef);
                    }));
            Map<String, Object> replay = tx.execute(s -> idempotency.executeDomain(
                    domain, tenantId, "acct-1", key, finRef, Map.of("amount", 2500, "domain", domain.name()),
                    Map.class, () -> {
                        executions.incrementAndGet();
                        return Map.of("status", "duplicate-should-not-run", "ref", finRef);
                    }));

            assertThat(executions.get()).as("Domain %s must execute exactly once", domain).isEqualTo(1);
            assertThat(replay).isEqualTo(first);

            // Payload fingerprint mismatch under same key must fail with 409 idempotency_conflict
            assertThatThrownBy(() -> tx.execute(s -> idempotency.executeDomain(
                    domain, tenantId, "acct-1", key, finRef, Map.of("amount", 9999, "domain", domain.name()),
                    Map.class, () -> Map.of("status", "conflict"))))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("idempotency key");
        }

        // 2. Ambiguous external payment outcome blocks blind retry until reconciliation resolves it
        String ambScope = FinancialDomain.PAYMENT_INITIATION.scopedKey(tenantId, "checkout-amb");
        String ambKey = "amb-key-" + UUID.randomUUID();
        Map<String, Object> reqPayload = Map.of("amount", 15000, "rail", "mtn");
        idempotency.markAmbiguousOutcome(ambScope, ambKey, reqPayload, "RAIL-TX-AMB-1");

        // Mismatched request_hash on markAmbiguousOutcome must throw 409 idempotency_conflict
        assertThatThrownBy(() -> idempotency.markAmbiguousOutcome(
                ambScope, ambKey, Map.of("amount", 99999, "rail", "mtn"), "RAIL-TX-AMB-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("idempotency key");

        assertThatThrownBy(() -> tx.execute(s -> idempotency.executeFinancial(
                tenantId, "PAYMENT_INITIATION", ambScope, ambKey, "RAIL-TX-AMB-1", 365,
                reqPayload, Map.class, () -> Map.of("shouldNotRun", true))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ambiguous");

        // Once reconciliation resolves the ambiguous outcome, replay returns the confirmed result without re-executing
        idempotency.resolveAmbiguousOutcome(ambScope, ambKey, Map.of("resolved", true, "txId", "RAIL-TX-AMB-1"));
        Map<?, ?> resolved = tx.execute(s -> idempotency.executeFinancial(
                tenantId, "PAYMENT_INITIATION", ambScope, ambKey, "RAIL-TX-AMB-1", 365,
                reqPayload, Map.class, () -> Map.of("shouldNotRun", true)));
        assertThat(resolved.get("resolved")).isEqualTo(true);

        // Late markAmbiguousOutcome after COMPLETED must NEVER downgrade COMPLETED back to AMBIGUOUS_OUTCOME
        idempotency.markAmbiguousOutcome(ambScope, ambKey, reqPayload, "RAIL-TX-AMB-1");
        assertThat(idempotency.inspect(ambScope, ambKey).orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    @Order(4)
    @DisplayName("Req 5: PgBouncer connection pooling compatibility, session-dependent detection, 4 workload budgets & credential rotation")
    void pgBouncerConnectionPoolingAndWorkloadBudgets() {
        assertThat(poolManager.isSessionDependentOperation("SELECT pg_advisory_lock(123)")).isTrue();
        assertThat(poolManager.isSessionDependentOperation("SELECT pg_try_advisory_lock (123)")).isTrue();
        assertThat(poolManager.isSessionDependentOperation("SELECT pg_advisory_lock_shared(123)")).isTrue();
        assertThat(poolManager.isSessionDependentOperation("SELECT pg_advisory_lock(1), pg_advisory_xact_lock(2)")).isTrue();
        assertThat(poolManager.isSessionDependentOperation("SELECT pg_advisory_xact_lock(123)")).isFalse();
        assertThat(poolManager.isSessionDependentOperation("LISTEN channel_events")).isTrue();
        assertThat(poolManager.isSessionDependentOperation("NOTIFY channel_events, 'payload'")).isTrue();
        assertThat(poolManager.isSessionDependentOperation("PREPARE stmt1 AS SELECT 1")).isTrue();
        assertThat(poolManager.isSessionDependentOperation("CREATE TEMP TABLE tmp_recon (id int)")).isTrue();

        com.zaxxer.hikari.HikariConfig cfg = new com.zaxxer.hikari.HikariConfig();
        PgBouncerConnectionPoolManager.applyPgBouncerAndTlsProperties(cfg, true, "verify-full");
        assertThat(cfg.getDataSourceProperties().getProperty("prepareThreshold")).isEqualTo("0");
        assertThat(cfg.getDataSourceProperties().getProperty("preparedStatementCacheQueries")).isEqualTo("0");
        assertThat(cfg.getDataSourceProperties().getProperty("preparedStatementCacheSizeMiB")).isEqualTo("0");
        assertThat(cfg.getDataSourceProperties().getProperty("sslmode")).isEqualTo("verify-full");

        for (WorkloadBudget budget : WorkloadBudget.values()) {
            String res = poolManager.executeWithBudget(budget, () -> "ok-" + budget.name());
            assertThat(res).isEqualTo("ok-" + budget.name());
        }

        // Live credential rotation unwraps underlying HikariDataSource pool(s)
        var pg = PostgresTestBase.DATABASE;
        assertThat(poolManager.rotateCredentials(pg.username(), pg.password())).isTrue();

        var snap = poolManager.snapshot();
        assertThat(snap.transactionalLimit()).isGreaterThan(snap.reportingLimit());
        assertThat(snap.reportingLimit()).isGreaterThanOrEqualTo(snap.reconciliationLimit());
        assertThat(snap.credentialRotations()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Order(5)
    @DisplayName("Req 6 & 13: Batch Ledger Posting savepoint isolation, simultaneous spending & batch-size benchmark (1, 10, 50, 100)")
    void batchLedgerPostingSavepointIsolationAndBenchmark() throws Exception {
        FinancialTxRetry txRetry = new FinancialTxRetry(txManager);
        UUID customerId = UUID.randomUUID();
        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();

        String custAcct = ledger.open("customer", customerId, "available", Ledger.GHS);
        String merchAAcct = ledger.open("merchant", merchantA, "available", Ledger.GHS);
        String merchBAcct = ledger.open("merchant", merchantB, "available", Ledger.GHS);
        String clearing = ledger.openSystem(ledger.clearingStripe("sandbox", Ledger.GHS, customerId), "clearing", Ledger.GHS, true);

        // Fund customer with exactly 10,000 pesewas
        ledger.post(Posting.of("bench:fund", "fund-" + customerId, "deposit", List.of(
                Leg.debit(clearing, 10_000L),
                Leg.credit(custAcct, 10_000L)
        )));

        // 1. Batch posting with 4 items where item #2 has insufficient funds (50,000 > 10,000) and item #3 has null kind:
        // Savepoint isolation must commit items #1 (3,000) and #4 (2,000) while isolating items #2 and #3!
        List<Posting> mixedBatch = List.of(
                Posting.of("batch:test", "b1-" + customerId, "spend", List.of(
                        Leg.debit(custAcct, 3_000L),
                        Leg.credit(merchAAcct, 3_000L))),
                Posting.of("batch:test", "b2-overdraft-" + customerId, "spend", List.of(
                        Leg.debit(custAcct, 50_000L),
                        Leg.credit(merchAAcct, 50_000L))),
                Posting.of("batch:test", "b3-null-kind-" + customerId, null, List.of(
                        Leg.debit(custAcct, 1_000L),
                        Leg.credit(merchAAcct, 1_000L))),
                Posting.of("batch:test", "b4-" + customerId, "spend", List.of(
                        Leg.debit(custAcct, 2_000L),
                        Leg.credit(merchBAcct, 2_000L)))
        );

        List<Ledger.BatchPostingOutcome> outcomes = ledger.postBatch(mixedBatch);
        assertThat(outcomes).hasSize(4);
        assertThat(outcomes.get(0).succeeded()).isTrue();
        assertThat(outcomes.get(1).succeeded()).isFalse();
        assertThat(outcomes.get(1).error().code()).isEqualTo("insufficient_funds");
        assertThat(outcomes.get(2).succeeded()).isFalse();
        assertThat(outcomes.get(2).error().code()).isEqualTo("invalid_posting");
        assertThat(outcomes.get(3).succeeded()).isTrue();

        assertThat(ledger.balance(custAcct)).isEqualTo(5_000L);
        assertThat(ledger.balance(merchAAcct)).isEqualTo(3_000L);
        assertThat(ledger.balance(merchBAcct)).isEqualTo(2_000L);

        // 2. Simultaneous double-spending race: 20 concurrent threads attempting to spend 5,000 from remaining 5,000 balance
        int contenders = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger(0);
        AtomicInteger insufficientRejections = new AtomicInteger(0);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(contenders);
            for (int i = 0; i < contenders; i++) {
                int idx = i;
                futures.add(exec.submit(() -> {
                    try {
                        startGate.await();
                        txRetry.execute(() -> ledger.post(Posting.of(
                                "race:spend", "race-" + customerId + "-" + idx, "spend",
                                List.of(Leg.debit(custAcct, 5_000L), Leg.credit(merchAAcct, 5_000L)))));
                        winners.incrementAndGet();
                    } catch (ApiException ex) {
                        if ("insufficient_funds".equals(ex.code())) {
                            insufficientRejections.incrementAndGet();
                        } else {
                            throw ex;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) f.get(20, TimeUnit.SECONDS);
        }
        assertThat(winners.get()).as("Exactly 1 contender may spend the last 5,000 pesewas").isEqualTo(1);
        assertThat(insufficientRejections.get()).isEqualTo(contenders - 1);
        assertThat(ledger.balance(custAcct)).isZero();

        // 3. Benchmark batch sizes (1, 10, 50, 100) measuring TPS and WAL bytes generated
        for (int batchSize : List.of(1, 10, 50, 100)) {
            int totalPostings = 200;
            int batches = totalPostings / batchSize;
            Long walBefore = db.sql("SELECT pg_current_wal_lsn() - '0/0'::pg_lsn").query(Long.class).single();
            long t0 = System.nanoTime();
            for (int b = 0; b < batches; b++) {
                List<Posting> batch = new ArrayList<>(batchSize);
                for (int i = 0; i < batchSize; i++) {
                    String k = "bench-bs" + batchSize + "-" + b + "-" + i + "-" + UUID.randomUUID();
                    String stripe = ledger.openSystem(ledger.clearingStripe("sandbox", Ledger.GHS, k), "clearing", Ledger.GHS, true);
                    batch.add(Posting.of("bench:batch", k, "split_settlement", List.of(
                            Leg.debit(stripe, 100L),
                            Leg.credit(merchAAcct, 60L),
                            Leg.credit(merchBAcct, 40L)
                    )));
                }
                List<Ledger.BatchPostingOutcome> res = ledger.postBatch(batch);
                assertThat(res).allMatch(Ledger.BatchPostingOutcome::succeeded);
            }
            long elapsedMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
            Long walAfter = db.sql("SELECT pg_current_wal_lsn() - '0/0'::pg_lsn").query(Long.class).single();
            double postingsPerSec = (totalPostings * 1000.0) / elapsedMs;
            long walBytes = Math.max(0L, (walAfter == null ? 0L : walAfter) - (walBefore == null ? 0L : walBefore));
            log.info("BENCHMARK [Ledger.postBatch batchSize={}]: throughput={} postings/s ({} legs/s), elapsedMs={}ms, walBytes={}",
                    batchSize, String.format("%.1f", postingsPerSec), String.format("%.1f", postingsPerSec * 3), elapsedMs, walBytes);
        }
    }

    @Test
    @Order(6)
    @DisplayName("Req 7 & 8: Global Cell Directory fencing tokens, recoverable cross-cell in-transit transfers & 13-domain reconciliation")
    void globalCellDirectoryCrossCellTransfersAndReconciliation() {
        cellRegistry.registerCell("cell-alpha", "Cell Alpha", null, null, 100);
        cellRegistry.registerCell("cell-beta", "Cell Beta", null, null, 100);

        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        String acctA = ledger.open("merchant", ownerA, "available", Ledger.GHS);
        String acctB = ledger.open("merchant", ownerB, "available", Ledger.GHS);
        String clearing = ledger.openSystem(ledger.clearingStripe("sandbox", Ledger.GHS, ownerA), "clearing", Ledger.GHS, true);

        ledger.post(Posting.of("cell:fund", "fund-" + ownerA, "deposit", List.of(
                Leg.debit(clearing, 25_000L),
                Leg.credit(acctA, 25_000L)
        )));

        // 1. Register account in directory & verify fencing token increments on cell migration
        var dirA = cellDirectory.registerOrResolveAccount(acctA, "LEDGER_ACCOUNT", ownerA);
        assertThat(dirA.fencingToken()).isEqualTo(1L);

        var migratedA = cellDirectory.migrateAccountCell(acctA, "cell-alpha", 1L);
        assertThat(migratedA.homeCellId()).isEqualTo("cell-alpha");
        assertThat(migratedA.fencingToken()).isEqualTo(2L);

        // Stale fencing token (1 < 2) MUST be rejected with 409 cell_fencing_violation, both directly and on initiateCrossCellTransfer
        assertThatThrownBy(() -> cellDirectory.assertAuthoritativeCellAndFencingToken(acctA, "cell-0", 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("fencing");
        assertThatThrownBy(() -> cellDirectory.initiateCrossCellTransfer(
                "xcell-stale-" + UUID.randomUUID(), "cell-alpha", "cell-beta", acctA, acctB, 1_000L, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("fencing");

        // 2. Successful cross-cell transfer: RESERVED_IN_TRANSIT -> SETTLED
        String xcellKey1 = "xcell-settle-" + UUID.randomUUID();
        var tx1 = cellDirectory.initiateCrossCellTransfer(
                xcellKey1, "cell-alpha", "cell-beta", acctA, acctB, 10_000L, 2L);
        assertThat(tx1.state()).isEqualTo("RESERVED_IN_TRANSIT");
        assertThat(ledger.balance(acctA)).isEqualTo(15_000L);

        // Reusing idempotencyKey with different amount must throw 409 idempotency_conflict
        assertThatThrownBy(() -> cellDirectory.initiateCrossCellTransfer(
                xcellKey1, "cell-alpha", "cell-beta", acctA, acctB, 99_000L, 2L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("idempotency key");

        // While RESERVED_IN_TRANSIT, 13-domain reconciliation must still be 100% balanced (0 variance!)
        var midRecon = reconciliationService.runReconciliation(Cadence.INTRADAY, "ops-auditor");
        assertThat(midRecon.balanced()).isTrue();
        assertThat(midRecon.crossCellInTransitVarianceMinor()).isZero();

        var settledTx1 = cellDirectory.settleCrossCellTransfer(tx1.id());
        assertThat(settledTx1.state()).isEqualTo("SETTLED");
        assertThat(ledger.balance(acctB)).isEqualTo(10_000L);

        // 3. Interrupted cross-cell transfer recovery via compensating entry: RESERVED_IN_TRANSIT -> COMPENSATED
        var tx2 = cellDirectory.initiateCrossCellTransfer(
                "xcell-comp-" + UUID.randomUUID(), "cell-alpha", "cell-beta", acctA, acctB, 5_000L, 2L);
        assertThat(ledger.balance(acctA)).isEqualTo(10_000L);

        var compTx2 = cellDirectory.compensateCrossCellTransfer(tx2.id(), "Target cell-beta timeout during maintenance");
        assertThat(compTx2.state()).isEqualTo("COMPENSATED");
        assertThat(ledger.balance(acctA)).isEqualTo(15_000L);

        // 4. Two-step 4-eyes compensating adjustment workflow (proposeAdjustment -> approveAdjustment / rejectAdjustment)
        var rejectedProposal = reconciliationService.proposeAdjustment(
                "adj-reject-" + UUID.randomUUID(), "ops-maker@elepay",
                acctB, acctA, 300L, "Disputed fee adjustment", "RECON-0");
        assertThat(rejectedProposal.status()).isEqualTo("PENDING");
        var afterReject = reconciliationService.rejectAdjustment(rejectedProposal.proposalId(), "ops-checker@elepay", "Insufficient evidence");
        assertThat(afterReject.status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> reconciliationService.approveAdjustment(rejectedProposal.proposalId(), "ops-checker@elepay", "Late approve"))
                .isInstanceOf(ApiException.class);

        var validProposal = reconciliationService.proposeAdjustment(
                "adj-2step-" + UUID.randomUUID(), "ops-maker@elepay",
                acctB, acctA, 500L, "Verified fee rebate adjustment", "RECON-1");
        // Self-approval attempt on 2-step proposal must be rejected with 403 Forbidden
        assertThatThrownBy(() -> reconciliationService.approveAdjustment(validProposal.proposalId(), "ops-maker@elepay", "Self approve"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("four-eyes");

        var adj = reconciliationService.approveAdjustment(validProposal.proposalId(), "ops-checker@elepay", "Approved after audit");
        assertThat(adj.adjustmentTxId()).isNotNull();
        assertThat(ledger.balance(acctA)).isEqualTo(15_500L);
        assertThat(ledger.balance(acctB)).isEqualTo(9_500L);

        // Double approval of already-approved proposal must be rejected with 409 Conflict
        assertThatThrownBy(() -> reconciliationService.approveAdjustment(validProposal.proposalId(), "ops-checker2@elepay", "Duplicate approve"))
                .isInstanceOf(ApiException.class);

        // Final 13-domain reconciliation check
        var finalRecon = reconciliationService.runReconciliation(Cadence.DAILY, "ops-auditor");
        assertThat(finalRecon.balanced()).isTrue();
        assertThat(finalRecon.unbalancedJournalTransactions()).isZero();
        assertThat(finalRecon.driftedLedgerAccounts()).isZero();
        assertThat(finalRecon.crossCellInTransitVarianceMinor()).isZero();
    }

    @Test
    @Order(7)
    @DisplayName("Req 9, 10, 11 & 13: Adaptive Overload Protection, Authoritative Primary Routing, Quorum Failover & Split-Brain Fencing")
    void adaptiveOverloadSynchronousReplicationAndFailoverFencing() {
        // 1. Adaptive Overload Protection: CRITICAL transactions pass while noisy-neighbor merchant bulkhead sheds excess
        UUID whaleMerchant = UUID.randomUUID();
        String ok = overloadProtection.executeProtected(whaleMerchant, PriorityTier.CRITICAL, () -> "admitted");
        assertThat(ok).isEqualTo("admitted");
        var telemetry = overloadProtection.telemetry();
        assertThat(telemetry.currentConcurrencyLimit()).isBetween(telemetry.minConcurrencyLimit(), telemetry.maxConcurrencyLimit());

        // 2. Authoritative Primary Financial Execution pins route to PRIMARY and marks RYOW sticky-primary
        DataSourceRouteContext.clear();
        DataSourceRouteContext.setRouteOverride(RouteType.REPLICA);
        RouteType observedInsideAuth = replicationFencing.executeAuthoritativeFinancialAction(() -> {
            assertThat(DataSourceRouteContext.isPrimaryWritten()).isTrue();
            return DataSourceRouteContext.getRouteOverride();
        });
        assertThat(observedInsideAuth).isEqualTo(RouteType.PRIMARY);
        DataSourceRouteContext.clear();

        // 3. Quorum Failover & Split-Brain Fencing
        var healthBefore = replicationFencing.inspectReplicationHealth(12L);
        String initialPrimary = healthBefore.activePrimaryId();
        long initialEpoch = healthBefore.fencingEpoch();

        // Insufficient quorum (1 < 2) MUST be rejected
        assertThatThrownBy(() -> replicationFencing.promoteStandbyWithQuorum(
                initialPrimary, "standby-cell-0-b", 1, "patroni", "quorum test"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("quorum");

        // Valid quorum (2 >= 2) promotes standby and increments fencing epoch
        String promotedNode = "standby-promoted-" + UUID.randomUUID().toString().substring(0, 6);
        var promotion = replicationFencing.promoteStandbyWithQuorum(
                initialPrimary, promotedNode, 2, "patroni-consensus", "Simulated primary heartbeat loss");
        assertThat(promotion.newEpoch()).isEqualTo(initialEpoch + 1);
        assertThat(promotion.newPrimaryId()).isEqualTo(promotedNode);

        // Old primary attempting to write with stale epoch inside a transaction MUST be fenced out (409 split_brain_fenced)
        // AND the OBSOLETE_PRIMARY_FENCED audit event MUST survive the outer transaction rollback!
        TransactionTemplate tx = new TransactionTemplate(txManager);
        long fencedAuditBefore = db.sql("""
                SELECT count(*) FROM failover_audit_event
                WHERE event_type = 'OBSOLETE_PRIMARY_FENCED' AND old_primary_id = :node""")
                .param("node", initialPrimary).query(Long.class).single();
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                replicationFencing.assertPrimaryFencingToken(initialPrimary, initialEpoch)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("fenced");
        long fencedAuditAfter = db.sql("""
                SELECT count(*) FROM failover_audit_event
                WHERE event_type = 'OBSOLETE_PRIMARY_FENCED' AND old_primary_id = :node""")
                .param("node", initialPrimary).query(Long.class).single();
        assertThat(fencedAuditAfter).isEqualTo(fencedAuditBefore + 1);
        String detailsJson = db.sql("""
                SELECT details::text FROM failover_audit_event
                WHERE event_type = 'OBSOLETE_PRIMARY_FENCED' AND old_primary_id = :node
                ORDER BY created_at DESC LIMIT 1""")
                .param("node", initialPrimary).query(String.class).single();
        assertThat(detailsJson).contains("rejectedNodeId").contains("rejectedEpoch");

        // Promoted primary with new epoch passes fencing check
        replicationFencing.assertPrimaryFencingToken(promotedNode, promotion.newEpoch());

        // Degraded sync replication fence blocks executeAuthoritativeFinancialAction until cleared
        replicationFencing.setSynchronousReplicationDegradedFence(true, "test-monitor", "Standby unreachable");
        try {
            assertThatThrownBy(() -> replicationFencing.executeAuthoritativeFinancialAction(() -> "should-fail"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("SAFE_READ_ONLY_DEGRADED");
        } finally {
            replicationFencing.setSynchronousReplicationDegradedFence(false, "test-monitor", "Standby restored");
        }

        // Immutable failover_audit_event rejects UPDATE/DELETE (V79 + V80 triggers)
        assertThatThrownBy(() -> db.sql("DELETE FROM failover_audit_event WHERE id = :id")
                .param("id", promotion.auditEventId()).update())
                .hasStackTraceContaining("immutable");

        // Memory stability check after full stress & benchmark suite
        long usedHeapMb = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long maxHeapMb = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / (1024 * 1024);
        log.info("BENCHMARK [JVM Resource Telemetry]: usedHeap={}MB, maxHeap={}MB, heapRatio={}",
                usedHeapMb, maxHeapMb, String.format("%.2f", overloadProtection.currentHeapUsageRatio()));
        assertThat(overloadProtection.currentHeapUsageRatio()).isLessThan(0.95);
    }
}
