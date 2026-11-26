package com.elepay.reliability;

import com.elepay.common.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Adaptive Overload Protection, Per-Merchant Bulkhead Isolation &amp; AIMD Concurrency Controller
 * (Requirement 9).
 *
 * <ul>
 *   <li>Continuously monitors JVM heap utilization, CPU load, transaction latency, and active
 *       in-flight concurrency.</li>
 *   <li>Applies Additive-Increase / Multiplicative-Decrease (AIMD) adaptive concurrency limits
 *       (Vegas/Gradient-inspired) when transaction latency exceeds {@code targetLatencyMs} or
 *       heap utilization crosses {@code highHeapThreshold}.</li>
 *   <li>Enforces per-merchant noisy-neighbor bulkheads so a flash sale on one whale merchant
 *       cannot consume more than {@code maxInFlightPerMerchant} concurrent slots.</li>
 *   <li>Prioritizes {@link LoadShedder.PriorityTier#CRITICAL} financial transactions over
 *       reporting and notifications, shedding lower tiers first with HTTP 429 or 503 and
 *       explicit retry-after guidance.</li>
 *   <li>Recovers concurrency limits automatically as latency and resource pressure stabilize.</li>
 * </ul>
 */
@Service
public class AdaptiveOverloadProtectionService {

    public record OverloadTelemetry(
            int currentConcurrencyLimit,
            int minConcurrencyLimit,
            int maxConcurrencyLimit,
            int globalInFlight,
            double heapUsageRatio,
            double cpuLoadRatio,
            long lastObservedLatencyMs,
            long totalShed429,
            long totalShed503,
            int retryAfterSeconds
    ) {}

    private final LoadShedder loadShedder;
    private final int minLimit;
    private final int maxLimit;
    private final int maxPerMerchant;
    private final long targetLatencyMs;
    private final double highHeapThreshold;

    private final AtomicInteger adaptiveLimit;
    private final AtomicInteger globalInFlight = new AtomicInteger(0);
    private final AtomicLong lastLatencyMs = new AtomicLong(5L);
    private final AtomicLong totalShed429 = new AtomicLong(0);
    private final AtomicLong totalShed503 = new AtomicLong(0);
    private final Map<UUID, Semaphore> merchantBulkheads = new ConcurrentHashMap<>();

    @Autowired
    public AdaptiveOverloadProtectionService(
            LoadShedder loadShedder,
            @Value("${elepay.overload.min-concurrency:16}") int minLimit,
            @Value("${elepay.overload.max-concurrency:256}") int maxLimit,
            @Value("${elepay.overload.max-per-merchant:32}") int maxPerMerchant,
            @Value("${elepay.overload.target-latency-ms:150}") long targetLatencyMs,
            @Value("${elepay.overload.high-heap-threshold:0.88}") double highHeapThreshold,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.loadShedder = loadShedder;
        this.minLimit = Math.max(4, minLimit);
        this.maxLimit = Math.max(this.minLimit, maxLimit);
        this.maxPerMerchant = Math.max(2, maxPerMerchant);
        this.targetLatencyMs = Math.max(10L, targetLatencyMs);
        this.highHeapThreshold = Math.min(0.98, Math.max(0.50, highHeapThreshold));
        this.adaptiveLimit = new AtomicInteger(this.maxLimit);

        if (meterRegistry != null) {
            meterRegistry.gauge("elepay.overload.adaptive.limit", adaptiveLimit);
            meterRegistry.gauge("elepay.overload.global.in_flight", globalInFlight);
            meterRegistry.gauge("elepay.overload.shed.429", totalShed429);
            meterRegistry.gauge("elepay.overload.shed.503", totalShed503);
        }
    }

    private static final int MAX_TRACKED_MERCHANT_BULKHEADS = 10_000;

    /**
     * Executes a workload protected by per-merchant noisy-neighbor bulkheads, priority-tier
     * load shedding, and AIMD adaptive concurrency limits.
     */
    public <T> T executeProtected(
            UUID merchantId,
            LoadShedder.PriorityTier tier,
            Supplier<T> action) {
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(action, "action must not be null");

        // 1. Check JVM Heap pressure for non-critical workloads
        double heapRatio = currentHeapUsageRatio();
        if (heapRatio >= highHeapThreshold && tier != LoadShedder.PriorityTier.CRITICAL) {
            totalShed503.incrementAndGet();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "jvm_memory_backpressure",
                    "Instance memory pressure elevated (Retry-After: " + recommendedRetryAfterSeconds() + "s)");
        }

        // 2. Atomically reserve global slot before checking priority-tier threshold and concurrency limit
        int reservedSlot = globalInFlight.incrementAndGet();
        boolean globalSlotHeld = true;
        Semaphore merchantSem = null;
        boolean merchantPermitHeld = false;
        boolean loadShedderIncremented = false;
        long startNanos = System.nanoTime();
        boolean success = false;
        try {
            int limit = adaptiveLimit.get();
            double loadFactor = (double) (reservedSlot - 1) / Math.max(1, limit);
            if (tier != LoadShedder.PriorityTier.CRITICAL && loadFactor >= tier.getSheddingThreshold()) {
                totalShed503.incrementAndGet();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "priority_load_shed",
                        "Non-critical workload shed at load factor "
                                + String.format(java.util.Locale.ROOT, "%.2f", loadFactor)
                                + " (Retry-After: " + recommendedRetryAfterSeconds() + "s)");
            }
            if (reservedSlot > limit) {
                totalShed503.incrementAndGet();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "adaptive_concurrency_limit_reached",
                        "Adaptive concurrency limit (" + limit + ") reached (Retry-After: "
                                + recommendedRetryAfterSeconds() + "s)");
            }

            // 3. Per-merchant noisy-neighbor bulkhead check
            if (merchantId != null) {
                evictIdleBulkheadsIfNeeded();
                merchantSem = merchantBulkheads.computeIfAbsent(merchantId, id -> new Semaphore(maxPerMerchant));
                if (!merchantSem.tryAcquire()) {
                    totalShed429.incrementAndGet();
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "merchant_bulkhead_saturated",
                            "Per-merchant concurrency bulkhead (" + maxPerMerchant + ") saturated for merchant "
                                    + merchantId + " (Retry-After: " + recommendedRetryAfterSeconds() + "s)");
                }
                merchantPermitHeld = true;
            }

            loadShedder.incrementLoad();
            loadShedderIncremented = true;
            startNanos = System.nanoTime();
            T res = action.get();
            success = true;
            return res;
        } finally {
            try {
                if (loadShedderIncremented) {
                    long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
                    lastLatencyMs.set(elapsedMs);
                    updateAdaptiveLimit(elapsedMs, success, heapRatio);
                }
            } finally {
                if (loadShedderIncremented) {
                    try {
                        loadShedder.decrementLoad();
                    } catch (RuntimeException ignored) {
                    }
                }
                if (globalSlotHeld) {
                    globalInFlight.decrementAndGet();
                }
                if (merchantPermitHeld && merchantSem != null) {
                    merchantSem.release();
                }
            }
        }
    }

    private void evictIdleBulkheadsIfNeeded() {
        if (merchantBulkheads.size() < MAX_TRACKED_MERCHANT_BULKHEADS) {
            return;
        }
        var iter = merchantBulkheads.entrySet().iterator();
        int removed = 0;
        while (iter.hasNext() && removed < 1000) {
            var entry = iter.next();
            Semaphore sem = entry.getValue();
            if (sem.availablePermits() == maxPerMerchant && !sem.hasQueuedThreads()) {
                iter.remove();
                removed++;
            }
        }
    }

    /**
     * AIMD (Additive Increase / Multiplicative Decrease) concurrency window adjustment.
     */
    void updateAdaptiveLimit(long observedLatencyMs, boolean success, double heapRatio) {
        if (!success || observedLatencyMs > targetLatencyMs * 2 || heapRatio >= highHeapThreshold) {
            // Multiplicative decrease (0.85x) bounded by minLimit
            adaptiveLimit.updateAndGet(cur -> Math.max(minLimit, (int) Math.floor(cur * 0.85)));
        } else if (observedLatencyMs <= targetLatencyMs) {
            // Additive increase (+1) bounded by maxLimit
            adaptiveLimit.updateAndGet(cur -> Math.min(maxLimit, cur + 1));
        }
    }

    public int recommendedRetryAfterSeconds() {
        double load = (double) globalInFlight.get() / Math.max(1, adaptiveLimit.get());
        if (load >= 1.0) return 5;
        if (load >= 0.85) return 2;
        return 1;
    }

    public double currentHeapUsageRatio() {
        try {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            long used = mem.getHeapMemoryUsage().getUsed();
            long max = mem.getHeapMemoryUsage().getMax();
            if (max <= 0) return 0.2;
            return Math.min(1.0, Math.max(0.0, (double) used / max));
        } catch (Exception e) {
            return 0.2;
        }
    }

    public double currentCpuLoadRatio() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            double avg = os.getSystemLoadAverage();
            int cores = Math.max(1, os.getAvailableProcessors());
            if (avg < 0) return 0.1;
            return Math.min(1.0, avg / cores);
        } catch (Exception e) {
            return 0.1;
        }
    }

    public OverloadTelemetry telemetry() {
        return new OverloadTelemetry(
                adaptiveLimit.get(),
                minLimit,
                maxLimit,
                globalInFlight.get(),
                currentHeapUsageRatio(),
                currentCpuLoadRatio(),
                lastLatencyMs.get(),
                totalShed429.get(),
                totalShed503.get(),
                recommendedRetryAfterSeconds()
        );
    }
}
