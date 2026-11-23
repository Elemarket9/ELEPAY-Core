package com.elepay.enterprise.connectors;

import java.time.LocalDate;
import java.util.List;

/**
 * Universal Enterprise Connector Interface.
 * Standardizes API communication, signed webhook processing, SFTP/file-based
 * continuous reconciliation, resilience circuit breakers, and rate limiting.
 */
public interface EnterpriseConnector {

    /** Returns unique connector identifier (e.g. "acme-wallet-v1"). */
    String getConnectorId();

    /** Returns connector type (e.g. "EXTERNAL_WALLET", "REST_WEBHOOK", "SFTP_RECON"). */
    String getConnectorType();

    /** Returns active circuit breaker instance for this connector. */
    CircuitBreaker getCircuitBreaker();

    /** Returns active rate limiter instance for this connector. */
    RateLimiter getRateLimiter();

    /** Generates HMAC-SHA256 signature for outgoing webhook payload. */
    String signWebhook(String secret, long timestampSeconds, String payload);

    /** Verifies HMAC-SHA256 signature of incoming webhook. */
    boolean verifyWebhookSignature(String secret, long timestampSeconds, String payload, String signature);

    /** Formats daily end-of-day SFTP batch reconciliation file. */
    String generateDailyReconciliationFile(String batchId, LocalDate batchDate, String currency, List<SftpReconRecord> records);

    /** Parses and cryptographically validates SFTP batch reconciliation file. */
    SftpReconBatch parseReconciliationFile(String fileContent);
}
