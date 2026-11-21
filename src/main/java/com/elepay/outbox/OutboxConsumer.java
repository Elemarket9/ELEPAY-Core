package com.elepay.outbox;

/**
 * In-process consumer of outbox events. Delivery is at least once; the
 * dispatcher records processed ids in {@code outbox_consumer_inbox} in the
 * consumer's transaction so duplicates are skipped, but implementations must
 * still be safe to re-run.
 */
public interface OutboxConsumer {
    String name();

    boolean accepts(String eventType);

    void handle(Outbox.Event event);
}
