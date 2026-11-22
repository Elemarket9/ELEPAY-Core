package com.elepay.enterprise.wallet;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal External Wallet Integration Engine coordinating multi-mode connectors,
 * token lifecycle management, partner authentication, and external financial routing.
 */
@Service
public class ExternalWalletEngine {

    private final Map<String, ExternalWalletConnector> connectorRegistry = new ConcurrentHashMap<>();
    private final EnterpriseTokenService tokenService;
    private final HmacWebhookVerifier webhookVerifier;

    public ExternalWalletEngine(
            List<ExternalWalletConnector> connectors,
            EnterpriseTokenService tokenService,
            HmacWebhookVerifier webhookVerifier
    ) {
        this.tokenService = tokenService;
        this.webhookVerifier = webhookVerifier;
        if (connectors != null) {
            for (ExternalWalletConnector connector : connectors) {
                registerConnector(connector);
            }
        }
    }

    public void registerConnector(ExternalWalletConnector connector) {
        Objects.requireNonNull(connector, "connector cannot be null");
        connectorRegistry.put(connector.getConnectorId(), connector);
    }

    public Optional<ExternalWalletConnector> getConnector(String connectorId) {
        if (connectorId == null) return Optional.empty();
        return Optional.ofNullable(connectorRegistry.get(connectorId));
    }

    public List<ExternalWalletConnector> getConnectorsByMode(WalletOperatingMode mode) {
        List<ExternalWalletConnector> result = new ArrayList<>();
        for (ExternalWalletConnector connector : connectorRegistry.values()) {
            if (connector.getOperatingMode() == mode) {
                result.add(connector);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public Collection<ExternalWalletConnector> getAllConnectors() {
        return Collections.unmodifiableCollection(connectorRegistry.values());
    }

    public EnterpriseTokenService getTokenService() {
        return tokenService;
    }

    public HmacWebhookVerifier getWebhookVerifier() {
        return webhookVerifier;
    }

    public ExternalPaymentAuthResult authorize(String connectorId, ExternalPaymentAuthRequest request) {
        ExternalWalletConnector connector = resolveConnector(connectorId);
        return connector.authorizePayment(request);
    }

    public ExternalPaymentCaptureResult capture(String connectorId, ExternalPaymentCaptureRequest request) {
        ExternalWalletConnector connector = resolveConnector(connectorId);
        return connector.capturePayment(request);
    }

    public ExternalPaymentCancelResult cancel(String connectorId, ExternalPaymentCancelRequest request) {
        ExternalWalletConnector connector = resolveConnector(connectorId);
        return connector.cancelPayment(request);
    }

    public ExternalWalletRefundResult refund(String connectorId, ExternalWalletRefundRequest request) {
        ExternalWalletConnector connector = resolveConnector(connectorId);
        return connector.refundToWallet(request);
    }

    public ExternalWalletWithdrawalResult withdraw(String connectorId, ExternalWalletWithdrawalRequest request) {
        ExternalWalletConnector connector = resolveConnector(connectorId);
        return connector.requestWithdrawal(request);
    }

    public ExternalBalanceQueryResult queryBalance(String connectorId, ExternalBalanceQueryRequest request) {
        ExternalWalletConnector connector = resolveConnector(connectorId);
        return connector.queryWalletBalance(request);
    }

    public ExternalPaymentStatusResult queryStatus(String connectorId, String reference) {
        ExternalWalletConnector connector = resolveConnector(connectorId);
        return connector.queryPaymentStatus(reference);
    }

    public WebhookSignatureVerificationResult verifyWebhook(String connectorId, WebhookSignatureVerificationRequest request) {
        ExternalWalletConnector connector = resolveConnector(connectorId);
        return connector.verifyWebhook(request);
    }

    public ExternalReconciliationSyncResult syncReconciliation(String connectorId, ExternalReconciliationSyncRequest request) {
        ExternalWalletConnector connector = resolveConnector(connectorId);
        return connector.syncLedgerReconciliation(request);
    }

    private ExternalWalletConnector resolveConnector(String connectorId) {
        ExternalWalletConnector connector = connectorRegistry.get(connectorId);
        if (connector == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.CONNECTOR_UNAVAILABLE,
                    "No connector registered with ID: " + connectorId,
                    404
            );
        }
        return connector;
    }
}
