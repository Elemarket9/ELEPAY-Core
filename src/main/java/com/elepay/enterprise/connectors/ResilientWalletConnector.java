package com.elepay.enterprise.connectors;

import com.elepay.enterprise.wallet.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral resilience wrapper around any {@link ExternalWalletConnector}.
 *
 * <p>ELEPAY ships no partner-specific wallet adapter. An enterprise's connector is supplied as an
 * {@link ExternalWalletConnector} implementation built against that enterprise's authorized API
 * contract; this class adds, without knowing who the partner is:
 * <ul>
 *   <li>circuit breaking and token-bucket rate limiting per connector,</li>
 *   <li>HMAC-SHA256 webhook signing and verification,</li>
 *   <li>file-based batch reconciliation.</li>
 * </ul>
 * It is intentionally not a Spring bean: instances are created per configured connector.
 */
public class ResilientWalletConnector implements EnterpriseConnector {

    public static final String CONNECTOR_TYPE = "EXTERNAL_WALLET";
    public static final long DEFAULT_RATE_LIMIT_RPM = 600L; // used when no per-connector limit is configured

    private final ExternalWalletConnector walletAdapter;
    private final ConnectorWebhookSigner webhookSigner;
    private final SftpReconHandler reconHandler;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;

    public ResilientWalletConnector(
            ExternalWalletConnector walletAdapter,
            ConnectorWebhookSigner webhookSigner,
            SftpReconHandler reconHandler,
            long rateLimitPerMinute
    ) {
        this.walletAdapter = Objects.requireNonNull(walletAdapter, "walletAdapter cannot be null");
        this.webhookSigner = Objects.requireNonNull(webhookSigner, "webhookSigner cannot be null");
        this.reconHandler = Objects.requireNonNull(reconHandler, "reconHandler cannot be null");
        this.circuitBreaker = new CircuitBreaker(walletAdapter.getConnectorId(), 5, 30_000L, null);
        this.rateLimiter = new RateLimiter(walletAdapter.getConnectorId(), rateLimitPerMinute > 0 ? rateLimitPerMinute : DEFAULT_RATE_LIMIT_RPM);
    }

    @Override
    public String getConnectorId() {
        return walletAdapter.getConnectorId();
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
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for wallet connector " + getConnectorId());
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
            throw com.elepay.common.ApiException.internal("circuit_open", "Circuit breaker is OPEN for wallet connector " + getConnectorId());
        }
        if (!rateLimiter.tryAcquire()) {
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for wallet connector " + getConnectorId());
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
            throw com.elepay.common.ApiException.internal("circuit_open", "Circuit breaker is OPEN for wallet connector " + getConnectorId());
        }
        if (!rateLimiter.tryAcquire()) {
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for wallet connector " + getConnectorId());
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
            throw com.elepay.common.ApiException.internal("circuit_open", "Circuit breaker is OPEN for wallet connector " + getConnectorId());
        }
        if (!rateLimiter.tryAcquire()) {
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for wallet connector " + getConnectorId());
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
            throw com.elepay.common.ApiException.internal("circuit_open", "Circuit breaker is OPEN for wallet connector " + getConnectorId());
        }
        if (!rateLimiter.tryAcquire()) {
            throw com.elepay.common.ApiException.tooManyRequests("Rate limit exceeded for wallet connector " + getConnectorId());
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
