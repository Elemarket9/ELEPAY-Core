package com.elepay.ingestion;

/**
 * One-way SPI implemented by domain modules to process asynchronous ingestion events.
 * Domain modules depend on {@code com.elepay.ingestion}; {@code ingestion} never depends
 * on domain modules.
 */
public interface IngestionHandler {

    /**
     * Event type discriminator handled by this implementation (for example {@code CHECKOUT_CREATE}).
     */
    String eventType();

    /**
     * Processes the claimed ingestion record and optionally returns a JSON result string
     * to be stored in {@code ingestion_event.result}.
     */
    String handle(IngestionRecord record);
}
