package com.elepay.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that concurrent {@link Audit#record} writes are serialized via PostgreSQL advisory
 * lock so {@code prev_hash} never forks under READ COMMITTED concurrency.
 */
class AuditConcurrencyIT extends PostgresTestBase {

    @Autowired
    private Audit audit;

    @Autowired
    private AuditCheckpoints auditCheckpoints;

    @Test
    void sixteenConcurrentThreadsPreserveHashChainIntegrity() throws Exception {
        int threads = 16;
        int entriesPerThread = 5;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                final int threadIdx = t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < entriesPerThread; i++) {
                        audit.record(
                                "worker-" + threadIdx,
                                "audit.concurrency_test",
                                "item:" + threadIdx + ":" + i,
                                Map.of("thread", threadIdx, "seq", i)
                        );
                    }
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        Audit.VerificationResult chainResult = audit.verifyChain();
        assertThat(chainResult.valid()).isTrue();
        assertThat(chainResult.entriesVerified()).isGreaterThanOrEqualTo((long) threads * entriesPerThread);

        var checkpoint = auditCheckpoints.create();
        assertThat(checkpoint).isPresent();
        var cpVerify = auditCheckpoints.verify();
        assertThat(cpVerify.valid()).isTrue();
    }
}
