package com.elepay.enterprise.wallet;

import com.elepay.common.HmacVerification;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete Mode A implementation of {@link ExternalWalletConnector} for JumiaPay Ghana.
 * In this mode, JumiaPay maintains customer wallet balances externally and issues
 * cryptographically signed payment authorizations. ELEPAY orchestrates multi-merchant
 * escrow allocations, item-level refunds, customer withdrawals to MTN MoMo with BoG tier limits,
 * and external ledger reconciliation.
 */
@Component
public class JumiaPayContractAdapter implements ExternalWalletConnector {

    public static final String CONNECTOR_ID = "jumiapay-gh-v1";
    public static final String PROVIDER_NAME = "JumiaPay Ghana";
    public static final String DEFAULT_PARTNER_ID = "partner_jumiapay_gh";

    // Tier limits in minor units (Ghana Pesewas)
    public static final long BOG_TIER_1_LIMIT_PESEWAS = 100_000L;   // GH₵ 1,000.00
    public static final long BOG_TIER_2_LIMIT_PESEWAS = 500_000L;   // GH₵ 5,000.00
    public static final long BOG_TIER_3_LIMIT_PESEWAS = 1_000_000L; // GH₵ 10,000.00

    private final EnterpriseTokenService tokenService;
    private final HmacWebhookVerifier webhookVerifier;

    // Simulated external customer balances managed by JumiaPay: customerId -> availablePesewas
    private final Map<String, Long> externalCustomerBalances = new ConcurrentHashMap<>();

    // Idempotency cache: key -> CachedPayment
    private final Map<String, StoredPaymentState> idempotencyStore = new ConcurrentHashMap<>();

    // Authorizations store: authId -> StoredPaymentState
    private final Map<String, StoredPaymentState> authorizations = new ConcurrentHashMap<>();

    // Withdrawals store: withdrawalId -> ExternalWalletWithdrawalResult
    private final Map<String, ExternalWalletWithdrawalResult> withdrawals = new ConcurrentHashMap<>();

    private record StoredPaymentState(
            String authorizationId,
            String captureId,
            String idempotencyKey,
            String transactionReference,
            String externalCustomerId,
            long authorizedAmount,
            long capturedAmount,
            long refundedAmount,
            String currency,
            PaymentExecutionState state,
            List<PaymentAllocationSplit> allocations,
            List<RefundAllocationItem> refundItems,
            Instant createdAt
    ) {}

    public JumiaPayContractAdapter(
            EnterpriseTokenService tokenService,
            HmacWebhookVerifier webhookVerifier
    ) {
        this.tokenService = tokenService;
        this.webhookVerifier = webhookVerifier;
    }

    /**
     * Seeds or updates an external customer wallet balance in JumiaPay.
     */
    public void seedCustomerBalance(String externalCustomerId, long balanceMinorUnits) {
        externalCustomerBalances.put(externalCustomerId, balanceMinorUnits);
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
        return WalletOperatingMode.MODE_A_ENTERPRISE_OWNED;
    }

    @Override
    public WalletCapabilities getCapabilities() {
        return WalletCapabilities.modeADefaults();
    }

    @Override
    public PartnerAuthResult authenticatePartner(PartnerAuthRequest request) {
        return tokenService.authenticateClientCredentials(request);
    }

    @Override
    public ExternalPaymentAuthResult authorizePayment(ExternalPaymentAuthRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        // Idempotency check
        StoredPaymentState existing = idempotencyStore.get(request.idempotencyKey());
        if (existing != null) {
            if (!existing.transactionReference().equals(request.transactionReference())
                    || existing.authorizedAmount() != request.amountMinorUnits()
                    || !existing.currency().equalsIgnoreCase(request.currency())) {
                throw new EnterpriseWalletException(
                        EnterpriseWalletException.ErrorCode.IDEMPOTENCY_CONFLICT,
                        "Payload mismatch for idempotency key: " + request.idempotencyKey(),
                        409
                );
            }
            return ExternalPaymentAuthResult.success(
                    existing.authorizationId(),
                    existing.transactionReference(),
                    existing.authorizedAmount(),
                    existing.currency(),
                    "CACHED_IDEMPOTENT_RESPONSE"
            );
        }

        // Validate partner credentials
        PartnerAuthCredentials creds = tokenService.getPartnerCredentials(request.partnerId())
                .orElseThrow(() -> new EnterpriseWalletException(
                        EnterpriseWalletException.ErrorCode.INVALID_CREDENTIALS,
                        "Unknown partner: " + request.partnerId(),
                        401
                ));

        if (!creds.hasScope(PartnerScope.WALLET_AUTHORIZE)) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INSUFFICIENT_SCOPE,
                    "Partner lacks WALLET_AUTHORIZE scope",
                    403
            );
        }

        // Cryptographic signature verification of JumiaPay authorization
        String expectedCanonical = request.externalCustomerId() + ":" + request.amountMinorUnits()
                + ":" + request.currency().toUpperCase() + ":" + request.transactionReference();

        boolean sigValid = constantTimeVerify(creds.activeSigningKey(), expectedCanonical, request.cryptographicAuthorization());
        if (!sigValid && creds.previousSigningKey() != null) {
            sigValid = constantTimeVerify(creds.previousSigningKey(), expectedCanonical, request.cryptographicAuthorization());
        }

        if (!sigValid) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_SIGNATURE,
                    "Cryptographic payment authorization failed for reference: " + request.transactionReference(),
                    401
            );
        }

        // Verify customer has sufficient balance in JumiaPay external wallet
        long currentBalance = externalCustomerBalances.getOrDefault(request.externalCustomerId(), 0L);
        if (currentBalance < request.amountMinorUnits()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INSUFFICIENT_FUNDS,
                    "Insufficient external balance in JumiaPay: available=" + currentBalance + ", required=" + request.amountMinorUnits(),
                    400
            );
        }

        // Hold balance externally
        externalCustomerBalances.put(request.externalCustomerId(), currentBalance - request.amountMinorUnits());

        String authId = "jpa_" + UUID.randomUUID().toString().replace("-", "");
        StoredPaymentState payment = new StoredPaymentState(
                authId,
                null,
                request.idempotencyKey(),
                request.transactionReference(),
                request.externalCustomerId(),
                request.amountMinorUnits(),
                0L,
                0L,
                request.currency(),
                PaymentExecutionState.AUTHORIZED,
                List.of(),
                List.of(),
                Instant.now()
        );

        authorizations.put(authId, payment);
        idempotencyStore.put(request.idempotencyKey(), payment);

        return ExternalPaymentAuthResult.success(
                authId,
                request.transactionReference(),
                request.amountMinorUnits(),
                request.currency(),
                "JUMIAPAY_CRYPTOGRAPHIC_AUTH_OK"
        );
    }

    @Override
    public ExternalPaymentCaptureResult capturePayment(ExternalPaymentCaptureRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        StoredPaymentState auth = authorizations.get(request.authorizationId());
        if (auth == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.TRANSACTION_NOT_FOUND,
                    "Authorization not found: " + request.authorizationId(),
                    404
            );
        }

        if (auth.state() != PaymentExecutionState.AUTHORIZED) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_TRANSACTION_STATE,
                    "Cannot capture payment in state: " + auth.state(),
                    400
            );
        }

        if (request.captureAmountMinorUnits() > auth.authorizedAmount()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.LIMIT_EXCEEDED,
                    "Capture amount (" + request.captureAmountMinorUnits() + ") exceeds authorized amount (" + auth.authorizedAmount() + ")",
                    400
            );
        }

        String captureId = "jpc_" + UUID.randomUUID().toString().replace("-", "");
        StoredPaymentState updated = new StoredPaymentState(
                auth.authorizationId(),
                captureId,
                auth.idempotencyKey(),
                auth.transactionReference(),
                auth.externalCustomerId(),
                auth.authorizedAmount(),
                request.captureAmountMinorUnits(),
                0L,
                auth.currency(),
                PaymentExecutionState.CAPTURED,
                request.allocations(),
                List.of(),
                auth.createdAt()
        );

        authorizations.put(auth.authorizationId(), updated);
        authorizations.put(captureId, updated);

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

        StoredPaymentState auth = authorizations.get(request.authorizationId());
        if (auth == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.TRANSACTION_NOT_FOUND,
                    "Authorization not found: " + request.authorizationId(),
                    404
            );
        }

        if (auth.state() != PaymentExecutionState.AUTHORIZED) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_TRANSACTION_STATE,
                    "Cannot cancel payment in state: " + auth.state(),
                    400
            );
        }

        // Release held balance back to external JumiaPay customer
        long currentBalance = externalCustomerBalances.getOrDefault(auth.externalCustomerId(), 0L);
        externalCustomerBalances.put(auth.externalCustomerId(), currentBalance + auth.authorizedAmount());

        StoredPaymentState cancelled = new StoredPaymentState(
                auth.authorizationId(),
                null,
                auth.idempotencyKey(),
                auth.transactionReference(),
                auth.externalCustomerId(),
                auth.authorizedAmount(),
                0L,
                0L,
                auth.currency(),
                PaymentExecutionState.CANCELLED,
                List.of(),
                List.of(),
                auth.createdAt()
        );
        authorizations.put(auth.authorizationId(), cancelled);

        return ExternalPaymentCancelResult.success(auth.authorizationId(), request.reason());
    }

    @Override
    public ExternalWalletRefundResult refundToWallet(ExternalWalletRefundRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        StoredPaymentState payment = authorizations.get(request.originalCaptureId());
        if (payment == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.TRANSACTION_NOT_FOUND,
                    "Capture transaction not found: " + request.originalCaptureId(),
                    404
            );
        }

        if (payment.state() != PaymentExecutionState.CAPTURED && payment.state() != PaymentExecutionState.PARTIALLY_REFUNDED) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INVALID_TRANSACTION_STATE,
                    "Cannot refund payment in state: " + payment.state(),
                    400
            );
        }

        long totalAfterRefund = payment.refundedAmount() + request.refundAmountMinorUnits();
        if (totalAfterRefund > payment.capturedAmount()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.LIMIT_EXCEEDED,
                    "Cumulative refund (" + totalAfterRefund + ") exceeds captured amount (" + payment.capturedAmount() + ")",
                    400
            );
        }

        // Credit customer's JumiaPay wallet balance
        long currentBalance = externalCustomerBalances.getOrDefault(request.externalCustomerId(), 0L);
        externalCustomerBalances.put(request.externalCustomerId(), currentBalance + request.refundAmountMinorUnits());

        PaymentExecutionState newState = (totalAfterRefund == payment.capturedAmount())
                ? PaymentExecutionState.REFUNDED
                : PaymentExecutionState.PARTIALLY_REFUNDED;

        List<RefundAllocationItem> updatedItems = new ArrayList<>(payment.refundItems());
        if (request.items() != null) {
            updatedItems.addAll(request.items());
        }

        StoredPaymentState updated = new StoredPaymentState(
                payment.authorizationId(),
                payment.captureId(),
                payment.idempotencyKey(),
                payment.transactionReference(),
                payment.externalCustomerId(),
                payment.authorizedAmount(),
                payment.capturedAmount(),
                totalAfterRefund,
                payment.currency(),
                newState,
                payment.allocations(),
                Collections.unmodifiableList(updatedItems),
                payment.createdAt()
        );

        authorizations.put(payment.authorizationId(), updated);
        if (payment.captureId() != null) {
            authorizations.put(payment.captureId(), updated);
        }

        String refundId = "jpr_" + UUID.randomUUID().toString().replace("-", "");
        return ExternalWalletRefundResult.success(
                refundId,
                request.originalCaptureId(),
                newState,
                request.refundAmountMinorUnits(),
                request.currency(),
                request.items()
        );
    }

    @Override
    public ExternalWalletWithdrawalResult requestWithdrawal(ExternalWalletWithdrawalRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        // Validate BoG KYC Tier Limits
        long limit;
        switch (request.kycTier().toUpperCase()) {
            case "TIER_3" -> limit = BOG_TIER_3_LIMIT_PESEWAS;
            case "TIER_2" -> limit = BOG_TIER_2_LIMIT_PESEWAS;
            case "TIER_1", "TIER_0" -> limit = BOG_TIER_1_LIMIT_PESEWAS;
            default -> limit = BOG_TIER_1_LIMIT_PESEWAS;
        }

        if (request.amountMinorUnits() > limit) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.LIMIT_EXCEEDED,
                    "Withdrawal amount (" + request.amountMinorUnits() + ") exceeds Bank of Ghana "
                            + request.kycTier() + " limit (" + limit + ")",
                    403
            );
        }

        // Validate customer has sufficient balance in JumiaPay wallet
        long available = externalCustomerBalances.getOrDefault(request.externalCustomerId(), 0L);
        if (available < request.amountMinorUnits()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INSUFFICIENT_FUNDS,
                    "Insufficient customer balance for withdrawal: available=" + available + ", requested=" + request.amountMinorUnits(),
                    400
            );
        }

        // Debit customer wallet balance in JumiaPay
        externalCustomerBalances.put(request.externalCustomerId(), available - request.amountMinorUnits());

        String withdrawalId = "jpw_" + UUID.randomUUID().toString().replace("-", "");
        String txRef = "JPMO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        ExternalWalletWithdrawalResult result = ExternalWalletWithdrawalResult.success(
                withdrawalId,
                txRef,
                request.amountMinorUnits(),
                request.currency(),
                request.destinationRail(),
                request.destinationPhoneOrAccount()
        );

        withdrawals.put(withdrawalId, result);
        return result;
    }

    @Override
    public ExternalBalanceQueryResult queryWalletBalance(ExternalBalanceQueryRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        long balance = externalCustomerBalances.getOrDefault(request.externalCustomerId(), 0L);
        return ExternalBalanceQueryResult.success(
                request.partnerId(),
                request.externalCustomerId(),
                balance,
                0L,
                request.currency()
        );
    }

    @Override
    public ExternalPaymentStatusResult queryPaymentStatus(String reference) {
        if (reference == null || reference.isBlank()) {
            return ExternalPaymentStatusResult.notFound(reference);
        }

        StoredPaymentState payment = authorizations.get(reference);
        if (payment == null) {
            // Check by transaction reference
            for (StoredPaymentState state : authorizations.values()) {
                if (reference.equals(state.transactionReference())) {
                    payment = state;
                    break;
                }
            }
        }

        if (payment == null) {
            return ExternalPaymentStatusResult.notFound(reference);
        }

        long amount = payment.state() == PaymentExecutionState.CAPTURED || payment.state() == PaymentExecutionState.PARTIALLY_REFUNDED
                ? payment.capturedAmount() : payment.authorizedAmount();

        return ExternalPaymentStatusResult.of(
                payment.transactionReference(),
                payment.state(),
                amount,
                payment.currency(),
                payment.createdAt(),
                "Status: " + payment.state()
        );
    }

    @Override
    public WebhookSignatureVerificationResult verifyWebhook(WebhookSignatureVerificationRequest request) {
        PartnerAuthCredentials creds = tokenService.getPartnerCredentials(request.partnerId())
                .orElse(null);
        return webhookVerifier.verify(request, creds);
    }

    @Override
    public ExternalReconciliationSyncResult syncLedgerReconciliation(ExternalReconciliationSyncRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        int total = request.records().size();
        int matched = 0;
        int discrepancies = 0;
        long matchedAmount = 0L;
        long discrepancyAmount = 0L;
        List<String> discrepancyList = new ArrayList<>();

        for (ExternalLedgerRecord rec : request.records()) {
            StoredPaymentState internal = authorizations.get(rec.internalReference());
            if (internal == null) {
                discrepancies++;
                discrepancyAmount += rec.amountMinorUnits();
                discrepancyList.add("Missing internal record for external ref: " + rec.externalReference()
                        + " internal ref: " + rec.internalReference());
                continue;
            }

            long expectedAmount = internal.state() == PaymentExecutionState.CAPTURED
                    || internal.state() == PaymentExecutionState.PARTIALLY_REFUNDED
                    ? internal.capturedAmount() : internal.authorizedAmount();

            if (expectedAmount != rec.amountMinorUnits() || !internal.currency().equalsIgnoreCase(rec.currency())) {
                discrepancies++;
                discrepancyAmount += Math.abs(expectedAmount - rec.amountMinorUnits());
                discrepancyList.add("Amount/currency mismatch for ref " + rec.externalReference()
                        + ": external=" + rec.amountMinorUnits() + " " + rec.currency()
                        + ", internal=" + expectedAmount + " " + internal.currency());
            } else {
                matched++;
                matchedAmount += expectedAmount;
            }
        }

        String batchId = "recon_jp_" + UUID.randomUUID().toString().replace("-", "");
        return new ExternalReconciliationSyncResult(
                batchId,
                total,
                matched,
                discrepancies,
                matchedAmount,
                discrepancyAmount,
                Collections.unmodifiableList(discrepancyList),
                Instant.now()
        );
    }

    private boolean constantTimeVerify(String secret, String canonical, String providedHex) {
        if (secret == null || canonical == null || providedHex == null) return false;
        String expected = HmacVerification.computeHex(secret, canonical);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }
}
