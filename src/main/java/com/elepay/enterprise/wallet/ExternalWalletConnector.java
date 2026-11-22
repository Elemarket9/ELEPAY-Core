package com.elepay.enterprise.wallet;

/**
 * Universal interface contract for enterprise external wallet connectors.
 * Connectors bridge ELEPAY with enterprise-owned wallets (Mode A), white-label wallets (Mode B),
 * and external settlement-only systems (Mode C).
 */
public interface ExternalWalletConnector {

    /**
     * Unique identifier for this connector instance (e.g. "jumiapay-gh-v1").
     */
    String getConnectorId();

    /**
     * Human-readable enterprise provider name (e.g. "JumiaPay Ghana").
     */
    String getProviderName();

    /**
     * The primary operating mode of this connector.
     */
    WalletOperatingMode getOperatingMode();

    /**
     * Returns the feature capabilities supported by this connector.
     */
    WalletCapabilities getCapabilities();

    /**
     * Authenticates partner credentials via OAuth2 client credentials.
     */
    PartnerAuthResult authenticatePartner(PartnerAuthRequest request);

    /**
     * Authorizes a customer payment against the wallet.
     * In Mode A, validates cryptographic authorization issued by external system.
     * In Mode B, creates balance reservation on ELEPAY ledger.
     * In Mode C, records authorization intent for externally collected funds.
     */
    ExternalPaymentAuthResult authorizePayment(ExternalPaymentAuthRequest request);

    /**
     * Captures an authorized payment and allocates funds across marketplace merchants.
     */
    ExternalPaymentCaptureResult capturePayment(ExternalPaymentCaptureRequest request);

    /**
     * Cancels an existing authorization before capture.
     */
    ExternalPaymentCancelResult cancelPayment(ExternalPaymentCancelRequest request);

    /**
     * Refunds funds back to the customer's wallet.
     * In Mode A, credits the customer balance in the external platform.
     * In Mode B, credits the customer subaccount on ELEPAY ledger.
     * In Mode C, processes merchant settlement clawback/reversal.
     */
    ExternalWalletRefundResult refundToWallet(ExternalWalletRefundRequest request);

    /**
     * Initiates a withdrawal from customer's wallet balance to domestic payment rails (e.g. MTN MoMo).
     */
    ExternalWalletWithdrawalResult requestWithdrawal(ExternalWalletWithdrawalRequest request);

    /**
     * Queries current available and reserved balance for a customer.
     */
    ExternalBalanceQueryResult queryWalletBalance(ExternalBalanceQueryRequest request);

    /**
     * Queries current status of an external payment by reference.
     */
    ExternalPaymentStatusResult queryPaymentStatus(String reference);

    /**
     * Verifies cryptographic HMAC signature on partner webhook callbacks.
     */
    WebhookSignatureVerificationResult verifyWebhook(WebhookSignatureVerificationRequest request);

    /**
     * Reconciles external ledger records against ELEPAY settlement state.
     */
    ExternalReconciliationSyncResult syncLedgerReconciliation(ExternalReconciliationSyncRequest request);
}
