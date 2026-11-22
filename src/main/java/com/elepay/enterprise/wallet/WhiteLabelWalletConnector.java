package com.elepay.enterprise.wallet;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete Mode B implementation of {@link ExternalWalletConnector}.
 * In Mode B, ELEPAY operates as the authoritative ledger for white-label enterprise wallets,
 * managing customer subaccounts, balance reservations, captures, refunds, and withdrawals.
 */
@Component
public class WhiteLabelWalletConnector implements ExternalWalletConnector {

    public static final String CONNECTOR_ID = "elepay-whitelabel-v1";
    public static final String PROVIDER_NAME = "ELEPAY White-Label Wallet";

    private final EnterpriseTokenService tokenService;
    private final HmacWebhookVerifier webhookVerifier;

    // Internal subaccount balances: customerId -> CustomerSubaccountBalance
    private final Map<String, CustomerSubaccountBalance> customerBalances = new ConcurrentHashMap<>();

    // Idempotency cache: key -> StoredWhiteLabelPayment
    private final Map<String, StoredWhiteLabelPayment> idempotencyStore = new ConcurrentHashMap<>();

    // Authorizations & captures: id -> StoredWhiteLabelPayment
    private final Map<String, StoredWhiteLabelPayment> payments = new ConcurrentHashMap<>();

    public record CustomerSubaccountBalance(
            String customerId,
            long availableMinorUnits,
            long reservedMinorUnits,
            String currency
    ) {
        public CustomerSubaccountBalance withAvailable(long newAvailable) {
            return new CustomerSubaccountBalance(customerId, newAvailable, reservedMinorUnits, currency);
        }

        public CustomerSubaccountBalance withReservation(long delta) {
            return new CustomerSubaccountBalance(customerId, availableMinorUnits - delta, reservedMinorUnits + delta, currency);
        }

        public CustomerSubaccountBalance releaseReservation(long delta) {
            return new CustomerSubaccountBalance(customerId, availableMinorUnits + delta, reservedMinorUnits - delta, currency);
        }

        public CustomerSubaccountBalance finalizeDeduction(long delta) {
            return new CustomerSubaccountBalance(customerId, availableMinorUnits, reservedMinorUnits - delta, currency);
        }

        public CustomerSubaccountBalance credit(long delta) {
            return new CustomerSubaccountBalance(customerId, availableMinorUnits + delta, reservedMinorUnits, currency);
        }
    }

    private record StoredWhiteLabelPayment(
            String authorizationId,
            String captureId,
            String idempotencyKey,
            String transactionReference,
            String customerId,
            long authorizedAmount,
            long capturedAmount,
            long refundedAmount,
            String currency,
            PaymentExecutionState state,
            List<PaymentAllocationSplit> allocations,
            List<RefundAllocationItem> refundItems,
            Instant createdAt
    ) {}

    public WhiteLabelWalletConnector(
            EnterpriseTokenService tokenService,
            HmacWebhookVerifier webhookVerifier
    ) {
        this.tokenService = tokenService;
        this.webhookVerifier = webhookVerifier;
    }

    /**
     * Credits an internal customer subaccount balance.
     */
    public void creditCustomerWallet(String customerId, long amountMinorUnits, String currency) {
        customerBalances.compute(customerId, (id, current) -> {
            if (current == null) {
                return new CustomerSubaccountBalance(customerId, amountMinorUnits, 0L, currency);
            }
            return current.credit(amountMinorUnits);
        });
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
        return WalletOperatingMode.MODE_B_WHITE_LABEL;
    }

    @Override
    public WalletCapabilities getCapabilities() {
        return WalletCapabilities.modeBDefaults();
    }

    @Override
    public PartnerAuthResult authenticatePartner(PartnerAuthRequest request) {
        return tokenService.authenticateClientCredentials(request);
    }

    @Override
    public ExternalPaymentAuthResult authorizePayment(ExternalPaymentAuthRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        StoredWhiteLabelPayment existing = idempotencyStore.get(request.idempotencyKey());
        if (existing != null) {
            if (!existing.transactionReference().equals(request.transactionReference())
                    || existing.authorizedAmount() != request.amountMinorUnits()) {
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

        // Validate partner authorization
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

        // Check internal ledger balance and reserve funds
        CustomerSubaccountBalance balance = customerBalances.get(request.externalCustomerId());
        if (balance == null || balance.availableMinorUnits() < request.amountMinorUnits()) {
            long available = (balance != null) ? balance.availableMinorUnits() : 0L;
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INSUFFICIENT_FUNDS,
                    "Insufficient available balance: " + available + ", required: " + request.amountMinorUnits(),
                    400
            );
        }

        // Reserve funds in internal ledger
        customerBalances.put(request.externalCustomerId(), balance.withReservation(request.amountMinorUnits()));

        String authId = "wla_" + UUID.randomUUID().toString().replace("-", "");
        StoredWhiteLabelPayment payment = new StoredWhiteLabelPayment(
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

        payments.put(authId, payment);
        idempotencyStore.put(request.idempotencyKey(), payment);

        return ExternalPaymentAuthResult.success(
                authId,
                request.transactionReference(),
                request.amountMinorUnits(),
                request.currency(),
                "WHITE_LABEL_RESERVATION_AUTHORIZED"
        );
    }

    @Override
    public ExternalPaymentCaptureResult capturePayment(ExternalPaymentCaptureRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        StoredWhiteLabelPayment auth = payments.get(request.authorizationId());
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
                    "Cannot capture in state: " + auth.state(),
                    400
            );
        }

        // Finalize deduction from reserved balance in internal ledger
        CustomerSubaccountBalance balance = customerBalances.get(auth.customerId());
        if (balance != null) {
            customerBalances.put(auth.customerId(), balance.finalizeDeduction(request.captureAmountMinorUnits()));
        }

        String captureId = "wlc_" + UUID.randomUUID().toString().replace("-", "");
        StoredWhiteLabelPayment updated = new StoredWhiteLabelPayment(
                auth.authorizationId(),
                captureId,
                auth.idempotencyKey(),
                auth.transactionReference(),
                auth.customerId(),
                auth.authorizedAmount(),
                request.captureAmountMinorUnits(),
                0L,
                auth.currency(),
                PaymentExecutionState.CAPTURED,
                request.allocations(),
                List.of(),
                auth.createdAt()
        );

        payments.put(auth.authorizationId(), updated);
        payments.put(captureId, updated);

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

        StoredWhiteLabelPayment auth = payments.get(request.authorizationId());
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
                    "Cannot cancel in state: " + auth.state(),
                    400
            );
        }

        // Release reservation back to available balance
        CustomerSubaccountBalance balance = customerBalances.get(auth.customerId());
        if (balance != null) {
            customerBalances.put(auth.customerId(), balance.releaseReservation(auth.authorizedAmount()));
        }

        StoredWhiteLabelPayment cancelled = new StoredWhiteLabelPayment(
                auth.authorizationId(),
                null,
                auth.idempotencyKey(),
                auth.transactionReference(),
                auth.customerId(),
                auth.authorizedAmount(),
                0L,
                0L,
                auth.currency(),
                PaymentExecutionState.CANCELLED,
                List.of(),
                List.of(),
                auth.createdAt()
        );
        payments.put(auth.authorizationId(), cancelled);

        return ExternalPaymentCancelResult.success(auth.authorizationId(), request.reason());
    }

    @Override
    public ExternalWalletRefundResult refundToWallet(ExternalWalletRefundRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        StoredWhiteLabelPayment payment = payments.get(request.originalCaptureId());
        if (payment == null) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.TRANSACTION_NOT_FOUND,
                    "Capture not found: " + request.originalCaptureId(),
                    404
            );
        }

        long totalRefunded = payment.refundedAmount() + request.refundAmountMinorUnits();
        if (totalRefunded > payment.capturedAmount()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.LIMIT_EXCEEDED,
                    "Cumulative refund exceeds captured amount",
                    400
            );
        }

        // Credit customer available balance in internal ledger
        creditCustomerWallet(payment.customerId(), request.refundAmountMinorUnits(), request.currency());

        PaymentExecutionState newState = (totalRefunded == payment.capturedAmount())
                ? PaymentExecutionState.REFUNDED : PaymentExecutionState.PARTIALLY_REFUNDED;

        String refundId = "wlr_" + UUID.randomUUID().toString().replace("-", "");
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

        CustomerSubaccountBalance balance = customerBalances.get(request.externalCustomerId());
        if (balance == null || balance.availableMinorUnits() < request.amountMinorUnits()) {
            throw new EnterpriseWalletException(
                    EnterpriseWalletException.ErrorCode.INSUFFICIENT_FUNDS,
                    "Insufficient available funds for withdrawal",
                    400
            );
        }

        // Debit customer subaccount
        customerBalances.put(request.externalCustomerId(), balance.withAvailable(balance.availableMinorUnits() - request.amountMinorUnits()));

        String withdrawalId = "wlw_" + UUID.randomUUID().toString().replace("-", "");
        return ExternalWalletWithdrawalResult.success(
                withdrawalId,
                "WL-W-" + UUID.randomUUID().toString().substring(0, 8),
                request.amountMinorUnits(),
                request.currency(),
                request.destinationRail(),
                request.destinationPhoneOrAccount()
        );
    }

    @Override
    public ExternalBalanceQueryResult queryWalletBalance(ExternalBalanceQueryRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        CustomerSubaccountBalance balance = customerBalances.get(request.externalCustomerId());
        long available = (balance != null) ? balance.availableMinorUnits() : 0L;
        long reserved = (balance != null) ? balance.reservedMinorUnits() : 0L;

        return ExternalBalanceQueryResult.success(
                request.partnerId(),
                request.externalCustomerId(),
                available,
                reserved,
                request.currency()
        );
    }

    @Override
    public ExternalPaymentStatusResult queryPaymentStatus(String reference) {
        StoredWhiteLabelPayment payment = payments.get(reference);
        if (payment == null) {
            return ExternalPaymentStatusResult.notFound(reference);
        }
        return ExternalPaymentStatusResult.of(
                payment.transactionReference(),
                payment.state(),
                payment.capturedAmount() > 0 ? payment.capturedAmount() : payment.authorizedAmount(),
                payment.currency(),
                payment.createdAt(),
                "White-label status: " + payment.state()
        );
    }

    @Override
    public WebhookSignatureVerificationResult verifyWebhook(WebhookSignatureVerificationRequest request) {
        PartnerAuthCredentials creds = tokenService.getPartnerCredentials(request.partnerId()).orElse(null);
        return webhookVerifier.verify(request, creds);
    }

    @Override
    public ExternalReconciliationSyncResult syncLedgerReconciliation(ExternalReconciliationSyncRequest request) {
        String batchId = "recon_wl_" + UUID.randomUUID().toString().replace("-", "");
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
