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

    @org.springframework.beans.factory.annotation.Value("${elepay.payouts.enforce-batch-destination-allowlist:false}")
    private volatile boolean enforceBatchDestinationAllowlist = false;

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
                            null, actor);
                }));
        payouts.submit(batch.id());
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
            java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
            for (ItemInput item : request.items()) {
                Payouts.Destination norm = payouts.normalize(
                        new Payouts.Destination(item.type(), item.network(), item.accountNumber(), item.accountName()));
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
        }
        Payouts.Batch batch = tx.execute(s -> idempotency.execute("payout-batch:" + merchantId, key, request,
                Payouts.Batch.class, () -> payouts.create("merchant", merchantId, request.items().stream()
                        .map(i -> new Payouts.Item(i.reference(), i.amount(),
                                new Payouts.Destination(i.type(), i.network(), i.accountNumber(), i.accountName()),
                                i.platform(), null)).toList(), request.description(), a.actor().subject())));
        payouts.submit(batch.id());
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

    @PostMapping("/payouts/{reference}/retry")
    public Payouts.Batch retry(@PathVariable UUID merchantId, @PathVariable String reference,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var payoutOpt = db.sql("SELECT client_reference FROM payout WHERE reference = :r AND owner_type = 'merchant' AND owner_id = :m")
                .param("r", reference).param("m", merchantId).query(String.class).optional();
        if (payoutOpt.isEmpty()) {
            throw ApiException.notFound("Payout not found");
        }
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("missing_idempotency_key", "Idempotency-Key header is required");
        }
        String clientRef = payoutOpt.get() == null ? "" : payoutOpt.get();
        var a = access.require(merchantId, clientRef.contains("withdrawal-") ? WITHDRAW : PAYOUT);
        if (a.actor().isApiKey() && !a.actor().isPayoutKey()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "payout_key_required",
                    "Secret keys (sk_live) cannot initiate payouts or withdrawals alone; use a dedicated payout_live key");
        }
        Payouts.Batch batch = tx.execute(s -> idempotency.execute("payout-retry:" + merchantId, key,
                java.util.Map.of("reference", reference), Payouts.Batch.class,
                () -> payouts.retry("merchant", merchantId, reference, a.actor().subject())));
        payouts.submit(batch.id());
        return payouts.batch(batch.id());
    }
}
