package com.elepay.enterprise.wallet;

import org.springframework.stereotype.Component;

/**
 * Disabled production adapter for a JumiaPay Ghana enterprise-owned (Mode A) wallet.
 *
 * <p>ELEPAY does not hold an authorized JumiaPay API contract, endpoint list, credential set or
 * signature specification. Until one is supplied and verified, this adapter fails closed: every
 * financial operation raises {@link EnterpriseWalletException.ErrorCode#CONNECTOR_UNAVAILABLE}
 * and no capability is advertised. It never fabricates an authorization, capture, refund,
 * withdrawal, balance or status response.
 *
 * <p>Because callers run inside database transactions, a refusal here rolls back any ledger
 * posting that depended on the external confirmation, so no money is recorded as moved.
 *
 * <p>To enable: obtain the partner's signed API contract, implement the HTTP client against it
 * behind this class, advertise only capabilities verified against that contract, and record the
 * credentials in the partner credential store. See
 * {@code docs/enterprise-external-dependencies.md}.
 */
@Component
public class JumiaPayContractAdapter implements ExternalWalletConnector {

    public static final String CONNECTOR_ID = "jumiapay-gh-v1";
    public static final String PROVIDER_NAME = "JumiaPay Ghana (disabled: no authorized API contract)";

    static final String DISABLED_REASON =
            "JumiaPay connector is disabled: no authorized JumiaPay API contract or credentials are configured";

    @Override
    public String getConnectorId() {
        return CONNECTOR_ID;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public WalletOperatingMode getOperatingMode() {
        return WalletOperatingMode.MODE_A_ENTERPRISE_OWNED;
    }

    /** No capability is advertised until it has been verified against the partner's contract. */
    @Override
    public WalletCapabilities getCapabilities() {
        return WalletCapabilities.none();
    }

    public boolean isEnabled() {
        return false;
    }

    @Override
    public PartnerAuthResult authenticatePartner(PartnerAuthRequest request) {
        throw disabled();
    }

    @Override
    public ExternalPaymentAuthResult authorizePayment(ExternalPaymentAuthRequest request) {
        throw disabled();
    }

    @Override
    public ExternalPaymentCaptureResult capturePayment(ExternalPaymentCaptureRequest request) {
        throw disabled();
    }

    @Override
    public ExternalPaymentCancelResult cancelPayment(ExternalPaymentCancelRequest request) {
        throw disabled();
    }

    @Override
    public ExternalWalletRefundResult refundToWallet(ExternalWalletRefundRequest request) {
        throw disabled();
    }

    @Override
    public ExternalWalletWithdrawalResult requestWithdrawal(ExternalWalletWithdrawalRequest request) {
        throw disabled();
    }

    @Override
    public ExternalBalanceQueryResult queryWalletBalance(ExternalBalanceQueryRequest request) {
        throw disabled();
    }

    @Override
    public ExternalPaymentStatusResult queryPaymentStatus(String reference) {
        throw disabled();
    }

    @Override
    public WebhookSignatureVerificationResult verifyWebhook(WebhookSignatureVerificationRequest request) {
        throw disabled();
    }

    @Override
    public ExternalReconciliationSyncResult syncLedgerReconciliation(ExternalReconciliationSyncRequest request) {
        throw disabled();
    }

    private static EnterpriseWalletException disabled() {
        return new EnterpriseWalletException(
                EnterpriseWalletException.ErrorCode.CONNECTOR_UNAVAILABLE, DISABLED_REASON, 503);
    }
}
