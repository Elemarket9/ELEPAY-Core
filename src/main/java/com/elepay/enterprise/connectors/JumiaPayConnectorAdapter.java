package com.elepay.enterprise.connectors;

import com.elepay.enterprise.wallet.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Universal Enterprise Connector Adapter for JumiaPay Ghana.
 *
 * <p>In production this wraps the disabled {@link JumiaPayContractAdapter}: every wallet call fails
 * closed until an authorized JumiaPay API contract is implemented. It is not a live connection.
 * Tests may wrap an isolated test double through the package-visible constructor.
 *
 * <p>Wraps external wallet Mode A operations with enterprise resilience features:
 * - REST API client capabilities
 * - HMAC-SHA256 signed webhook generation and verification
 * - SFTP/file-based batch reconciliation
 * - Resilient Circuit Breaker
 * - Token-bucket Rate Limiter
 */
@Component
public class JumiaPayConnectorAdapter implements EnterpriseConnector {

    public static final String CONNECTOR_ID = "jumiapay-gh-v1";
    public static final String CONNECTOR_TYPE = "JUMIAPAY";
    public static final long DEFAULT_RATE_LIMIT_RPM = 600L; // 600 req/min

    private final ExternalWalletConnector walletAdapter;
    private final ConnectorWebhookSigner webhookSigner;
    private final SftpReconHandler reconHandler;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;

    @Autowired
    public JumiaPayConnectorAdapter(
            JumiaPayContractAdapter walletAdapter,
            ConnectorWebhookSigner webhookSigner,
            SftpReconHandler reconHandler
    ) {
        this((ExternalWalletConnector) walletAdapter, webhookSigner, reconHandler);
    }

    /** Wraps an arbitrary wallet connector; used by isolated tests. */
    public JumiaPayConnectorAdapter(
            ExternalWalletConnector walletAdapter,
            ConnectorWebhookSigner webhookSigner,
            SftpReconHandler reconHandler
    ) {
        this.walletAdapter = Objects.requireNonNull(walletAdapter, "walletAdapter cannot be null");
        this.webhookSigner = Objects.requireNonNull(webhookSigner, "webhookSigner cannot be null");
        this.reconHandler = Objects.requireNonNull(reconHandler, "reconHandler cannot be null");
        this.circuitBreaker = new CircuitBreaker(CONNECTOR_ID, 5, 30_000L, null);
        this.rateLimiter = new RateLimiter(CONNECTOR_ID, DEFAULT_RATE_LIMIT_RPM);
    }

    @Override
    public String getConnectorId() {
        return CONNECTOR_ID;
    }

    @Override
    public String getConnectorType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    @Override
    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    @Override
    public String signWebhook(String secret, long timestampSeconds, String payload) {
        return webhookSigner.sign(secret, timestampSeconds, payload);
    }

    @Override
    public boolean verifyWebhookSignature(String secret, long timestampSeconds, String payload, String signature) {
        return webhookSigner.verify(secret, timestampSeconds, payload, signature);
    }

    @Override
    public String generateDailyReconciliationFile(String batchId, LocalDate batchDate, String currency, List<SftpReconRecord> records) {
        return reconHandler.generateReconFile(batchId, batchDate, currency, records);
    }

    @Override
    public SftpReconBatch parseReconciliationFile(String fileContent) {
        return reconHandler.parseReconFile(fileContent);
    }

    // =========================================================================
    // Wallet Delegation Methods
    // =========================================================================

    public ExternalWalletConnector getWalletAdapter() {
        return walletAdapter;
    }

    public PartnerAuthResult authenticatePartner(PartnerAuthRequest request) {
        if (!rateLimiter.tryAcquire()) {
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for JumiaPay connector");
        }
        try {
            PartnerAuthResult res = walletAdapter.authenticatePartner(request);
            circuitBreaker.recordSuccess();
            return res;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }

    public ExternalPaymentAuthResult authorizePayment(ExternalPaymentAuthRequest request) {
        if (!circuitBreaker.allowRequest()) {
            throw com.elepay.common.ApiException.internal("circuit_open", "JumiaPay connector circuit breaker is OPEN");
        }
        if (!rateLimiter.tryAcquire()) {
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for JumiaPay connector");
        }
        try {
            ExternalPaymentAuthResult res = walletAdapter.authorizePayment(request);
            circuitBreaker.recordSuccess();
            return res;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }

    public ExternalPaymentCaptureResult capturePayment(ExternalPaymentCaptureRequest request) {
        if (!circuitBreaker.allowRequest()) {
            throw com.elepay.common.ApiException.internal("circuit_open", "JumiaPay connector circuit breaker is OPEN");
        }
        if (!rateLimiter.tryAcquire()) {
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for JumiaPay connector");
        }
        try {
            ExternalPaymentCaptureResult res = walletAdapter.capturePayment(request);
            circuitBreaker.recordSuccess();
            return res;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }

    public ExternalWalletRefundResult refundToWallet(ExternalWalletRefundRequest request) {
        if (!circuitBreaker.allowRequest()) {
            throw com.elepay.common.ApiException.internal("circuit_open", "JumiaPay connector circuit breaker is OPEN");
        }
        if (!rateLimiter.tryAcquire()) {
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for JumiaPay connector");
        }
        try {
            ExternalWalletRefundResult res = walletAdapter.refundToWallet(request);
            circuitBreaker.recordSuccess();
            return res;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }

    public ExternalWalletWithdrawalResult requestWithdrawal(ExternalWalletWithdrawalRequest request) {
        if (!circuitBreaker.allowRequest()) {
            throw com.elepay.common.ApiException.internal("circuit_open", "JumiaPay connector circuit breaker is OPEN");
        }
        if (!rateLimiter.tryAcquire()) {
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for JumiaPay connector");
        }
        try {
            ExternalWalletWithdrawalResult res = walletAdapter.requestWithdrawal(request);
            circuitBreaker.recordSuccess();
            return res;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }

    public ExternalBalanceQueryResult queryWalletBalance(ExternalBalanceQueryRequest request) {
        return walletAdapter.queryWalletBalance(request);
    }
}
