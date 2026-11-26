package com.elepay.ingestion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for the async ingestion pipeline: enqueue volume,
 * worker completions, retries, dead-letter transitions, and backpressure rejections.
 */
@Component
public class IngestionMetrics {

    public static final String ENQUEUED = "elepay.ingestion.enqueued";
    public static final String COMPLETED = "elepay.ingestion.completed";
    public static final String RETRIED = "elepay.ingestion.retried";
    public static final String DEAD = "elepay.ingestion.dead";
    public static final String BACKPRESSURE = "elepay.ingestion.backpressure";
    public static final String PROCESSING_DURATION = "elepay.ingestion.processing.duration";

    private final Counter enqueuedCounter;
    private final Counter completedCounter;
    private final Counter retriedCounter;
    private final Counter deadCounter;
    private final Counter backpressureCounter;
    private final Timer processingTimer;

    @Autowired
    public IngestionMetrics(MeterRegistry registry) {
        this.enqueuedCounter = Counter.builder(ENQUEUED)
                .description("Total ingestion commands durably enqueued")
                .register(registry);
        this.completedCounter = Counter.builder(COMPLETED)
                .description("Total ingestion events processed successfully")
                .register(registry);
        this.retriedCounter = Counter.builder(RETRIED)
                .description("Total ingestion event retry attempts scheduled")
                .register(registry);
        this.deadCounter = Counter.builder(DEAD)
                .description("Total ingestion events moved to the dead-letter state")
                .register(registry);
        this.backpressureCounter = Counter.builder(BACKPRESSURE)
                .description("Total ingestion requests rejected due to queue backpressure")
                .register(registry);
        this.processingTimer = Timer.builder(PROCESSING_DURATION)
                .description("Execution latency of ingestion handlers")
                .register(registry);
    }

    /** Convenience constructor for unit tests without a Spring context. */
    public IngestionMetrics() {
        this(new SimpleMeterRegistry());
    }

    public void recordEnqueued() {
        enqueuedCounter.increment();
    }

    public void recordEnqueued(long count) {
        if (count > 0) {
            enqueuedCounter.increment(count);
        }
    }

    public void recordCompleted() {
        completedCounter.increment();
    }

    public void recordCompleted(Duration duration) {
        completedCounter.increment();
        if (duration != null && !duration.isNegative()) {
            processingTimer.record(duration);
        }
    }

    public void recordRetried() {
        retriedCounter.increment();
    }

    public void recordDead() {
        deadCounter.increment();
    }

    public void recordBackpressure() {
        backpressureCounter.increment();
    }

    public Counter enqueuedCounter() {
        return enqueuedCounter;
    }

    public Counter completedCounter() {
        return completedCounter;
    }

    public Counter retriedCounter() {
        return retriedCounter;
    }

    public Counter deadCounter() {
        return deadCounter;
    }

    public Counter backpressureCounter() {
        return backpressureCounter;
    }

    public Timer processingTimer() {
        return processingTimer;
    }

    public double enqueuedCount() {
        return enqueuedCounter.count();
    }

    public double completedCount() {
        return completedCounter.count();
    }

    public double retriedCount() {
        return retriedCounter.count();
    }

    public double deadCount() {
        return deadCounter.count();
    }

    public double backpressureCount() {
        return backpressureCounter.count();
    }
}
