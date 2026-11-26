/**
 * Async ingestion buffer module (Stage 2).
 *
 * <p>Provides a one-way SPI ({@link com.elepay.ingestion.IngestionHandler} and
 * {@link com.elepay.ingestion.IngestionQueue}) so domain modules ({@code checkout},
 * {@code rails}, {@code operations}) depend on {@code ingestion} with zero circular
 * dependencies. Owns the {@code ingestion_event} table.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Async Ingestion")
package com.elepay.ingestion;
