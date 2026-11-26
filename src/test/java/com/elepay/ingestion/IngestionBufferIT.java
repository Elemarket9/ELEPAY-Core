package com.elepay.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@DisplayName("Async Ingestion Buffer, Worker Pool, DLQ & Ops Replay Integration Tests")
class IngestionBufferIT extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired IngestionQueue ingestionQueue;
    @Autowired IngestionWorkerPool workerPool;
    @Autowired IngestionMetrics ingestionMetrics;
    @Autowired JdbcClient db;

    TestApi api;
    String opsToken;
    String customerToken;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        opsToken = api.token("ops-ingestion-" + suffix, "ops_admin");
        customerToken = api.token("cust-ingestion-" + suffix, "customer");
    }

    @Test
    @DisplayName("concurrent burst enqueue and batch enqueue commit durably and complete via virtual-thread workers")
    void concurrentBurstEnqueueAndWorkerProcessing() throws Exception {
        String eventType = "TEST_BURST_" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger(0);

        workerPool.registerHandler(new IngestionHandler() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public String handle(IngestionRecord record) {
                handled.incrementAndGet();
                return "{\"ok\":true,\"key\":\"" + record.idempotencyKey() + "\"}";
            }
        });

        try {
            int concurrentCount = 50;
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<IngestionReceipt>> futures = new ArrayList<>(concurrentCount);

            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < concurrentCount; i++) {
                    int idx = i;
                    futures.add(exec.submit(() -> {
                        startGate.await(5, TimeUnit.SECONDS);
                        return ingestionQueue.enqueue(new IngestionCommand(
                                tenantId,
                                eventType,
                                "burst-scope-" + tenantId,
                                "burst-key-" + idx,
                                "{\"seq\":" + idx + "}"));
                    }));
                }
                startGate.countDown();

                Set<UUID> ids = new HashSet<>();
                for (Future<IngestionReceipt> f : futures) {
                    IngestionReceipt receipt = f.get(15, TimeUnit.SECONDS);
                    assertThat(receipt.ingestionId()).isNotNull();
                    assertThat(receipt.duplicate()).isFalse();
                    ids.add(receipt.ingestionId());
                }
                assertThat(ids).hasSize(concurrentCount);

                // Also verify enqueueBatch
                List<IngestionCommand> batchCommands = new ArrayList<>();
                for (int i = 0; i < 15; i++) {
                    batchCommands.add(new IngestionCommand(
                            tenantId,
                            eventType,
                            "burst-scope-" + tenantId,
                            "batch-key-" + i,
                            "{\"batchSeq\":" + i + "}"));
                }
                List<IngestionReceipt> batchReceipts = ingestionQueue.enqueueBatch(batchCommands);
                assertThat(batchReceipts).hasSize(15);
                for (IngestionReceipt r : batchReceipts) {
                    assertThat(r.duplicate()).isFalse();
                    ids.add(r.ingestionId());
                }

                // Drain all pending items deterministically
                workerPool.drainNow();
                awaitAllCompleted(ids, tenantId);

                assertThat(handled.get()).isEqualTo(concurrentCount + 15);
                UUID sampleId = batchReceipts.getFirst().ingestionId();
                Optional<IngestionStatus> sampleStatus = ingestionQueue.findStatus(sampleId, tenantId);
                assertThat(sampleStatus).isPresent();
                assertThat(sampleStatus.get().status()).isEqualTo("COMPLETED");
                assertThat(sampleStatus.get().resultJson().replace(" ", "")).contains("\"ok\":true");
                assertThat(sampleStatus.get().completedAt()).isNotNull();
                assertThat(sampleStatus.get().lastError()).isNull();
            }
        } finally {
            workerPool.unregisterHandler(eventType);
        }
    }

    @Test
    @DisplayName("idempotent duplicate replay returns the original receipt with duplicate=true")
    void idempotentDuplicateReplay() {
        String eventType = "TEST_DUP_" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.randomUUID();
        String scope = "dup-scope-" + tenantId;
        String key = "idem-key-1";

        workerPool.registerHandler(new IngestionHandler() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public String handle(IngestionRecord record) {
                return "{\"handled\":true}";
            }
        });

        try {
            IngestionReceipt first = ingestionQueue.enqueue(new IngestionCommand(
                    tenantId, eventType, scope, key, "{\"amount\":5000,\"currency\":\"GHS\"}"));
            IngestionReceipt second = ingestionQueue.enqueue(new IngestionCommand(
                    tenantId, eventType, scope, key, "{\"currency\":\"GHS\",\"amount\":5000}"));

            assertThat(first.duplicate()).isFalse();
            assertThat(second.duplicate()).isTrue();
            assertThat(second.ingestionId()).isEqualTo(first.ingestionId());
        } finally {
            workerPool.drainNow();
            workerPool.unregisterHandler(eventType);
        }
    }

    @Test
    @DisplayName("same idempotency key with different payload throws 409 Conflict")
    void payloadConflictReturns409() {
        String eventType = "TEST_CONFLICT_" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.randomUUID();
        String scope = "conflict-scope-" + tenantId;
        String key = "conflict-key-1";

        workerPool.registerHandler(new IngestionHandler() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public String handle(IngestionRecord record) {
                return "{\"handled\":true}";
            }
        });

        try {
            IngestionReceipt first = ingestionQueue.enqueue(new IngestionCommand(
                    tenantId, eventType, scope, key, "{\"amount\":1000}"));
            assertThat(first.duplicate()).isFalse();

            assertThatThrownBy(() -> ingestionQueue.enqueue(new IngestionCommand(
                    tenantId, eventType, scope, key, "{\"amount\":2000}")))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(apiEx.code()).isEqualTo("idempotency_conflict");
                    });
        } finally {
            workerPool.drainNow();
            workerPool.unregisterHandler(eventType);
        }
    }

    @Test
    @DisplayName("exhausted retries transition to DEAD, appear in ops DLQ, and succeed after ops replay")
    void dlqTransitionAndOpsReplay() {
        String eventType = "TEST_DLQ_" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.randomUUID();
        AtomicBoolean shouldFail = new AtomicBoolean(true);

        workerPool.registerHandler(new IngestionHandler() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public String handle(IngestionRecord record) {
                if (shouldFail.get()) {
                    throw new IllegalStateException("simulated downstream outage");
                }
                return "{\"replayed\":true}";
            }
        });

        try {
            IngestionReceipt receipt = ingestionQueue.enqueue(new IngestionCommand(
                    tenantId, eventType, "dlq-scope-" + tenantId, "dlq-key-1", "{\"order\":42}"));
            UUID ingestionId = receipt.ingestionId();

            // Drive retries deterministically until the event reaches DEAD
            for (int i = 0; i < 15; i++) {
                workerPool.drainNow();
                IngestionStatus current = ingestionQueue.findStatus(ingestionId, tenantId).orElseThrow();
                if ("DEAD".equals(current.status())) {
                    break;
                }
                db.sql("""
                        UPDATE ingestion_event
                        SET next_attempt_at = now() - interval '1 second'
                        WHERE id = :id AND status = 'PENDING'""")
                        .param("id", ingestionId)
                        .update();
            }

            IngestionStatus deadStatus = ingestionQueue.findStatus(ingestionId, tenantId).orElseThrow();
            assertThat(deadStatus.status()).isEqualTo("DEAD");
            assertThat(deadStatus.lastError()).contains("simulated downstream outage");

            // Non-ops caller must receive 403 Forbidden
            var forbiddenRes = api.call("GET", "/v1/ops/ingestions/dead", customerToken, null, null);
            assertThat(forbiddenRes.status()).isEqualTo(403);

            // Ops caller sees the dead event in GET /v1/ops/ingestions/dead
            var deadListRes = api.call("GET", "/v1/ops/ingestions/dead?limit=100", opsToken, null, null);
            assertThat(deadListRes.status()).isEqualTo(200);
            boolean foundInDlq = false;
            for (JsonNode item : deadListRes.body()) {
                if (ingestionId.toString().equals(item.get("ingestionId").asString())) {
                    foundInDlq = true;
                    assertThat(item.get("status").asString()).isEqualTo("DEAD");
                }
            }
            assertThat(foundInDlq).isTrue();

            // Flip handler to succeed and trigger ops replay
            shouldFail.set(false);
            var replayRes = api.call("POST", "/v1/ops/ingestions/" + ingestionId + "/replay", opsToken, null, Map.of());
            assertThat(replayRes.status()).isEqualTo(200);
            assertThat(replayRes.body().get("status").asString()).isEqualTo("PENDING");

            workerPool.drainNow();
            awaitAllCompleted(Set.of(ingestionId), tenantId);

            IngestionStatus completedStatus = ingestionQueue.findStatus(ingestionId, tenantId).orElseThrow();
            assertThat(completedStatus.status()).isEqualTo("COMPLETED");
            assertThat(completedStatus.resultJson().replace(" ", "")).contains("\"replayed\":true");
            assertThat(completedStatus.lastError()).isNull();
            assertThat(completedStatus.completedAt()).isNotNull();
        } finally {
            workerPool.unregisterHandler(eventType);
        }
    }

    @Test
    @DisplayName("stale lease recovery resets expired PROCESSING rows back to PENDING")
    void staleLeaseRecovery() {
        String eventType = "TEST_STALE_" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.randomUUID();
        UUID id = UUID.randomUUID();

        workerPool.registerHandler(new IngestionHandler() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public String handle(IngestionRecord record) {
                return "{\"recovered\":true}";
            }
        });

        try {
            // Insert a row simulating a worker crash with an expired lease
            db.sql("""
                    INSERT INTO ingestion_event (
                        id, tenant_id, event_type, scope, idempotency_key, payload_hash, payload,
                        status, attempts, max_attempts, locked_by, locked_at, lease_expires_at
                    ) VALUES (
                        :id, :tenantId, :eventType, :scope, :key, :hash, CAST(:payload AS jsonb),
                        'PROCESSING', 1, 5, 'crashed-worker', now() - interval '10 minutes', now() - interval '5 minutes'
                    )""")
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("eventType", eventType)
                    .param("scope", "stale-scope-" + id)
                    .param("key", "stale-key-" + id)
                    .param("hash", "hash-" + id)
                    .param("payload", "{\"stale\":true}")
                    .update();

            int recovered = workerPool.recoverStaleLeases();
            assertThat(recovered).isGreaterThanOrEqualTo(1);

            workerPool.drainNow();
            awaitAllCompleted(Set.of(id), tenantId);

            IngestionStatus status = ingestionQueue.findStatus(id, tenantId).orElseThrow();
            assertThat(status.status()).isEqualTo("COMPLETED");
            assertThat(status.resultJson().replace(" ", "")).contains("\"recovered\":true");
        } finally {
            workerPool.unregisterHandler(eventType);
        }
    }

    private void awaitAllCompleted(Set<UUID> ids, UUID tenantId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean allDone = true;
            for (UUID id : ids) {
                Optional<IngestionStatus> st = ingestionQueue.findStatus(id, tenantId);
                if (st.isEmpty() || !"COMPLETED".equals(st.get().status())) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                return;
            }
            workerPool.drainNow();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        for (UUID id : ids) {
            IngestionStatus st = ingestionQueue.findStatus(id, tenantId).orElseThrow();
            assertThat(st.status()).as("Expected ingestion %s to reach COMPLETED", id).isEqualTo("COMPLETED");
        }
    }
}
