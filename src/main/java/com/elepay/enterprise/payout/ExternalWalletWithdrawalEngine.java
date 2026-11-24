package com.elepay.enterprise.payout;

import com.elepay.common.ApiException;
import com.elepay.enterprise.wallet.EnterpriseTokenService;
import com.elepay.enterprise.wallet.PartnerScope;
import com.elepay.payout.Payouts;
import com.elepay.payout.Payouts.Batch;
import com.elepay.payout.Payouts.Destination;
import com.elepay.payout.Payouts.Item;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate tx;

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
        this(tokenService, fundingService, complianceService, payouts, db, null);
    }

    @Autowired
    public ExternalWalletWithdrawalEngine(
            EnterpriseTokenService tokenService,
            EnterpriseSettlementFundingService fundingService,
            EnterpriseWithdrawalComplianceService complianceService,
            Payouts payouts,
            JdbcClient db,
            @Autowired(required = false) PlatformTransactionManager transactionManager
    ) {
        this.tokenService = tokenService;
        this.fundingService = Objects.requireNonNull(fundingService, "fundingService cannot be null");
        this.complianceService = Objects.requireNonNull(complianceService, "complianceService cannot be null");
        this.payouts = Objects.requireNonNull(payouts, "payouts cannot be null");
        this.db = db;
        this.tx = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    /**
     * Executes an authorized customer withdrawal from an enterprise wallet to domestic rails.
     */
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

        // 2. Idempotency Check (Authoritative PostgreSQL + in-memory cache, scoped by enterpriseId)
        String scopedIdempotencyKey = idempotencyScopeKey(request.enterpriseId(), request.idempotencyKey());
        EnterpriseWithdrawalResult existing = withdrawalsByIdempotency.get(scopedIdempotencyKey);
        if (existing == null && db != null) {
            existing = findByIdempotencyInDb(request.enterpriseId(), request.idempotencyKey()).orElse(null);
        }
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

        // 5. Authoritative KYC Tier Lookup & Compliance Screening
        int authoritativeKycTier = resolveAuthoritativeKycTier(request);
        ComplianceCheckResult compliance = complianceService.reserveWithdrawal(
                request.enterpriseId(),
                request.externalCustomerId(),
                request.customerName(),
                authoritativeKycTier,
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

        // 6. Persist withdrawal intent + payout reservation inside transaction (NO external rail submit inside TX)
        String withdrawalRef = "EWD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String finalNetwork = network.toLowerCase();
        String finalDestAccount = destinationAccount;
        Destination dest = new Destination(railType, finalNetwork, finalDestAccount, request.customerName());
        Item item = new Item(withdrawalRef, request.amountPesewas(), dest, "ENTERPRISE_WITHDRAWAL", request.customerName());
        UUID withdrawalId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Batch batch;
        try {
            if (tx != null) {
                batch = tx.execute(status -> createAndPersistWithdrawal(
                        withdrawalId, withdrawalRef, request, railType, finalNetwork, finalDestAccount,
                        item, authoritativeKycTier, now));
            } else {
                batch = createAndPersistWithdrawal(
                        withdrawalId, withdrawalRef, request, railType, finalNetwork, finalDestAccount,
                        item, authoritativeKycTier, now);
            }
        } catch (Exception e) {
            complianceService.releaseReservation(request.enterpriseId(), request.externalCustomerId(), request.amountPesewas());
            throw e;
        }

        UUID payoutId = (batch != null && !batch.items().isEmpty()) ? batch.items().get(0).id() : null;

        // 7. Submit batch to payment rails AFTER transaction commit
        payouts.submit(batch.id());
        if (db != null) {
            db.sql("UPDATE enterprise_wallet_withdrawal SET status = 'SUBMITTED' WHERE id = :id")
                    .param("id", withdrawalId).update();
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
        withdrawalsByIdempotency.put(scopedIdempotencyKey, result);
        withdrawalsByRef.put(withdrawalRef, result);

        return result;
    }

    private Batch createAndPersistWithdrawal(
            UUID withdrawalId,
            String withdrawalRef,
            EnterpriseWithdrawalRequest request,
            String railType,
            String network,
            String destinationAccount,
            Item item,
            int authoritativeKycTier,
            OffsetDateTime now
    ) {
        Batch batch = payouts.create(
                "merchant",
                request.enterpriseId(),
                List.of(item),
                "Enterprise customer withdrawal: " + withdrawalRef,
                "enterprise"
        );
        UUID payoutId = !batch.items().isEmpty() ? batch.items().get(0).id() : null;

        if (db != null) {
            db.sql("""
                INSERT INTO enterprise_wallet_withdrawal
                (id, enterprise_id, idempotency_key, withdrawal_reference, delegated_token_id, external_customer_id,
                 customer_name, national_id, kyc_tier, amount_pesewas, currency, destination_rail, destination_network,
                 destination_account, payout_batch_id, payout_id, status, compliance_checked, created_at)
                VALUES
                (:id, :eid, :ikey, :ref, :tid, :cid, :cname, :nid, :tier, :amt, :cur, :rail, :net, :acc, :bid, :pid, 'PENDING', true, :now)
                """)
                .param("id", withdrawalId)
                .param("eid", request.enterpriseId())
                .param("ikey", request.idempotencyKey())
                .param("ref", withdrawalRef)
                .param("tid", request.delegatedTokenId())
                .param("cid", request.externalCustomerId())
                .param("cname", request.customerName())
                .param("nid", request.nationalId())
                .param("tier", authoritativeKycTier)
                .param("amt", request.amountPesewas())
                .param("cur", request.currency())
                .param("rail", railType)
                .param("net", network)
                .param("acc", destinationAccount)
                .param("bid", batch.id())
                .param("pid", payoutId)
                .param("now", now)
                .update();
        }
        return batch;
    }

    private int resolveAuthoritativeKycTier(EnterpriseWithdrawalRequest request) {
        if (db == null) {
            return request.kycTier();
        }
        return db.sql("""
                SELECT kyc_tier FROM enterprise_managed_account
                WHERE enterprise_id = :eid AND external_customer_id = :cid""")
                .param("eid", request.enterpriseId())
                .param("cid", request.externalCustomerId())
                .query(Integer.class)
                .optional()
                .orElse(1);
    }

    private static String idempotencyScopeKey(UUID enterpriseId, String idempotencyKey) {
        return enterpriseId + ":" + idempotencyKey;
    }

    private Optional<EnterpriseWithdrawalResult> findByIdempotencyInDb(UUID enterpriseId, String idempotencyKey) {
        return db.sql("""
                SELECT id, withdrawal_reference, idempotency_key, enterprise_id, external_customer_id,
                       customer_name, amount_pesewas, currency, destination_rail, destination_network,
                       destination_account, status, payout_batch_id, payout_id, compliance_checked,
                       failure_reason, retry_of, created_at, completed_at
                FROM enterprise_wallet_withdrawal
                WHERE enterprise_id = :eid AND idempotency_key = :ikey""")
                .param("eid", enterpriseId)
                .param("ikey", idempotencyKey)
                .query((rs, i) -> new EnterpriseWithdrawalResult(
                        rs.getObject("id", UUID.class),
                        rs.getString("withdrawal_reference"),
                        rs.getString("idempotency_key"),
                        rs.getObject("enterprise_id", UUID.class),
                        rs.getString("external_customer_id"),
                        rs.getString("customer_name"),
                        rs.getLong("amount_pesewas"),
                        rs.getString("currency"),
                        "momo".equalsIgnoreCase(rs.getString("destination_rail"))
                                ? WithdrawalDestinationType.MOMO : WithdrawalDestinationType.BANK,
                        rs.getString("destination_network"),
                        rs.getString("destination_account"),
                        WithdrawalStatus.valueOf(rs.getString("status")),
                        rs.getObject("payout_batch_id", UUID.class),
                        rs.getObject("payout_id", UUID.class),
                        rs.getBoolean("compliance_checked"),
                        rs.getString("failure_reason"),
                        rs.getObject("retry_of", UUID.class),
                        true,
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("completed_at", OffsetDateTime.class)
                ))
                .optional();
    }

    /**
     * Retries an eligible failed or returned withdrawal under a new idempotency key.
     */
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

        UUID newWithdrawalId = UUID.randomUUID();
        String newWithdrawalRef = "EWD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Batch retriedBatch = tx != null
                ? tx.execute(status -> createAndPersistRetry(original, newWithdrawalId, newWithdrawalRef, newIdempotencyKey, now))
                : createAndPersistRetry(original, newWithdrawalId, newWithdrawalRef, newIdempotencyKey, now);

        payouts.submit(retriedBatch.id());
        if (db != null) {
            db.sql("UPDATE enterprise_wallet_withdrawal SET status = 'SUBMITTED' WHERE id = :id")
                    .param("id", newWithdrawalId).update();
        }

        UUID newPayoutId = retriedBatch.items().isEmpty() ? null : retriedBatch.items().get(0).id();

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
        withdrawalsByIdempotency.put(idempotencyScopeKey(original.enterpriseId(), newIdempotencyKey), result);
        withdrawalsByRef.put(newWithdrawalRef, result);

        return result;
    }

    private Batch createAndPersistRetry(
            EnterpriseWithdrawalResult original,
            UUID newWithdrawalId,
            String newWithdrawalRef,
            String newIdempotencyKey,
            OffsetDateTime now
    ) {
        Batch retriedBatch = payouts.retry(
                "merchant",
                original.enterpriseId(),
                original.withdrawalReference(),
                "enterprise"
        );
        UUID newPayoutId = retriedBatch.items().isEmpty() ? null : retriedBatch.items().get(0).id();

        if (db != null) {
            db.sql("""
                INSERT INTO enterprise_wallet_withdrawal
                (id, enterprise_id, idempotency_key, withdrawal_reference, external_customer_id, customer_name,
                 kyc_tier, amount_pesewas, currency, destination_rail, destination_network, destination_account,
                 payout_batch_id, payout_id, status, retry_of, compliance_checked, created_at)
                VALUES
                (:id, :eid, :ikey, :ref, :cid, :cname, 1, :amt, :cur, :rail, :net, :acc, :bid, :pid, 'PENDING', :retryOf, true, :now)
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
        }
        return retriedBatch;
    }

    public Optional<EnterpriseWithdrawalResult> findWithdrawalById(UUID withdrawalId) {
        return Optional.ofNullable(withdrawalsById.get(withdrawalId));
    }

    public Optional<EnterpriseWithdrawalResult> findWithdrawalByReference(String reference) {
        return Optional.ofNullable(withdrawalsByRef.get(reference));
    }
}
