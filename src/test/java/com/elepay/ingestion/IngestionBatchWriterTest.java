package com.elepay.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@DisplayName("IngestionBatchWriter, IngestionMetrics, and IngestionWorkerPool Unit Tests")
class IngestionBatchWriterTest {

    @Test
    @DisplayName("canonicalPayloadHash is insensitive to JSON key order and rejects malformed JSON")
    void canonicalPayloadHashDeterminism() {
        String h1 = IngestionBatchWriter.canonicalPayloadHash("{\"b\":2,\"a\":1,\"nested\":{\"y\":true,\"x\":false}}");
        String h2 = IngestionBatchWriter.canonicalPayloadHash("{\"nested\":{\"x\":false,\"y\":true},\"a\":1,\"b\":2}");
        String h3 = IngestionBatchWriter.canonicalPayloadHash("{\"a\":1,\"b\":3}");

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);

        assertThatThrownBy(() -> IngestionBatchWriter.canonicalPayloadHash("{not-valid-json"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.code()).isEqualTo("invalid_payload_json");
                });
    }

    @Test
    @DisplayName("group-commit batch writer commits before completing caller futures")
    void groupCommitCompletesFuturesAfterCommit() throws Exception {
        AtomicBoolean committed = new AtomicBoolean(false);
        AtomicInteger batchCalls = new AtomicInteger(0);
        StubTransactionManager txManager = new StubTransactionManager(committed);
        StubJdbcTemplate jdbc = new StubJdbcTemplate(batchCalls, null);
        IngestionProperties props = new IngestionProperties();
        props.setQueueCapacity(100);
        props.setBatchSize(32);
        props.setFlushIntervalMs(5);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        IngestionBatchWriter writer = new IngestionBatchWriter(jdbc, txManager, props, new IngestionMetrics(registry), registry);
        writer.start();
        try {
            UUID tenantId = UUID.randomUUID();
            int count = 12;
            List<Future<IngestionReceipt>> futures = new ArrayList<>();
            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < count; i++) {
                    int idx = i;
                    futures.add(exec.submit(() -> writer.enqueue(new IngestionCommand(
                            tenantId,
                            "TEST_EVENT",
                            "test-scope",
                            "key-" + idx,
                            "{\"idx\":" + idx + "}"))));
                }
                for (Future<IngestionReceipt> f : futures) {
                    IngestionReceipt receipt = f.get(5, TimeUnit.SECONDS);
                    assertThat(committed.get()).isTrue();
                    assertThat(receipt.ingestionId()).isNotNull();
                    assertThat(receipt.status()).isEqualTo("PENDING");
                    assertThat(receipt.duplicate()).isFalse();
                }
            }
            assertThat(batchCalls.get()).isGreaterThanOrEqualTo(1);
            assertThat(registry.counter(IngestionMetrics.ENQUEUED).count()).isEqualTo(count);
        } finally {
            writer.stop();
        }
    }

    @Test
    @DisplayName("backpressure throws 503 when queue reaches high watermark or writer is stopped")
    void backpressureAtHighWatermarkAndAfterStop() throws Exception {
        CountDownLatch inBatchUpdate = new CountDownLatch(1);
        CountDownLatch releaseBatchUpdate = new CountDownLatch(1);
        StubTransactionManager txManager = new StubTransactionManager(new AtomicBoolean(false));
        StubJdbcTemplate blockingJdbc = new StubJdbcTemplate(new AtomicInteger(0), () -> {
            inBatchUpdate.countDown();
            try {
                releaseBatchUpdate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        IngestionProperties props = new IngestionProperties();
        props.setQueueCapacity(2);
        props.setHighWatermarkRatio(0.5); // highWatermark = 1
        props.setBatchSize(1);
        props.setFlushIntervalMs(5);
        props.setEnqueueTimeoutMs(5_000);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        IngestionBatchWriter writer = new IngestionBatchWriter(blockingJdbc, txManager, props, new IngestionMetrics(registry), registry);
        writer.start();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            UUID tenantId = UUID.randomUUID();
            // First item gets pulled by flusher and blocks inside batchUpdate
            Future<IngestionReceipt> first = exec.submit(() -> writer.enqueue(
                    new IngestionCommand(tenantId, "TEST", "bp-scope", "k-1", "{\"n\":1}")));
            assertThat(inBatchUpdate.await(3, TimeUnit.SECONDS)).isTrue();

            // Second item sits in the queue, bringing queue.size() == 1 == highWatermark
            Future<IngestionReceipt> second = exec.submit(() -> writer.enqueue(
                    new IngestionCommand(tenantId, "TEST", "bp-scope", "k-2", "{\"n\":2}")));

            // Wait briefly for the second item to enter the bounded queue
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (writer.queueDepth() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(writer.isHighWatermark()).isTrue();

            // Third item must be rejected immediately with 503 ingestion_backpressure
            assertThatThrownBy(() -> writer.enqueue(
                    new IngestionCommand(tenantId, "TEST", "bp-scope", "k-3", "{\"n\":3}")))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(apiEx.code()).isEqualTo("ingestion_backpressure");
                    });
            assertThat(registry.counter(IngestionMetrics.BACKPRESSURE).count()).isGreaterThanOrEqualTo(1.0);

            releaseBatchUpdate.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).ingestionId()).isNotNull();
            assertThat(second.get(5, TimeUnit.SECONDS).ingestionId()).isNotNull();
        } finally {
            releaseBatchUpdate.countDown();
            writer.stop();
        }

        // Enqueue after stop must also reject with 503
        assertThatThrownBy(() -> writer.enqueue(
                new IngestionCommand(UUID.randomUUID(), "TEST", "bp-scope", "k-after", "{}")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("IngestionCommand validates required fields")
    void commandValidation() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> new IngestionCommand(tenant, "", "s", "k", "{}"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new IngestionCommand(tenant, "E", "", "k", "{}"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new IngestionCommand(tenant, "E", "s", "", "{}"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new IngestionCommand(tenant, "E", "s", "k", ""))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("IngestionMetrics registers and increments all expected Micrometer meters")
    void metricsCountersAndTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestionMetrics metrics = new IngestionMetrics(registry);

        metrics.recordEnqueued();
        metrics.recordEnqueued(4);
        metrics.recordCompleted(Duration.ofMillis(12));
        metrics.recordRetried();
        metrics.recordDead();
        metrics.recordBackpressure();

        assertThat(metrics.enqueuedCount()).isEqualTo(5.0);
        assertThat(metrics.completedCount()).isEqualTo(1.0);
        assertThat(metrics.retriedCount()).isEqualTo(1.0);
        assertThat(metrics.deadCount()).isEqualTo(1.0);
        assertThat(metrics.backpressureCount()).isEqualTo(1.0);
        assertThat(metrics.processingTimer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("IngestionWorkerPool clamps concurrency strictly below Hikari max and bounds backoff")
    void workerPoolConcurrencyClampAndBackoff() {
        assertThat(IngestionWorkerPool.clampConcurrency(0)).isEqualTo(1);
        assertThat(IngestionWorkerPool.clampConcurrency(16)).isEqualTo(16);
        assertThat(IngestionWorkerPool.clampConcurrency(100)).isEqualTo(IngestionWorkerPool.MAX_SAFE_CONCURRENCY);
        assertThat(IngestionWorkerPool.MAX_SAFE_CONCURRENCY).isLessThan(50);

        IngestionWorkerPool pool = new IngestionWorkerPool(
                null,
                new StubTransactionManager(new AtomicBoolean(false)),
                List.of(),
                new IngestionMetrics(),
                false,
                16,
                32,
                50,
                60,
                15_000,
                1000,
                10_000,
                5_000);

        for (int attempt = 1; attempt <= 6; attempt++) {
            long delay = pool.computeBackoffMs(attempt);
            assertThat(delay).isBetween(500L, 10_000L);
        }

        String truncated = IngestionWorkerPool.truncateError(new RuntimeException("x".repeat(3000)));
        assertThat(truncated.length()).isLessThanOrEqualTo(2000);
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final AtomicInteger batchCalls;
        private final Runnable beforeReturn;

        StubJdbcTemplate(AtomicInteger batchCalls, Runnable beforeReturn) {
            this.batchCalls = batchCalls;
            this.beforeReturn = beforeReturn;
        }

        @Override
        public int[] batchUpdate(String sql, BatchPreparedStatementSetter pss) {
            batchCalls.incrementAndGet();
            if (beforeReturn != null) {
                beforeReturn.run();
            }
            int size = pss.getBatchSize();
            int[] counts = new int[size];
            java.util.Arrays.fill(counts, 1);
            return counts;
        }
    }

    private static final class StubTransactionManager extends AbstractPlatformTransactionManager {
        private final AtomicBoolean committed;

        StubTransactionManager(AtomicBoolean committed) {
            this.committed = committed;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed.set(true);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
