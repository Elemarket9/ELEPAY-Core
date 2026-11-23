package com.elepay.fx;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * An FX conversion and domestic-settlement partner, for example a contracted bank.
 * Legacy transfer status callbacks support historical reconciliation only.
 */
public interface FxPartner {

    enum Status { PENDING, PAID, FAILED, RETURNED, UNKNOWN, NOT_FOUND }

    record Quote(String reference, BigDecimal rate, OffsetDateTime expiresAt) {}

    record Execution(Status status, String partnerReference, String message) {}

    /** Verified partner notification about a transfer. */
    record Notification(String dedupeKey, String reference, Status status, String message) {}

    String name();

    /** Partner-confirmed rate for converting {@code sourceAmount} (minor units) on the corridor. */
    Quote quote(String sourceCurrency, String destinationCurrency, long sourceAmount);

    /** True while the partner still honours the quote. */
    boolean honours(String quoteReference);

    Execution query(String reference);

    Notification verify(Map<String, String> headers, String body);

}
