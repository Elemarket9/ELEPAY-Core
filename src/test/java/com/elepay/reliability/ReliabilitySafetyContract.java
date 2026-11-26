package com.elepay.reliability;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** Dependency-free contract runner; also invoked by JUnit. No real payments or sleeps. */
public final class ReliabilitySafetyContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        checks = 0;
        BackoffAndJitterRetry retry = new BackoffAndJitterRetry();
        for (var strategy : BackoffAndJitterRetry.JitterStrategy.values()) {
            for (long base : new long[] {0, 1, Long.MAX_VALUE / 2, Long.MAX_VALUE}) {
                var config = new BackoffAndJitterRetry.RetryConfig(3, base, Long.MAX_VALUE, strategy);
                for (int attempt : new int[] {0, 1, 30, 62, 63, Integer.MAX_VALUE}) {
                    for (int sample = 0; sample < 100; sample++) {
                        long delay = retry.calculateDelay(attempt, config, Long.MAX_VALUE);
                        check(delay >= 0 && delay <= config.maxDelayMs(), "delay outside bounds");
                    }
                }
            }
        }
        var exact = new BackoffAndJitterRetry.RetryConfig(3, 3, 10,
                BackoffAndJitterRetry.JitterStrategy.NO_JITTER);
        check(retry.calculateDelay(0, exact, 0) == 3, "first delay");
        check(retry.calculateDelay(1, exact, 0) == 6, "second delay");
        check(retry.calculateDelay(Integer.MAX_VALUE, exact, 0) == 10, "saturation");
        expect(IllegalArgumentException.class, () -> new BackoffAndJitterRetry.RetryConfig(
                0, 1, 2, BackoffAndJitterRetry.JitterStrategy.FULL_JITTER));
        expect(IllegalArgumentException.class, () -> new BackoffAndJitterRetry.RetryConfig(
                1, -1, 2, BackoffAndJitterRetry.JitterStrategy.FULL_JITTER));
        expect(IllegalArgumentException.class, () -> new BackoffAndJitterRetry.RetryConfig(
                1, 3, 2, BackoffAndJitterRetry.JitterStrategy.FULL_JITTER));
        expect(IllegalArgumentException.class, () -> retry.calculateDelay(-1, exact, 0));
        expect(IllegalArgumentException.class, () -> retry.calculateDelay(0, exact, -1));

        var zero = new BackoffAndJitterRetry.RetryConfig(3, 0, 0,
                BackoffAndJitterRetry.JitterStrategy.NO_JITTER);
        AtomicInteger calls = new AtomicInteger();
        expect(SQLException.class, () -> retry.executeWithRetry(() -> {
            calls.incrementAndGet();
            throw new SQLException("temporary");
        }, zero, failure -> true));
        check(calls.get() == 3, "attempt budget");
        calls.set(0);
        AssertionError fatal = new AssertionError("fatal");
        try {
            retry.executeWithRetry(() -> { calls.incrementAndGet(); throw fatal; },
                    zero, failure -> true);
            throw new AssertionError("fatal error swallowed");
        } catch (AssertionError actual) {
            check(actual == fatal && calls.get() == 1, "fatal error retried or wrapped");
        }
        calls.set(0);
        try {
            expect(InterruptedException.class, () -> retry.executeWithRetry(() -> {
                calls.incrementAndGet();
                throw new InterruptedException("cancel");
            }, zero, failure -> true));
            check(Thread.currentThread().isInterrupted(), "interrupt flag lost");
            check(calls.get() == 1, "cancelled task retried");
        } finally {
            Thread.interrupted();
        }
        Thread.currentThread().interrupt();
        try {
            calls.set(0);
            expect(InterruptedException.class, () -> retry.executeWithRetry(
                    () -> calls.incrementAndGet(), zero, failure -> true));
            check(calls.get() == 0, "pre-interrupted task ran");
        } finally {
            Thread.interrupted();
        }

        DataSource unused = (DataSource) Proxy.newProxyInstance(
                ReliabilitySafetyContract.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> { throw new AssertionError("unexpected database access"); });
        DatabaseFailoverRecoveryService db = new DatabaseFailoverRecoveryService(unused);
        calls.set(0);
        SQLException unknown = new SQLException("connection lost after COMMIT", "08006");
        try {
            db.executeWithFailoverRetry(() -> {
                calls.incrementAndGet(); // models an already applied side effect
                throw unknown;
            }, 3, 0);
            throw new AssertionError("uncertain result hidden");
        } catch (SQLException actual) {
            check(actual == unknown && calls.get() == 1, "uncertain commit replayed");
        }
        calls.set(0);
        String result = db.executeWithFailoverRetry(() -> {
            if (calls.incrementAndGet() < 3) throw unknown;
            return "recovered";
        }, 2, 0, failure -> true); // test-only evidence: action has no financial effects
        check(result.equals("recovered") && calls.get() == 3, "explicit safe recovery");
        calls.set(0);
        expect(SQLException.class, () -> db.executeWithFailoverRetry(() -> {
            calls.incrementAndGet();
            throw new SQLException("syntax", "42601");
        }, 3, 0, failure -> true));
        check(calls.get() == 1, "non-failover retried");
        expect(IllegalArgumentException.class, () -> db.executeWithFailoverRetry(
                () -> 1, -1, 0));
        expect(IllegalArgumentException.class, () -> db.executeWithFailoverRetry(
                () -> 1, 2, Long.MAX_VALUE));
        Throwable first = new Exception("first"), second = new Exception("second", first);
        first.initCause(second);
        check(!db.isFailoverException(first), "cyclic cause chain");
        check(db.isFailoverException(new Exception(unknown)), "nested SQL classification");
        try {
            expect(InterruptedException.class, () -> db.executeWithFailoverRetry(
                    () -> { throw new InterruptedException("cancel"); }, 3, 0, failure -> true));
            check(Thread.currentThread().isInterrupted(), "database interrupt flag lost");
        } finally {
            Thread.interrupted();
        }
        System.out.println("PASS: " + checks + " reliability safety checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            check(type.isInstance(failure), "expected " + type + ", received " + failure);
            return;
        }
        throw new AssertionError("Expected " + type);
    }

    @FunctionalInterface
    private interface Action { void run() throws Exception; }
}
