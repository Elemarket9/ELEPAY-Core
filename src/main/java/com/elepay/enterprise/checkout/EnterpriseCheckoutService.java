package com.elepay.enterprise.checkout;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enterprise Externally Collected Payment & Multi-Merchant Split Service.
 *
 * <p>Ingests customer payments collected externally by enterprises/marketplaces
 * (e.g. JumiaPay, MoMo, Card) and executes atomic, multi-tier allocations across
 * sub-merchants, delivery partners, marketplace commissions, and platform revenue
 * via the authoritative double-entry {@link Ledger}.
 */
@Service
public class EnterpriseCheckoutService {

    private final Ledger ledger;
    private final EnterpriseMultiMerchantSplitter splitter;

    // Concurrent lookup indices for processed enterprise checkouts
    private final Map<UUID, EnterpriseCheckoutResult> checkoutsById = new ConcurrentHashMap<>();
    private final Map<String, EnterpriseCheckoutResult> checkoutsByOrderRef = new ConcurrentHashMap<>();

    public EnterpriseCheckoutService(Ledger ledger, EnterpriseMultiMerchantSplitter splitter) {
        this.ledger = Objects.requireNonNull(ledger, "ledger cannot be null");
        this.splitter = Objects.requireNonNull(splitter, "splitter cannot be null");
    }

    /**
     * Ingests and processes an externally collected checkout payment, executing
     * multi-tier split and posting atomic double-entry legs to the authoritative ledger.
     *
     * @param request the payment ingestion request
     * @return the result with ledger transaction ID and exact split breakdown
     */
    @Transactional
    public EnterpriseCheckoutResult ingestExternalPayment(EnterpriseCheckoutPaymentRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        // 1. Calculate deterministic multi-tier split
        EnterpriseSplitRequest splitReq = new EnterpriseSplitRequest(
                request.totalPesewas(),
                request.currency(),
                request.items(),
                request.deliveryFees()
        );
        EnterpriseSplitResult splitResult = splitter.split(splitReq);

        // 2. Validate financial conservation
        splitResult.validateConservation();

        // 3. Prepare ledger accounts
        String currency = request.currency();
        String channel = sanitizeCode(request.paymentChannel());
        String clearingAccount = ledger.openSystem("system:clearing:" + channel + ":" + currency, "clearing", currency, true);

        // Open sub-merchant accounts
        for (CalculatedMerchantAllocation alloc : splitResult.merchantAllocations()) {
            ledger.open("merchant", alloc.merchantId(), "pending", currency);
            if (alloc.reservePesewas() > 0) {
                ledger.open("merchant", alloc.merchantId(), "reserved", currency);
            }
        }

        // Open delivery partner accounts
        for (CalculatedDeliveryAllocation delivery : splitResult.deliveryAllocations()) {
            ledger.open("merchant", delivery.deliveryPartnerId(), "pending", currency);
        }

        // Open enterprise marketplace account
        ledger.open("merchant", request.enterpriseId(), "pending", currency);

        // Open platform revenue account
        ledger.openSystem(Ledger.REVENUE, "revenue", currency, false);

        // 4. Construct double-entry journal legs
        List<Leg> legs = new ArrayList<>();

        // Debit source of funds (clearing account for the external rail/wallet)
        legs.add(Leg.debit(clearingAccount, request.totalPesewas()));

        // Credit sub-merchant pending buckets and reserves
        for (CalculatedMerchantAllocation alloc : splitResult.merchantAllocations()) {
            if (alloc.netPayablePesewas() > 0) {
                legs.add(Leg.credit(Ledger.code("merchant", alloc.merchantId(), "pending", currency), alloc.netPayablePesewas()));
            }
            if (alloc.reservePesewas() > 0) {
                legs.add(Leg.credit(Ledger.code("merchant", alloc.merchantId(), "reserved", currency), alloc.reservePesewas()));
            }
        }

        // Credit delivery partner pending buckets
        for (CalculatedDeliveryAllocation delivery : splitResult.deliveryAllocations()) {
            if (delivery.deliveryNetPesewas() > 0) {
                legs.add(Leg.credit(Ledger.code("merchant", delivery.deliveryPartnerId(), "pending", currency), delivery.deliveryNetPesewas()));
            }
        }

        // Credit marketplace commission to enterprise pending bucket
        if (splitResult.totalMarketplaceCommission() > 0) {
            legs.add(Leg.credit(Ledger.code("merchant", request.enterpriseId(), "pending", currency), splitResult.totalMarketplaceCommission()));
        }

        // Credit ELEPAY platform service charge to system revenue
        if (splitResult.totalPlatformRevenue() > 0) {
            legs.add(Leg.credit(Ledger.REVENUE, splitResult.totalPlatformRevenue()));
        }

        // Verify journal balance: sum of legs must strictly be zero
        long sumLegs = legs.stream().mapToLong(Leg::amount).sum();
        if (sumLegs != 0) {
            throw new IllegalStateException("Journal legs are unbalanced: net sum is " + sumLegs);
        }

        // 5. Post to authoritative ledger (atomic, locked in ID order, idempotent per scope/key)
        Map<String, String> meta = new TreeMap<>(request.metadata());
        meta.put("orderReference", request.orderReference());
        meta.put("externalPaymentReference", request.externalPaymentReference());
        meta.put("enterpriseId", request.enterpriseId().toString());
        meta.put("paymentChannel", request.paymentChannel());
        meta.put("totalPesewas", String.valueOf(request.totalPesewas()));

        Ledger.Posting posting = Ledger.Posting.of(
                "enterprise-checkout",
                request.idempotencyKey(),
                "enterprise_checkout_external_ingest",
                legs
        ).describedAs("External checkout ingest: " + request.orderReference() + " via " + request.paymentChannel())
         .withMetadata(meta);

        Ledger.Posted posted = ledger.post(posting);

        UUID checkoutId = UUID.nameUUIDFromBytes((request.enterpriseId() + ":" + request.orderReference()).getBytes());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        EnterpriseCheckoutResult result = new EnterpriseCheckoutResult(
                checkoutId,
                posted.transactionId(),
                request.idempotencyKey(),
                request.enterpriseId(),
                request.orderReference(),
                request.externalPaymentReference(),
                request.paymentChannel(),
                currency,
                request.totalPesewas(),
                "PROCESSED",
                now,
                splitResult,
                legs,
                posted.replay()
        );

        checkoutsById.put(checkoutId, result);
        checkoutsByOrderRef.put(request.orderReference(), result);

        return result;
    }

    /**
     * Calculates a preview of the split without executing ledger movements.
     */
    public EnterpriseSplitResult calculateSplitPreview(EnterpriseSplitRequest request) {
        return splitter.split(request);
    }

    /**
     * Looks up an ingested checkout by its internal UUID.
     */
    public Optional<EnterpriseCheckoutResult> findCheckoutById(UUID checkoutId) {
        return Optional.ofNullable(checkoutsById.get(checkoutId));
    }

    /**
     * Looks up an ingested checkout by external order reference.
     */
    public Optional<EnterpriseCheckoutResult> findCheckoutByOrderReference(String orderReference) {
        return Optional.ofNullable(checkoutsByOrderRef.get(orderReference));
    }

    private static String sanitizeCode(String input) {
        if (input == null || input.isBlank()) return "external";
        return input.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }
}
