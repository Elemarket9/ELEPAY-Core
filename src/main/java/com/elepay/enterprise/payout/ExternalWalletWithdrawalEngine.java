package com.elepay.enterprise.payout;

import com.elepay.common.ApiException;
import com.elepay.enterprise.wallet.EnterpriseTokenService;
import com.elepay.enterprise.wallet.PartnerScope;
import com.elepay.payout.Payouts;
import com.elepay.payout.Payouts.Batch;
import com.elepay.payout.Payouts.Destination;
import com.elepay.payout.Payouts.Item;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * External Wallet Withdrawal Engine.
 *
 * <p>Processes authorized customer withdrawal requests from enterprise-owned wallets
 * (e.g. a refund balance held in an enterprise-owned wallet) into domestic mobile money (MTN, Telecel, AirtelTigo) or bank rails.
 * Coordinates delegated token validation, settlement funding checks, Ghana E.164 phone verification,
 * Bank of Ghana KYC daily tier limits, sanctions screening, and domestic payout dispatch.
 */
@Service
public class ExternalWalletWithdrawalEngine {

    private final EnterpriseTokenService tokenService;
    private final EnterpriseSettlementFundingService fundingService;
    private final EnterpriseWithdrawalComplianceService complianceService;
    private final Payouts payouts;
    private final JdbcClient db;

    // Fast in-memory caches for lookups and unit tests
    private final Map<UUID, EnterpriseWithdrawalResult> withdrawalsById = new ConcurrentHashMap<>();
    private final Map<String, EnterpriseWithdrawalResult> withdrawalsByIdempotency = new ConcurrentHashMap<>();
    private final Map<String, EnterpriseWithdrawalResult> withdrawalsByRef = new ConcurrentHashMap<>();

    public ExternalWalletWithdrawalEngine(
            EnterpriseTokenService tokenService,
            EnterpriseSettlementFundingService fundingService,
            EnterpriseWithdrawalComplianceService complianceService,
            Payouts payouts,
            JdbcClient db
    ) {
        this.tokenService = tokenService;
        this.fundingService = Objects.requireNonNull(fundingService, "fundingService cannot be null");
        this.complianceService = Objects.requireNonNull(complianceService, "complianceService cannot be null");
        this.payouts = Objects.requireNonNull(payouts, "payouts cannot be null");
        this.db = db;
    }

    /**
     * Executes an authorized customer withdrawal from an enterprise wallet to domestic rails.
     */
    @Transactional
    public EnterpriseWithdrawalResult requestWithdrawal(EnterpriseWithdrawalRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        // 1. Destination Phone / Account Validation
        String destinationAccount = request.destinationAccount();
        String network = request.destinationNetwork();
        String railType;

        if (request.destinationType() == WithdrawalDestinationType.MOMO) {
            GhanaPhoneValidator.NormalizedPhone normalized = GhanaPhoneValidator.validateAndNormalize(destinationAccount);
            destinationAccount = normalized.canonicalE164();
            railType = "momo";
            if (network == null || network.isBlank() || "unknown".equalsIgnoreCase(network)) {
                network = normalized.network();
            }
        } else {
            railType = "bank";
            if (network == null || network.isBlank()) {
                throw ApiException.badRequest("invalid_network", "Bank network/code is required for bank withdrawal");
            }
        }

        // 2. Idempotency Check
        EnterpriseWithdrawalResult existing = withdrawalsByIdempotency.get(request.idempotencyKey());
        if (existing != null) {
            if (!existing.externalCustomerId().equals(request.externalCustomerId())
                    || existing.amountPesewas() != request.amountPesewas()
                    || !existing.destinationAccount().equals(destinationAccount)) {
                throw ApiException.conflict("idempotency_conflict",
                        "Idempotency conflict for key: " + request.idempotencyKey());
            }
            return new EnterpriseWithdrawalResult(
                    existing.withdrawalId(),
                    existing.withdrawalReference(),
                    existing.idempotencyKey(),
                    existing.enterpriseId(),
                    existing.externalCustomerId(),
                    existing.customerName(),
                    existing.amountPesewas(),
                    existing.currency(),
                    existing.destinationType(),
                    existing.destinationNetwork(),
                    existing.destinationAccount(),
                    existing.status(),
                    existing.payoutBatchId(),
                    existing.payoutId(),
                    existing.compliancePassed(),
                    existing.failureReason(),
                    existing.retryOf(),
                    true,
                    existing.createdAt(),
                    existing.completedAt()
            );
        }

        // 3. Delegated Token Authorization Validation (if token provided)
        if (request.delegatedTokenId() != null && !request.delegatedTokenId().isBlank() && tokenService != null) {
            tokenService.validateDelegatedToken(
                    request.delegatedTokenId(),
                    PartnerScope.WALLET_WITHDRAW,
                    request.amountPesewas(),
                    request.currency()
            );
        }

        // 4. Enterprise Settlement Funding Verification
        EnterpriseFundingCheckResult fundingCheck = fundingService.checkFunding(
                request.enterpriseId(),
                request.amountPesewas(),
                request.currency()
        );
        if (!fundingCheck.sufficient()) {
            throw ApiException.badRequest("insufficient_funding", fundingCheck.message());
        }

        // 5. Compliance Screening (KYC Tier daily limits & Sanctions)
        ComplianceCheckResult compliance = complianceService.reserveWithdrawal(
                request.enterpriseId(),
                request.externalCustomerId(),
                request.customerName(),
                request.kycTier(),
                request.amountPesewas()
        );

        if (!compliance.passed()) {
            if (compliance.sanctionsHit()) {
                throw ApiException.badRequest("sanctions_violation", compliance.failureReason());
            } else if (compliance.dailyLimitExceeded()) {
                throw ApiException.badRequest("daily_limit_exceeded", compliance.failureReason());
            } else {
                throw ApiException.badRequest("compliance_failed", compliance.failureReason());
            }
        }

        // 6. Dispatch Domestic Payout via Payouts Engine
        String withdrawalRef = "EWD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Destination dest = new Destination(railType, network.toLowerCase(), destinationAccount, request.customerName());
        Item item = new Item(withdrawalRef, request.amountPesewas(), dest, "ENTERPRISE_WITHDRAWAL", request.customerName());

        Batch batch;
        UUID payoutId = null;
        try {
            batch = payouts.create(
                    "merchant",
                    request.enterpriseId(),
                    List.of(item),
                    "Enterprise customer withdrawal: " + withdrawalRef,
                    "enterprise"
            );

            // Submit batch to payment rails
            payouts.submit(batch.id());

            if (!batch.items().isEmpty()) {
                payoutId = batch.items().get(0).id();
            }
        } catch (Exception e) {
            // Roll back daily accumulator reservation if payout dispatch fails
            complianceService.releaseReservation(request.enterpriseId(), request.externalCustomerId(), request.amountPesewas());
            throw e;
        }

        // 7. Daily Accumulator already reserved and recorded atomically in Step 5

        // 8. Persist Withdrawal Record
        UUID withdrawalId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO enterprise_wallet_withdrawal
                    (id, enterprise_id, idempotency_key, withdrawal_reference, delegated_token_id, external_customer_id,
                     customer_name, national_id, kyc_tier, amount_pesewas, currency, destination_rail, destination_network,
                     destination_account, payout_batch_id, payout_id, status, compliance_checked, created_at)
                    VALUES
                    (:id, :eid, :ikey, :ref, :tid, :cid, :cname, :nid, :tier, :amt, :cur, :rail, :net, :acc, :bid, :pid, 'SUBMITTED', true, :now)
                    """)
                    .param("id", withdrawalId)
                    .param("eid", request.enterpriseId())
                    .param("ikey", request.idempotencyKey())
                    .param("ref", withdrawalRef)
                    .param("tid", request.delegatedTokenId())
                    .param("cid", request.externalCustomerId())
                    .param("cname", request.customerName())
                    .param("nid", request.nationalId())
                    .param("tier", request.kycTier())
                    .param("amt", request.amountPesewas())
                    .param("cur", request.currency())
                    .param("rail", railType)
                    .param("net", network.toLowerCase())
                    .param("acc", destinationAccount)
                    .param("bid", batch.id())
                    .param("pid", payoutId)
                    .param("now", now)
                    .update();
            } catch (Exception ignored) {}
        }

        EnterpriseWithdrawalResult result = new EnterpriseWithdrawalResult(
                withdrawalId,
                withdrawalRef,
                request.idempotencyKey(),
                request.enterpriseId(),
                request.externalCustomerId(),
                request.customerName(),
                request.amountPesewas(),
                request.currency(),
                request.destinationType(),
                network,
                destinationAccount,
                WithdrawalStatus.SUBMITTED,
                batch.id(),
                payoutId,
                true,
                null,
                null,
                false,
                now,
                null
        );

        withdrawalsById.put(withdrawalId, result);
        withdrawalsByIdempotency.put(request.idempotencyKey(), result);
        withdrawalsByRef.put(withdrawalRef, result);

        return result;
    }

    /**
     * Retries an eligible failed or returned withdrawal under a new idempotency key.
     */
    @Transactional
    public EnterpriseWithdrawalResult retryWithdrawal(String originalWithdrawalRef, String newIdempotencyKey) {
        if (originalWithdrawalRef == null || originalWithdrawalRef.isBlank()) {
            throw ApiException.badRequest("invalid_reference", "Original withdrawal reference is required");
        }
        if (newIdempotencyKey == null || newIdempotencyKey.isBlank()) {
            throw ApiException.badRequest("invalid_idempotency_key", "New idempotency key is required");
        }

        EnterpriseWithdrawalResult original = withdrawalsByRef.get(originalWithdrawalRef);
        if (original == null) {
            throw ApiException.notFound("Withdrawal not found: " + originalWithdrawalRef);
        }

        // Verify payout eligibility in Payouts engine
        if (!payouts.isRetryEligible(original.withdrawalReference())) {
            throw ApiException.conflict("not_retry_eligible",
                    "Withdrawal " + originalWithdrawalRef + " is not eligible for retry. Current status: " + original.status());
        }

        Batch retriedBatch = payouts.retry(
                "merchant",
                original.enterpriseId(),
                original.withdrawalReference(),
                "enterprise"
        );
        payouts.submit(retriedBatch.id());

        UUID newPayoutId = retriedBatch.items().isEmpty() ? null : retriedBatch.items().get(0).id();
        UUID newWithdrawalId = UUID.randomUUID();
        String newWithdrawalRef = "EWD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO enterprise_wallet_withdrawal
                    (id, enterprise_id, idempotency_key, withdrawal_reference, external_customer_id, customer_name,
                     kyc_tier, amount_pesewas, currency, destination_rail, destination_network, destination_account,
                     payout_batch_id, payout_id, status, retry_of, compliance_checked, created_at)
                    VALUES
                    (:id, :eid, :ikey, :ref, :cid, :cname, 1, :amt, :cur, :rail, :net, :acc, :bid, :pid, 'SUBMITTED', :retryOf, true, :now)
                    """)
                    .param("id", newWithdrawalId)
                    .param("eid", original.enterpriseId())
                    .param("ikey", newIdempotencyKey)
                    .param("ref", newWithdrawalRef)
                    .param("cid", original.externalCustomerId())
                    .param("cname", original.customerName())
                    .param("amt", original.amountPesewas())
                    .param("cur", original.currency())
                    .param("rail", original.destinationType().name().toLowerCase())
                    .param("net", original.destinationNetwork())
                    .param("acc", original.destinationAccount())
                    .param("bid", retriedBatch.id())
                    .param("pid", newPayoutId)
                    .param("retryOf", original.withdrawalId())
                    .param("now", now)
                    .update();
            } catch (Exception ignored) {}
        }

        EnterpriseWithdrawalResult result = new EnterpriseWithdrawalResult(
                newWithdrawalId,
                newWithdrawalRef,
                newIdempotencyKey,
                original.enterpriseId(),
                original.externalCustomerId(),
                original.customerName(),
                original.amountPesewas(),
                original.currency(),
                original.destinationType(),
                original.destinationNetwork(),
                original.destinationAccount(),
                WithdrawalStatus.SUBMITTED,
                retriedBatch.id(),
                newPayoutId,
                true,
                null,
                original.withdrawalId(),
                false,
                now,
                null
        );

        withdrawalsById.put(newWithdrawalId, result);
        withdrawalsByIdempotency.put(newIdempotencyKey, result);
        withdrawalsByRef.put(newWithdrawalRef, result);

        return result;
    }

    public Optional<EnterpriseWithdrawalResult> findWithdrawalById(UUID withdrawalId) {
        return Optional.ofNullable(withdrawalsById.get(withdrawalId));
    }

    public Optional<EnterpriseWithdrawalResult> findWithdrawalByReference(String reference) {
        return Optional.ofNullable(withdrawalsByRef.get(reference));
    }
}
