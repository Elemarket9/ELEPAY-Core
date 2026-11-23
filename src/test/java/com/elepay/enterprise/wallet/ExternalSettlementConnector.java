package com.elepay.enterprise.wallet;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete Mode C implementation of {@link ExternalWalletConnector}.
 * In Mode C, the enterprise collects payments externally through its own payment infrastructure
 * and uses ELEPAY exclusively for escrow allocation, intelligent multi-merchant splitting,
 * fee deductions, and partner payouts.
 */
@Component
public class ExternalSettlementConnector implements ExternalWalletConnector {

    public static final String CONNECTOR_ID = "elepay-settlement-only-v1";
    public static final String PROVIDER_NAME = "ELEPAY External Settlement-Only Connector";

    private final EnterpriseTokenService tokenService;
    private final HmacWebhookVerifier webhookVerifier;

    // Escrow ledger of externally collected funds: settlementBatchId -> StoredSettlementPayment
    private final Map<String, StoredSettlementPayment> settlements = new ConcurrentHashMap<>();
    private final Map<String, StoredSettlementPayment> idempotencyStore = new ConcurrentHashMap<>();

    // Merchant payout balances: merchantId -> accruedAvailableMinorUnits
    private final Map<String, Long> merchantSettlementBalances = new ConcurrentHashMap<>();

    private record StoredSettlementPayment(
            String authorizationId,
            String captureId,
            String idempotencyKey,
            String transactionReference,
            long amountMinorUnits,
            String currency,
            PaymentExecutionState state,
            List<PaymentAllocationSplit> allocations,
            Instant createdAt
    ) {}

    public ExternalSettlementConnector(
            EnterpriseTokenService tokenService,
            HmacWebhookVerifier webhookVerifier
    ) {
        this.tokenService = tokenService;
        this.webhookVerifier = webhookVerifier;
    }

    public long getMerchantAccruedBalance(String merchantId) {
        return merchantSettlementBalances.getOrDefault(merchantId, 0L);
    }

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
        return WalletOperatingMode.MODE_C_SETTLEMENT_ONLY;
    }

    @Override
    public WalletCapabilities getCapabilities() {
        return WalletCapabilities.modeCDefaults();
    }

    @Override
    public PartnerAuthResult authenticatePartner(PartnerAuthRequest request) {
        return tokenService.authenticateClientCredentials(request);
    }

    @Override
    public ExternalPaymentAuthResult authorizePayment(ExternalPaymentAuthRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        StoredSettlementPayment existing = idempotencyStore.get(request.idempotencyKey());
        if (existing != null) {
            return ExternalPaymentAuthResult.success(
                    existing.authorizationId(),
                    existing.transactionReference(),
                    existing.amountMinorUnits(),
                    existing.currency(),
                    "CACHED_SETTLEMENT_INTENT"
            );
        }

        PartnerAuthCredentials creds = tokenService.getPartnerCredentials(request.partnerId())
                .orElseThrow(() -> new EnterpriseWalletException(
                        EnterpriseWalletException.ErrorCode.INVALID_CREDENTIALS,
                        "Unknown partner: " + request.partnerId(),
                        401
                ));

        if (!creds.hasScope(PartnerScope.SETTLEMENT_SUBMIT)) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INSUFFICIENT_SCOPE,
                    "Partner lacks SETTLEMENT_SUBMIT scope",
                    403
            );
        }

        String authId = "eso_" + UUID.randomUUID().toString().replace("-", "");
        StoredSettlementPayment payment = new StoredSettlementPayment(
                authId,
                null,
                request.idempotencyKey(),
                request.transactionReference(),
                request.amountMinorUnits(),
                request.currency(),
                PaymentExecutionState.AUTHORIZED,
                List.of(),
                Instant.now()
        );

        settlements.put(authId, payment);
        idempotencyStore.put(request.idempotencyKey(), payment);

        return ExternalPaymentAuthResult.success(
                authId,
                request.transactionReference(),
                request.amountMinorUnits(),
                request.currency(),
                "EXTERNAL_COLLECTION_INTENT_RECORDED"
        );
    }

    @Override
    public ExternalPaymentCaptureResult capturePayment(ExternalPaymentCaptureRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        StoredSettlementPayment auth = settlements.get(request.authorizationId());
        if (auth == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.TRANSACTION_NOT_FOUND,
                    "Settlement authorization not found: " + request.authorizationId(),
                    404
            );
        }

        // Allocate funds to merchant settlement balances
        for (PaymentAllocationSplit split : request.allocations()) {
            long net = split.netPayableToMerchant();
            merchantSettlementBalances.merge(split.merchantId(), net, Long::sum);
        }

        String captureId = "esc_" + UUID.randomUUID().toString().replace("-", "");
        StoredSettlementPayment captured = new StoredSettlementPayment(
                auth.authorizationId(),
                captureId,
                auth.idempotencyKey(),
                auth.transactionReference(),
                request.captureAmountMinorUnits(),
                request.currency(),
                PaymentExecutionState.CAPTURED,
                request.allocations(),
                auth.createdAt()
        );

        settlements.put(auth.authorizationId(), captured);
        settlements.put(captureId, captured);

        return ExternalPaymentCaptureResult.success(
                captureId,
                auth.authorizationId(),
                request.captureAmountMinorUnits(),
                auth.currency(),
                request.allocations()
        );
    }

    @Override
    public ExternalPaymentCancelResult cancelPayment(ExternalPaymentCancelRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        StoredSettlementPayment auth = settlements.get(request.authorizationId());
        if (auth == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.TRANSACTION_NOT_FOUND,
                    "Settlement authorization not found: " + request.authorizationId(),
                    404
            );
        }

        StoredSettlementPayment cancelled = new StoredSettlementPayment(
                auth.authorizationId(),
                null,
                auth.idempotencyKey(),
                auth.transactionReference(),
                auth.amountMinorUnits(),
                auth.currency(),
                PaymentExecutionState.CANCELLED,
                List.of(),
                auth.createdAt()
        );
        settlements.put(auth.authorizationId(), cancelled);

        return ExternalPaymentCancelResult.success(auth.authorizationId(), request.reason());
    }

    @Override
    public ExternalWalletRefundResult refundToWallet(ExternalWalletRefundRequest request) {
        // Mode C does not refund to a customer wallet; it clobbers/reverses merchant settlement allocations
        StoredSettlementPayment payment = settlements.get(request.originalCaptureId());
        if (payment == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.TRANSACTION_NOT_FOUND,
                    "Capture not found: " + request.originalCaptureId(),
                    404
            );
        }

        // Reversal of merchant balances
        if (request.items() != null) {
            for (RefundAllocationItem item : request.items()) {
                long clawback = item.refundAmountMinorUnits() - item.commissionReversalMinorUnits();
                merchantSettlementBalances.computeIfPresent(item.merchantId(), (mId, bal) -> bal - clawback);
            }
        }

        String refundId = "esr_" + UUID.randomUUID().toString().replace("-", "");
        return ExternalWalletRefundResult.success(
                refundId,
                request.originalCaptureId(),
                PaymentExecutionState.REFUNDED,
                request.refundAmountMinorUnits(),
                request.currency(),
                request.items()
        );
    }

    @Override
    public ExternalWalletWithdrawalResult requestWithdrawal(ExternalWalletWithdrawalRequest request) {
        throw new EnterpriseWalletException(
                EnterpriseWalletException.ErrorCode.UNSUPPORTED_OPERATION,
                "Mode C Settlement-Only connector does not support customer wallet withdrawals",
                400
        );
    }

    @Override
    public ExternalBalanceQueryResult queryWalletBalance(ExternalBalanceQueryRequest request) {
        throw new EnterpriseWalletException(
                EnterpriseWalletException.ErrorCode.UNSUPPORTED_OPERATION,
                "Mode C Settlement-Only connector does not maintain customer wallet balances",
                400
        );
    }

    @Override
    public ExternalPaymentStatusResult queryPaymentStatus(String reference) {
        StoredSettlementPayment payment = settlements.get(reference);
        if (payment == null) {
            return ExternalPaymentStatusResult.notFound(reference);
        }
        return ExternalPaymentStatusResult.of(
                payment.transactionReference(),
                payment.state(),
                payment.amountMinorUnits(),
                payment.currency(),
                payment.createdAt(),
                "Settlement state: " + payment.state()
        );
    }

    @Override
    public WebhookSignatureVerificationResult verifyWebhook(WebhookSignatureVerificationRequest request) {
        PartnerAuthCredentials creds = tokenService.getPartnerCredentials(request.partnerId()).orElse(null);
        return webhookVerifier.verify(request, creds);
    }

    @Override
    public ExternalReconciliationSyncResult syncLedgerReconciliation(ExternalReconciliationSyncRequest request) {
        String batchId = "recon_eso_" + UUID.randomUUID().toString().replace("-", "");
        return new ExternalReconciliationSyncResult(
                batchId,
                request.records().size(),
                request.records().size(),
                0,
                request.records().stream().mapToLong(ExternalLedgerRecord::amountMinorUnits).sum(),
                0L,
                List.of(),
                Instant.now()
        );
    }
}
