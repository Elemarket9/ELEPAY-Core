package com.elepay.rails;

import java.util.Map;

/**
 * ELEPAY's own payment rails: the channels through which money enters and
 * leaves the ELEPAY ledger (mobile money cash-in/cash-out, card acceptance,
 * bank transfer). ELEPAY is the ledger and the processor; there is no
 * third-party payment aggregator in the path. Each rail implementation
 * connects ELEPAY's processing to the corresponding network. A success result must carry the exact
 * amount, currency and non-empty authoritative provider transaction reference.
 *
 * <p>Every call carries ELEPAY's own unique reference so retries and status
 * queries are idempotent. Implementations never throw for transport failures:
 * they return {@link Status#UNKNOWN} so funds stay reserved and the outcome is
 * recovered by query, never by charging or paying out again.
 */
public interface PaymentRail {

    /** {@code RETURNED}: a completed payout or refund that the receiving network sent back. */
    enum Status { PENDING, SUCCEEDED, FAILED, UNKNOWN, NOT_FOUND, RETURNED, UNAVAILABLE }

    record Charge(String reference, long amount, String currency, String channel, String msisdn,
                  String network, String email, String customerName, String description, String returnUrl) {}

    record Payout(String reference, long amount, String currency, String type, String network,
                  String accountNumber, String accountName, String description) {}

    record Refund(String reference, String originalReference, String originalRailReference, long amount,
                  String currency, String msisdn, String network) {}

    record Result(Status status, String railReference, Long amount, String currency, Long fee,
                  String redirectUrl, String message) {
        public static Result unknown(String message) {
            return new Result(Status.UNKNOWN, null, null, null, null, null, message);
        }

        public static Result unavailable(String message) {
            return new Result(Status.UNAVAILABLE, null, null, null, null, null, message);
        }
    }

    /** A verified, normalised callback. {@code dedupeKey} identifies redeliveries of the same notification. */
    record Callback(String dedupeKey, String eventType, String direction, String reference,
                    String railReference, Result result) {}

    String name();

    /** Provider capabilities are checked before creating or reserving a transaction. */
    default boolean supports(String direction, String channel, String network) { return true; }

    Result charge(Charge charge);

    Result payout(Payout payout);

    Result refund(Refund refund);

    Result query(String direction, String reference, String railReference);

    /**
     * Verifies authenticity and parses the callback. Throws
     * {@link InvalidCallbackException} if the signature or source is not trusted.
     */
    Callback verifyCallback(Map<String, String> headers, String body);

    /**
     * True when callbacks are cryptographically signed and carry amounts that
     * can be trusted. Otherwise ELEPAY confirms every callback with a status query.
     */
    boolean callbacksAreAuthoritative();

    /**
     * Submits an external payment action guarded by Algorithm 086 (PartnerCircuitBreaker)
     * and Algorithm 087 (BackoffAndJitterRetry).
     */
    default Result submitWithResilience(
            com.elepay.reliability.PartnerCircuitBreaker circuitBreaker,
            com.elepay.reliability.BackoffAndJitterRetry retry,
            com.elepay.reliability.BackoffAndJitterRetry.RetryConfig retryConfig,
            java.util.concurrent.Callable<Result> submission
    ) {
        if (circuitBreaker != null) {
            try {
                circuitBreaker.acquirePermission();
            } catch (com.elepay.reliability.PartnerCircuitBreaker.CircuitBreakerOpenException e) {
                return Result.unavailable("Circuit breaker OPEN for rail " + name() + ": " + e.getMessage());
            }
        }

        try {
            Result result;
            if (retry != null && retryConfig != null) {
                result = retry.executeWithRetry(
                        submission,
                        retryConfig,
                        PaymentRail::isRetryable
                );
            } else {
                result = submission.call();
            }

            if (circuitBreaker != null) {
                if (result != null && result.status() == Status.UNAVAILABLE) {
                    circuitBreaker.recordFailure();
                } else {
                    circuitBreaker.recordSuccess();
                }
            }
            return result;
        } catch (Exception e) {
            if (circuitBreaker != null) {
                circuitBreaker.recordFailure();
            }
            // Submission may already have reached the partner rail; never return UNAVAILABLE
            // (which RailTransactions treats as pre-submission FAILED and releases reserved funds).
            return Result.unknown("Rail " + name() + " submission failed: " + e.getMessage());
        }
    }

    default Result submitWithResilience(
            com.elepay.reliability.PartnerCircuitBreaker circuitBreaker,
            com.elepay.reliability.BackoffAndJitterRetry retry,
            java.util.concurrent.Callable<Result> submission
    ) {
        return submitWithResilience(circuitBreaker, retry,
                com.elepay.reliability.BackoffAndJitterRetry.RetryConfig.defaultConfig(), submission);
    }

    static boolean isRetryable(Throwable t) {
        if (t == null) return false;
        if (t instanceof java.io.IOException) return true;
        if (t instanceof java.util.concurrent.TimeoutException) return true;
        if (t instanceof java.net.SocketException) return true;
        if (t.getCause() != null && isRetryable(t.getCause())) return true;
        if (t.getMessage() != null) {
            String m = t.getMessage().toLowerCase();
            return m.contains("timeout") || m.contains("connection reset") || m.contains("503")
                    || m.contains("unavailable") || m.contains("network");
        }
        return false;
    }

    /**
     * Decorates an existing PaymentRail with circuit breaking (Algo 086) and exponential backoff retry (Algo 087).
     */
    static PaymentRail resilient(
            PaymentRail delegate,
            com.elepay.reliability.PartnerCircuitBreaker circuitBreaker,
            com.elepay.reliability.BackoffAndJitterRetry retry
    ) {
        return resilient(delegate, circuitBreaker, retry,
                com.elepay.reliability.BackoffAndJitterRetry.RetryConfig.defaultConfig());
    }

    static PaymentRail resilient(
            PaymentRail delegate,
            com.elepay.reliability.PartnerCircuitBreaker circuitBreaker,
            com.elepay.reliability.BackoffAndJitterRetry retry,
            com.elepay.reliability.BackoffAndJitterRetry.RetryConfig config
    ) {
        return new PaymentRail() {
            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public boolean supports(String direction, String channel, String network) {
                return delegate.supports(direction, channel, network);
            }

            @Override
            public Result charge(Charge charge) {
                return submitWithResilience(circuitBreaker, retry, config, () -> delegate.charge(charge));
            }

            @Override
            public Result payout(Payout payout) {
                return submitWithResilience(circuitBreaker, retry, config, () -> delegate.payout(payout));
            }

            @Override
            public Result refund(Refund refund) {
                return submitWithResilience(circuitBreaker, retry, config, () -> delegate.refund(refund));
            }

            @Override
            public Result query(String direction, String reference, String railReference) {
                return submitWithResilience(circuitBreaker, retry, config, () -> delegate.query(direction, reference, railReference));
            }

            @Override
            public Callback verifyCallback(Map<String, String> headers, String body) {
                return delegate.verifyCallback(headers, body);
            }

            @Override
            public boolean callbacksAreAuthoritative() {
                return delegate.callbacksAreAuthoritative();
            }
        };
    }

    class InvalidCallbackException extends RuntimeException {
        public InvalidCallbackException(String message) {
            super(message);
        }
    }
}
