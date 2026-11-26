package com.elepay.reliability;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * Algorithm 087: bounded exponential backoff and jitter.
 * Delay arithmetic saturates before multiplication; calculation uses O(1) space
 * and at most 63 doubling steps. Retryability alone does not establish that a
 * financial operation is safe to replay: callers must use durable idempotency
 * or reconcile an unknown outcome before supplying a retryable decision.
 */
@Service
public class BackoffAndJitterRetry {
    public enum JitterStrategy {
        FULL_JITTER, EQUAL_JITTER, DECORRELATED_JITTER, NO_JITTER
    }

    public record RetryConfig(int maxAttempts, long baseDelayMs, long maxDelayMs,
                              JitterStrategy jitterStrategy) {
        public RetryConfig {
            if (maxAttempts < 1 || baseDelayMs < 0 || maxDelayMs < baseDelayMs) {
                throw new IllegalArgumentException("Invalid retry attempts or delay bounds");
            }
            Objects.requireNonNull(jitterStrategy, "jitterStrategy");
        }

        /** Compatibility preset; operational callers should supply validated policy. */
        public static RetryConfig defaultConfig() {
            return new RetryConfig(4, 100, 2000, JitterStrategy.FULL_JITTER);
        }
    }

    public long calculateDelay(int attempt, RetryConfig config, long prevDelay) {
        Objects.requireNonNull(config, "config");
        if (attempt < 0 || prevDelay < 0) {
            throw new IllegalArgumentException("Attempt and previous delay must be non-negative");
        }
        long exponential = config.baseDelayMs();
        // A signed long reaches its cap in at most 63 doublings.
        for (int i = 0; i < Math.min(attempt, 63); i++) {
            if (exponential >= config.maxDelayMs()
                    || exponential > config.maxDelayMs() / 2) {
                exponential = config.maxDelayMs();
                break;
            }
            exponential *= 2;
        }
        final long cap = exponential;
        return switch (config.jitterStrategy()) {
            case FULL_JITTER -> below(cap);
            case EQUAL_JITTER -> cap / 2 + below(cap - cap / 2);
            case DECORRELATED_JITTER -> {
                long triple = prevDelay > Long.MAX_VALUE / 3
                        ? Long.MAX_VALUE : prevDelay * 3;
                long high = Math.max(config.baseDelayMs(), triple);
                long sample = config.baseDelayMs() + below(high - config.baseDelayMs());
                yield Math.min(config.maxDelayMs(), sample);
            }
            case NO_JITTER -> cap;
        };
    }

    /** Uniform integer in [0, bound), or zero when bound is zero. */
    private static long below(long bound) {
        return bound == 0 ? 0 : ThreadLocalRandom.current().nextLong(bound);
    }

    /**
     * At most maxAttempts task invocations. Errors and cancellation never retry.
     * Attempt zero is the first retry delay: base * 2^0.
     */
    public <T> T executeWithRetry(Callable<T> task, RetryConfig config,
                                 Predicate<Throwable> isRetryable) throws Exception {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(isRetryable, "isRetryable");
        long lastDelay = config.baseDelayMs();
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Retry execution interrupted");
            }
            try {
                return task.call();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception failure) {
                if (attempt >= config.maxAttempts() - 1
                        || Thread.currentThread().isInterrupted()
                        || !isRetryable.test(failure)) {
                    throw failure;
                }
                lastDelay = calculateDelay(attempt, config, lastDelay);
                try {
                    if (lastDelay > 0) Thread.sleep(lastDelay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
    }
}
