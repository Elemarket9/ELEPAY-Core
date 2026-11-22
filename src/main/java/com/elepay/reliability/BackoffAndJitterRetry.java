package com.elepay.reliability;

import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * Algorithm 087: Exponential backoff with Full Jitter.
 * Formula:
 *   temp = min(capMs, baseMs * 2^attempt)
 *   sleep = random_between(0, temp)
 * Avoids thundering herd problem on network retries while enforcing attempt budgets.
 */
@Service
public class BackoffAndJitterRetry {

    public enum JitterStrategy {
        FULL_JITTER,
        EQUAL_JITTER,
        DECORRELATED_JITTER,
        NO_JITTER
    }

    public record RetryConfig(
            int maxAttempts,
            long baseDelayMs,
            long maxDelayMs,
            JitterStrategy jitterStrategy
    ) {
        public static RetryConfig defaultConfig() {
            return new RetryConfig(4, 100, 2000, JitterStrategy.FULL_JITTER);
        }
    }

    private final Random random = new Random();

    public long calculateDelay(int attempt, RetryConfig config, long prevDelay) {
        long exponential = config.baseDelayMs() * (1L << Math.min(attempt, 30));
        long cappedExponential = Math.min(config.maxDelayMs(), exponential);

        return switch (config.jitterStrategy()) {
            case FULL_JITTER -> (cappedExponential > 0) ? (long) (random.nextDouble() * cappedExponential) : 0L;
            case EQUAL_JITTER -> {
                long half = cappedExponential / 2;
                yield half + (half > 0 ? (long) (random.nextDouble() * half) : 0L);
            }
            case DECORRELATED_JITTER -> {
                long high = Math.max(config.baseDelayMs(), prevDelay * 3);
                long randVal = (long) (config.baseDelayMs() + random.nextDouble() * (high - config.baseDelayMs()));
                yield Math.min(config.maxDelayMs(), randVal);
            }
            case NO_JITTER -> cappedExponential;
        };
    }

    /**
     * Executes callable with exponential backoff and jitter for retryable errors.
     */
    public <T> T executeWithRetry(Callable<T> task, RetryConfig config, Predicate<Throwable> isRetryable) throws Exception {
        int attempt = 0;
        long lastDelay = config.baseDelayMs();

        while (true) {
            try {
                return task.call();
            } catch (Throwable t) {
                attempt++;
                if (attempt >= config.maxAttempts() || !isRetryable.test(t)) {
                    if (t instanceof Exception e) throw e;
                    throw new RuntimeException(t);
                }

                long delay = calculateDelay(attempt, config, lastDelay);
                lastDelay = delay;

                if (delay > 0) {
                    Thread.sleep(delay);
                }
            }
        }
    }
}
