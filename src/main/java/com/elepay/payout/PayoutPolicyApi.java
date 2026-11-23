package com.elepay.payout;

import static com.elepay.merchant.MerchantAccess.Permission.*;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import com.elepay.merchant.MerchantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/**
 * Payout policy APIs. A marketplace chooses on-request or automatic withdrawal for its sellers (a default
 * plus per-seller overrides); an independent merchant chooses for itself; every merchant chooses where
 * automatic withdrawals land.
 */
@RestController
@RequestMapping("/v1")
class PayoutPolicyApi {
    private final PayoutPolicies policies;
    private final MerchantAccess access;
    private final JdbcClient db;

    PayoutPolicyApi(PayoutPolicies policies, MerchantAccess access, JdbcClient db) {
        this.policies = policies;
        this.access = access;
        this.db = db;
    }

    record OwnPolicy(@Pattern(regexp = "on_request|automatic") String mode, @PositiveOrZero Long minAmount,
                     UUID destinationId) {}

    record RuleInput(@NotBlank @Pattern(regexp = "on_request|automatic") String mode, @PositiveOrZero Long minAmount) {}

    @GetMapping("/merchants/{merchantId}/payout-policy")
    public PayoutPolicies.Effective mine(@PathVariable UUID merchantId) {
        access.require(merchantId, FINANCE_VIEW);
        return policies.effective(merchantId);
    }

    /** Mode and minimum apply only when no marketplace governs this merchant; the destination always does. */
    @PutMapping("/merchants/{merchantId}/payout-policy")
    public PayoutPolicies.Effective saveMine(@PathVariable UUID merchantId, @Valid @RequestBody OwnPolicy request) {
        var a = access.require(merchantId, MANAGE);
        a.actor().requireUser();
        return policies.saveOwn(merchantId, request.mode(), request.minAmount(), request.destinationId(),
                a.actor().subject());
    }

    @GetMapping("/merchants/{merchantId}/seller-payout-policy")
    public PayoutPolicies.PlatformPolicy platform(@PathVariable UUID merchantId) {
        access.require(merchantId, VIEW);
        return policies.platform(merchantId);
    }

    /** The marketplace's default for all its sellers and delivery partners. */
    @PutMapping("/merchants/{merchantId}/seller-payout-policy")
    public PayoutPolicies.PlatformPolicy savePlatform(@PathVariable UUID merchantId, @Valid @RequestBody RuleInput request) {
        var a = access.require(merchantId, MANAGE);
        a.actor().requireUser();
        return policies.savePlatformDefault(merchantId, request.mode(),
                request.minAmount() == null ? 0 : request.minAmount(), a.actor().subject());
    }

    /** The policy in force for one seller, as the marketplace sees it. */
    @GetMapping("/merchants/{merchantId}/sellers/{sellerId}/payout-policy")
    public PayoutPolicies.Effective seller(@PathVariable UUID merchantId, @PathVariable UUID sellerId) {
        access.require(merchantId, VIEW);
        if (access.relationship(merchantId, sellerId).isEmpty()) throw ApiException.notFound("Seller not found in this marketplace");
        var e = policies.effective(sellerId);
        // The marketplace sees the outcome, not the seller's bank details.
        return new PayoutPolicies.Effective(e.merchantId(), e.mode(), e.minAmount(), e.source(), e.governedBy(), null);
    }

    @PutMapping("/merchants/{merchantId}/sellers/{sellerId}/payout-policy")
    public PayoutPolicies.PlatformPolicy saveSeller(@PathVariable UUID merchantId, @PathVariable UUID sellerId,
                                                    @Valid @RequestBody RuleInput request) {
        var a = access.require(merchantId, MANAGE);
        a.actor().requireUser();
        return policies.saveSellerOverride(merchantId, sellerId, request.mode(),
                request.minAmount() == null ? 0 : request.minAmount(), a.actor().subject());
    }

    /** Removes a seller's override so the marketplace default applies again. */
    @DeleteMapping("/merchants/{merchantId}/sellers/{sellerId}/payout-policy")
    public PayoutPolicies.PlatformPolicy clearSeller(@PathVariable UUID merchantId, @PathVariable UUID sellerId) {
        var a = access.require(merchantId, MANAGE);
        a.actor().requireUser();
        return policies.saveSellerOverride(merchantId, sellerId, null, 0, a.actor().subject());
    }

    /** Operations: run automatic withdrawals now (they also run every minute). */
    @PostMapping("/ops/payouts/automatic/run")
    public Map<String, Object> run() {
        Actor.current().requireOps();
        List<Payouts.Batch> batches = policies.runAutomatic();
        return Map.of("batches", batches.size(), "total", batches.stream().mapToLong(Payouts.Batch::total).sum(),
                "items", batches);
    }
}
