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
    enum Status { PENDING, SUCCEEDED, FAILED, UNKNOWN, NOT_FOUND, RETURNED }

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

    class InvalidCallbackException extends RuntimeException {
        public InvalidCallbackException(String message) {
            super(message);
        }
    }
}
