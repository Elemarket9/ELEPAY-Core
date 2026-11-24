package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("NetworkHealthDetector Concurrency Tests")
class NetworkHealthConcurrencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void halfOpenAllowsOnlySingleConcurrentProbe() throws Exception {
        NetworkHealthDetector detector = new NetworkHealthDetector(mock(JdbcClient.class));
        detector.tripCircuit("mtn");

        // Backdate lastStateChange past the 30s cooldown so the first caller transitions OPEN -> HALF_OPEN
        Field statesField = NetworkHealthDetector.class.getDeclaredField("railStates");
        statesField.setAccessible(true);
        Map<String, Object> states = (Map<String, Object>) statesField.get(detector);
        Object railState = states.get("mtn");
        Field lastChangeField = railState.getClass().getDeclaredField("lastStateChange");
        lastChangeField.setAccessible(true);
        lastChangeField.set(railState, Instant.now().minusSeconds(60));

        int threads = 10;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger permitted = new AtomicInteger(0);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (detector.isRailAvailable("mtn")) {
                        permitted.incrementAndGet();
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertThat(permitted.get()).isEqualTo(1);
        assertThat(detector.getHealth("mtn").circuitState()).isEqualTo(NetworkHealthDetector.CircuitState.HALF_OPEN);

        // Recording probe success closes circuit and allows traffic again
        detector.record("mtn", true, false, 50);
        assertThat(detector.getHealth("mtn").circuitState()).isEqualTo(NetworkHealthDetector.CircuitState.CLOSED);
        assertThat(detector.isRailAvailable("mtn")).isTrue();
    }
}
