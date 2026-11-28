package com.elepay.rails;

import java.time.Instant;
import java.util.Objects;

/**
 * Host-to-host gateway contract between ELEPAY and its Custodian Bank Trust Account
 * (e.g., GCB Bank, Ecobank Ghana, Stanbic Bank Ghana).
 *
 * <p>All real money sits in ELEPAY's single Bank of Ghana regulated Custodian Trust Account.
 * Implementations must be strictly idempotent, fail-closed on ambiguous network outcomes
 * (returning {@link DisbursementStatus#UNKNOWN} rather than {@link DisbursementStatus#REJECTED}),
 * and never hardcode bank credentials.
 */
public interface CustodianBankGateway {

    enum DisbursementStatus {
        ACCEPTED,
        SETTLED,
        REJECTED,
        UNKNOWN
    }

    record TrustBalanceSnapshot(
            String trustAccountCode,
            String currency,
            long availableBalancePesewas,
            long ledgerBalancePesewas,
            Instant asOf,
            String bankStatementReference
    ) {
        public TrustBalanceSnapshot {
            Objects.requireNonNull(trustAccountCode, "trustAccountCode is required");
            Objects.requireNonNull(currency, "currency is required");
            if (availableBalancePesewas < 0 || ledgerBalancePesewas < 0) {
                throw new IllegalArgumentException("Trust account balances cannot be negative");
            }
            if (asOf == null) asOf = Instant.now();
        }
    }

    record DisbursementInstruction(
            String idempotencyKey,
            String trustAccountCode,
            long amountPesewas,
            String currency,
            String destinationType,
            String destinationBankOrNetwork,
            String destinationAccountNumber,
            String destinationAccountName,
            String purpose,
            String narration
    ) {
        public DisbursementInstruction {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey is required");
            }
            if (trustAccountCode == null || trustAccountCode.isBlank()) {
                throw new IllegalArgumentException("trustAccountCode is required");
            }
            if (amountPesewas <= 0) {
                throw new IllegalArgumentException("amountPesewas must be positive");
            }
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("currency is required");
            }
            if (destinationAccountNumber == null || destinationAccountNumber.isBlank()) {
                throw new IllegalArgumentException("destinationAccountNumber is required");
            }
        }
    }

    record DisbursementResponse(
            DisbursementStatus status,
            String bankReference,
            String message
    ) {
        public DisbursementResponse {
            Objects.requireNonNull(status, "status is required");
        }
    }

    /** Queries the Custodian Bank for the live trust account balance. */
    TrustBalanceSnapshot fetchTrustBalance(String trustAccountCode, String currency);

    /** Submits an idempotent outbound transfer instruction from the Custodian Trust Account. */
    DisbursementResponse initiateDisbursement(DisbursementInstruction instruction);

    /** Queries the Custodian Bank for the authoritative status of a previously submitted transfer. */
    DisbursementResponse queryDisbursement(String idempotencyKey, String bankReference);
}
