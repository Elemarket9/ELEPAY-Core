package com.elepay.payout;

import static com.elepay.merchant.MerchantAccess.Permission.*;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.merchant.MerchantAccess;
import com.elepay.security.DualControl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Merchant withdrawals to settlement destinations, and batch payouts from a
 * merchant balance (platform earnings for drivers, couriers and creators).
 */
@RestController
@RequestMapping("/v1/merchants/{merchantId}")
public class MerchantPayouts {
    private final Payouts payouts;
    private final MerchantAccess access;
    private final Idempotency idempotency;
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final DualControl dualControl;

    @org.springframework.beans.factory.annotation.Value("${elepay.payouts.enforce-batch-destination-allowlist:true}")
    private volatile boolean enforceBatchDestinationAllowlist = true;

    @org.springframework.beans.factory.annotation.Value("${elepay.security.large-payout-pesewas:5000000}")
    private long largePayoutPesewas = 5_000_000L;

    MerchantPayouts(Payouts payouts, MerchantAccess access, Idempotency idempotency, JdbcClient db,
                    PlatformTransactionManager tm, DualControl dualControl) {
        this.payouts = payouts;
        this.access = access;
        this.idempotency = idempotency;
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.dualControl = dualControl;
    }

    public void setEnforceBatchDestinationAllowlist(boolean enforce) {
        this.enforceBatchDestinationAllowlist = enforce;
    }

    record Withdrawal(@Positive long amount, @NotNull UUID destinationId) {}

    record ItemInput(@NotBlank @Size(max = 64) String reference, @Positive long amount,
                     @NotBlank @Pattern(regexp = "momo|bank") String type, @NotBlank @Size(max = 20) String network,
                     @NotBlank @Size(max = 34) String accountNumber, @NotBlank @Size(max = 150) String accountName,
                     @Pattern(regexp = "bolt|yango|x|tiktok") String platform) {}

    record BatchInput(@NotEmpty @Size(max = Payouts.MAX_BATCH) List<@Valid ItemInput> items,
                      @Size(max = 200) String description) {}

    private void submitAfterCommit(UUID batchId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    payouts.submit(batchId);
                }
            });
        } else {
            payouts.submit(batchId);
        }
    }

    @PostMapping("/withdrawals")
    public Payouts.Batch withdraw(@PathVariable UUID merchantId, @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody Withdrawal request) {
        var a = access.require(merchantId, WITHDRAW);
        if (a.actor().isApiKey() && !a.actor().isPayoutKey()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "payout_key_required",
                    "Secret keys (sk_live) cannot initiate payouts or withdrawals alone; use a dedicated payout_live key");
        }
        if (request.amount() >= largePayoutPesewas) {
            dualControl.hold("LARGE_PAYOUT", "withdrawal", merchantId.toString(), merchantId,
                    Map.of("merchantId", merchantId.toString(), "amount", request.amount(),
                            "destinationId", request.destinationId().toString(), "idempotencyKey", key),
                    "large withdrawal", a.actor());
        }
        return executeWithdrawal(merchantId, key, request, a.actor().subject());
    }

    public Payouts.Batch executeWithdrawal(UUID merchantId, String key, Withdrawal request, String actor) {
        return executeWithdrawal(merchantId, key, request, actor, false);
    }

    Payouts.Batch executeWithdrawal(UUID merchantId, String key, Withdrawal request, String actor,
                                    boolean makerCheckerApproved) {
        Payouts.Batch batch = tx.execute(s -> idempotency.execute("merchant-withdrawal:" + merchantId, key, request,
                Payouts.Batch.class, () -> {
                    var dest = access.activeDestination(merchantId, request.destinationId())
                            .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                                    "destination_not_allowlisted",
                                    "Payouts are restricted to pre-approved, verified settlement destinations"));
                    if (!dest.verified()) {
                        throw ApiException.conflict("destination_unverified",
                                "Withdrawals go only to a verified destination owned by this merchant");
                    }
                    if (dest.isCoolingOff(java.time.OffsetDateTime.now())) {
                        throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                                "destination_cooling_off_active",
                                "Settlement destination is in 24-hour cooling-off lock until " + dest.coolingOffUntil());
                    }
                    // Withdrawals carry no ELEPAY fee.
                    return payouts.create("merchant", merchantId, List.of(new Payouts.Item(
                            "withdrawal-" + key, request.amount(),
                            new Payouts.Destination(dest.type(), dest.network(), dest.accountNumber(), dest.accountName()),
                            null, dest.verifiedName())),
                            null, actor, makerCheckerApproved);
                }));
        submitAfterCommit(batch.id());
        return payouts.batch(batch.id());
    }

    @PostMapping("/payout-batches")
    public Payouts.Batch batch(@PathVariable UUID merchantId, @RequestHeader("Idempotency-Key") String key,
                               @Valid @RequestBody BatchInput request) {
        var a = access.require(merchantId, PAYOUT);
        if (a.actor().isApiKey() && !a.actor().isPayoutKey()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "payout_key_required",
                    "Secret keys (sk_live) cannot initiate payouts or withdrawals alone; use a dedicated payout_live key");
        }
        boolean requireAllowlist = enforceBatchDestinationAllowlist || a.actor().isApiKey();
        if (requireAllowlist) {
            validateBatchDestinations(merchantId, request.items());
        }
        long batchTotal = 0L;
        long maxItem = 0L;
        for (ItemInput item : request.items()) {
            batchTotal = Math.addExact(batchTotal, item.amount());
            if (item.amount() > maxItem) {
                maxItem = item.amount();
            }
        }
        if (maxItem >= largePayoutPesewas || batchTotal >= largePayoutPesewas) {
            dualControl.hold("LARGE_PAYOUT", "payout-batch", merchantId.toString(), merchantId,
                    Map.of("merchantId", merchantId.toString(),
                            "totalAmount", batchTotal,
                            "maxItemAmount", maxItem,
                            "idempotencyKey", key,
                            "request", request),
                    "large payout batch", a.actor());
        }
        return executeBatch(merchantId, key, request, a.actor().subject());
    }

    private void validateBatchDestinations(UUID merchantId, List<ItemInput> items) {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        for (ItemInput item : items) {
            validateDestinationAllowlist(merchantId, item.type(), item.network(), item.accountNumber(), item.accountName(), now);
        }
    }

    private void validateDestinationAllowlist(UUID merchantId, String type, String network, String accountNumber,
                                              String accountName, java.time.OffsetDateTime now) {
        Payouts.Destination norm = payouts.normalize(
                new Payouts.Destination(type, network, accountNumber, accountName));
        var match = access.activeDestinationByAccount(merchantId, norm.type(), norm.network(), norm.accountNumber())
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "destination_not_allowlisted",
                        "Payouts are restricted to pre-approved, verified settlement destinations (" + norm.accountNumber() + ")"));
        if (!match.verified()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "destination_not_allowlisted",
                    "Settlement destination is not verified (" + norm.accountNumber() + ")");
        }
        if (match.isCoolingOff(now)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "destination_cooling_off_active",
                    "Settlement destination is in 24-hour cooling-off lock until " + match.coolingOffUntil());
        }
    }

    public Payouts.Batch executeBatch(UUID merchantId, String key, BatchInput request, String actor) {
        return executeBatch(merchantId, key, request, actor, false);
    }

    Payouts.Batch executeBatch(UUID merchantId, String key, BatchInput request, String actor,
                               boolean makerCheckerApproved) {
        if (enforceBatchDestinationAllowlist) {
            validateBatchDestinations(merchantId, request.items());
        }
        Payouts.Batch batch = tx.execute(s -> idempotency.execute("payout-batch:" + merchantId, key, request,
                Payouts.Batch.class, () -> payouts.create("merchant", merchantId, request.items().stream()
                        .map(i -> new Payouts.Item(i.reference(), i.amount(),
                                new Payouts.Destination(i.type(), i.network(), i.accountNumber(), i.accountName()),
                                i.platform(), null)).toList(), request.description(), actor, makerCheckerApproved)));
        submitAfterCommit(batch.id());
        return payouts.batch(batch.id());
    }

    @GetMapping("/payout-batches/{reference}")
    public Payouts.Batch getBatch(@PathVariable UUID merchantId, @PathVariable String reference) {
        access.require(merchantId, FINANCE_VIEW);
        return payouts.batch(payouts.batchId("merchant", merchantId, reference)
                .orElseThrow(() -> ApiException.notFound("Batch not found")));
    }

    @GetMapping("/payouts")
    public List<Payouts.Payout> list(@PathVariable UUID merchantId, @RequestParam(defaultValue = "100") int limit) {
        access.require(merchantId, FINANCE_VIEW);
        return payouts.forOwner("merchant", merchantId, limit);
    }

    @GetMapping({"/payouts/{reference}", "/withdrawals/{reference}"})
    public Payouts.Payout one(@PathVariable UUID merchantId, @PathVariable String reference) {
        access.require(merchantId, FINANCE_VIEW);
        return payouts.forOwner("merchant", merchantId, 500).stream().filter(p -> p.reference().equals(reference))
                .findFirst().orElseThrow(() -> ApiException.notFound("Payout not found"));
    }

    @GetMapping("/withdrawals")
    public List<Payouts.Payout> withdrawals(@PathVariable UUID merchantId) {
        access.require(merchantId, FINANCE_VIEW);
        return payouts.forOwner("merchant", merchantId, 500).stream()
                .filter(p -> p.clientReference() != null && (p.clientReference().startsWith("withdrawal-")
                        || p.clientReference().startsWith("auto-withdrawal-"))).toList();
    }

    private MerchantAccess.Access requireWithdrawOrPayout(UUID merchantId) {
        try {
            return access.require(merchantId, WITHDRAW);
        } catch (ApiException withdrawDenied) {
            if (withdrawDenied.status() == org.springframework.http.HttpStatus.FORBIDDEN
                    || (withdrawDenied.status() == org.springframework.http.HttpStatus.NOT_FOUND
                        && com.elepay.identity.Actor.current().isApiKey())) {
                return access.require(merchantId, PAYOUT);
            }
            throw withdrawDenied;
        }
    }

    @PostMapping("/payouts/{reference}/retry")
    public Payouts.Batch retry(@PathVariable UUID merchantId, @PathVariable String reference,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        // Authorize caller for WITHDRAW or PAYOUT BEFORE looking up payout to avoid both
        // cross-tenant (404) and intra-tenant VIEW-only (403) existence oracles.
        var preAuth = requireWithdrawOrPayout(merchantId);
        if (preAuth.actor().isApiKey() && !preAuth.actor().isPayoutKey()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "payout_key_required",
                    "Secret keys (sk_live) cannot initiate payouts or withdrawals alone; use a dedicated payout_live key");
        }
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("missing_idempotency_key", "Idempotency-Key header is required");
        }
        record RetryRow(String clientRef, long amount, String type, String network, String accountNumber, String accountName) {}
        var payoutOpt = db.sql("""
                SELECT client_reference, amount, destination->>'type', destination->>'network',
                       destination->>'accountNumber', destination->>'accountName'
                FROM payout WHERE reference = :r AND owner_type = 'merchant' AND owner_id = :m""")
                .param("r", reference).param("m", merchantId)
                .query((rs, i) -> new RetryRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional();
        if (payoutOpt.isEmpty()) {
            throw ApiException.notFound("Payout not found");
        }
        RetryRow row = payoutOpt.get();
        String clientRef = row.clientRef() == null ? "" : row.clientRef();
        boolean isWithdrawal = clientRef.contains("withdrawal-");
        var a = access.require(merchantId, isWithdrawal ? WITHDRAW : PAYOUT);
        if (isWithdrawal || enforceBatchDestinationAllowlist || a.actor().isApiKey()) {
            validateDestinationAllowlist(merchantId, row.type(), row.network(), row.accountNumber(),
                    row.accountName(), java.time.OffsetDateTime.now());
        }
        if (row.amount() >= largePayoutPesewas) {
            dualControl.hold("LARGE_PAYOUT", "payout-retry", merchantId.toString(), merchantId,
                    Map.of("merchantId", merchantId.toString(),
                            "reference", reference,
                            "amount", row.amount(),
                            "idempotencyKey", key),
                    "large payout retry", a.actor());
        }
        return executeRetry(merchantId, reference, key, a.actor().subject());
    }

    public Payouts.Batch executeRetry(UUID merchantId, String reference, String key, String actor) {
        record RetryRow(String clientRef, String type, String network, String accountNumber, String accountName) {}
        var row = db.sql("""
                SELECT client_reference, destination->>'type', destination->>'network',
                       destination->>'accountNumber', destination->>'accountName'
                FROM payout WHERE reference = :r AND owner_type = 'merchant' AND owner_id = :m""")
                .param("r", reference).param("m", merchantId)
                .query((rs, i) -> new RetryRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .optional().orElseThrow(() -> ApiException.notFound("Payout not found"));
        boolean isWithdrawal = row.clientRef() != null && row.clientRef().contains("withdrawal-");
        if (isWithdrawal || enforceBatchDestinationAllowlist) {
            validateDestinationAllowlist(merchantId, row.type(), row.network(), row.accountNumber(),
                    row.accountName(), java.time.OffsetDateTime.now());
        }
        Payouts.Batch batch = tx.execute(s -> idempotency.execute("payout-retry:" + merchantId, key,
                java.util.Map.of("reference", reference), Payouts.Batch.class,
                () -> payouts.retry("merchant", merchantId, reference, actor)));
        submitAfterCommit(batch.id());
        return payouts.batch(batch.id());
    }
}
