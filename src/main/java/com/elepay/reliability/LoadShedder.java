package com.elepay.reliability;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm 089: Load shedding and backpressure.
 * Protects critical financial transactions (ledger posting, money movements) by shedding
 * non-critical background traffic (telemetry, recommendations, batch sync) under elevated system load.
 */
@Service
public class LoadShedder {

    public enum PriorityTier {
        CRITICAL(1.00), // Never shed (financial commands, auth)
        HIGH(0.90),     // Shed only when system capacity > 90%
        MEDIUM(0.75),   // Shed when capacity > 75%
        LOW(0.60);      // Shed when capacity > 60%

        private final double sheddingThreshold;

        PriorityTier(double sheddingThreshold) {
            this.sheddingThreshold = sheddingThreshold;
        }

        public double getSheddingThreshold() { return sheddingThreshold; }
    }

    public record SheddingDecision(boolean allowed, PriorityTier tier, double currentLoadFactor, String reason) {}

    private final JdbcClient db;
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);
    private final int maxCapacity = 100;
    private final AtomicLong shedCounter = new AtomicLong(0);

    public LoadShedder(JdbcClient db) {
        this.db = db;
    }

    public SheddingDecision evaluateAdmission(PriorityTier tier) {
        double currentLoad = (double) inFlightRequests.get() / maxCapacity;

        if (tier == PriorityTier.CRITICAL) {
            return new SheddingDecision(true, tier, currentLoad, "CRITICAL tier always admitted");
        }

        if (currentLoad >= tier.getSheddingThreshold()) {
            shedCounter.incrementAndGet();
            return new SheddingDecision(false, tier, currentLoad,
                    String.format("Load factor %.2f exceeds threshold %.2f for %s tier",
                            currentLoad, tier.getSheddingThreshold(), tier.name()));
        }

        return new SheddingDecision(true, tier, currentLoad, "Admitted");
    }

    public void incrementLoad() {
        inFlightRequests.incrementAndGet();
    }

    public void decrementLoad() {
        inFlightRequests.decrementAndGet();
    }

    public void setInFlightForTesting(int count) {
        inFlightRequests.set(count);
    }

    public long getShedCount() {
        return shedCounter.get();
    }
}
