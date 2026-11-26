package com.elepay.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Stage 2 asynchronous ingestion buffer.
 */
@Component
@ConfigurationProperties(prefix = "elepay.ingestion")
public class IngestionProperties {

    private boolean enabled = true;
    private int queueCapacity = 10_000;
    private double highWatermarkRatio = 0.90;
    private int batchSize = 100;
    private long flushIntervalMs = 5;
    private long enqueueTimeoutMs = 5_000;
    private int maxAttempts = 5;
    private int retryAfterSeconds = 1;

    private int workerConcurrency = 16;
    private int pollBatchSize = 50;
    private long pollIntervalMs = 25;
    private long leaseDurationSeconds = 60;
    private long staleLeaseScanMs = 15_000;
    private long retryBaseMs = 500;
    private long retryMaxMs = 60_000;
    private long shutdownDrainTimeoutMs = 15_000;

    private Redis redis = new Redis();
    private Callbacks callbacks = new Callbacks();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getQueueCapacity() { return Math.max(1, queueCapacity); }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public double getHighWatermarkRatio() {
        if (highWatermarkRatio <= 0.0 || highWatermarkRatio > 1.0) {
            return 0.90;
        }
        return highWatermarkRatio;
    }
    public void setHighWatermarkRatio(double highWatermarkRatio) { this.highWatermarkRatio = highWatermarkRatio; }

    public int highWatermark() {
        return Math.max(1, (int) Math.floor(getQueueCapacity() * getHighWatermarkRatio()));
    }

    public int getBatchSize() { return Math.max(1, batchSize); }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public long getFlushIntervalMs() { return Math.max(1L, flushIntervalMs); }
    public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }

    public long getEnqueueTimeoutMs() { return Math.max(100L, enqueueTimeoutMs); }
    public void setEnqueueTimeoutMs(long enqueueTimeoutMs) { this.enqueueTimeoutMs = enqueueTimeoutMs; }

    public int getMaxAttempts() { return Math.max(1, maxAttempts); }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public int getRetryAfterSeconds() { return Math.max(1, retryAfterSeconds); }
    public void setRetryAfterSeconds(int retryAfterSeconds) { this.retryAfterSeconds = retryAfterSeconds; }

    public int getWorkerConcurrency() { return Math.max(1, workerConcurrency); }
    public void setWorkerConcurrency(int workerConcurrency) { this.workerConcurrency = workerConcurrency; }

    public int getPollBatchSize() { return Math.max(1, pollBatchSize); }
    public void setPollBatchSize(int pollBatchSize) { this.pollBatchSize = pollBatchSize; }

    public long getPollIntervalMs() { return Math.max(1L, pollIntervalMs); }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public long getLeaseDurationSeconds() { return Math.max(5L, leaseDurationSeconds); }
    public void setLeaseDurationSeconds(long leaseDurationSeconds) { this.leaseDurationSeconds = leaseDurationSeconds; }

    public long getStaleLeaseScanMs() { return Math.max(1_000L, staleLeaseScanMs); }
    public void setStaleLeaseScanMs(long staleLeaseScanMs) { this.staleLeaseScanMs = staleLeaseScanMs; }

    public long getRetryBaseMs() { return Math.max(10L, retryBaseMs); }
    public void setRetryBaseMs(long retryBaseMs) { this.retryBaseMs = retryBaseMs; }

    public long getRetryMaxMs() { return Math.max(getRetryBaseMs(), retryMaxMs); }
    public void setRetryMaxMs(long retryMaxMs) { this.retryMaxMs = retryMaxMs; }

    public long getShutdownDrainTimeoutMs() { return Math.max(500L, shutdownDrainTimeoutMs); }
    public void setShutdownDrainTimeoutMs(long shutdownDrainTimeoutMs) { this.shutdownDrainTimeoutMs = shutdownDrainTimeoutMs; }

    public Redis getRedis() { return redis; }
    public void setRedis(Redis redis) { this.redis = redis == null ? new Redis() : redis; }

    public Callbacks getCallbacks() { return callbacks; }
    public void setCallbacks(Callbacks callbacks) { this.callbacks = callbacks == null ? new Callbacks() : callbacks; }

    public static class Redis {
        private boolean enabled = false;
        private String streamKey = "elepay:ingestion:stream";
        private String consumerGroup = "elepay-ingestion-writers";
        private String consumerName = "";
        private long maxStreamLength = 100_000;
        private int readBatchSize = 100;
        private long breakerCooldownMs = 10_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getStreamKey() { return streamKey == null || streamKey.isBlank() ? "elepay:ingestion:stream" : streamKey; }
        public void setStreamKey(String streamKey) { this.streamKey = streamKey; }

        public String getConsumerGroup() { return consumerGroup == null || consumerGroup.isBlank() ? "elepay-ingestion-writers" : consumerGroup; }
        public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }

        public String getConsumerName() { return consumerName == null ? "" : consumerName; }
        public void setConsumerName(String consumerName) { this.consumerName = consumerName; }

        public long getMaxStreamLength() { return Math.max(1_000L, maxStreamLength); }
        public void setMaxStreamLength(long maxStreamLength) { this.maxStreamLength = maxStreamLength; }

        public int getReadBatchSize() { return Math.max(1, readBatchSize); }
        public void setReadBatchSize(int readBatchSize) { this.readBatchSize = readBatchSize; }

        public long getBreakerCooldownMs() { return Math.max(1_000L, breakerCooldownMs); }
        public void setBreakerCooldownMs(long breakerCooldownMs) { this.breakerCooldownMs = breakerCooldownMs; }
    }

    public static class Callbacks {
        private boolean async = false;

        public boolean isAsync() { return async; }
        public void setAsync(boolean async) { this.async = async; }
    }
}
