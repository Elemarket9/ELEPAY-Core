package com.elepay.enterprise.refund;

import com.elepay.common.ApiException;
import com.elepay.enterprise.checkout.EnterpriseCheckoutResult;
import com.elepay.enterprise.checkout.EnterpriseCheckoutService;
import com.elepay.enterprise.wallet.ExternalWalletConnector;
import com.elepay.enterprise.wallet.ExternalWalletEngine;
import com.elepay.enterprise.wallet.ExternalWalletRefundRequest;
import com.elepay.enterprise.wallet.ExternalWalletRefundResult;
import com.elepay.enterprise.wallet.RefundAllocationItem;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Enterprise Refund Orchestration Service.
 *
 * <p>Coordinates full and item-level refunds, multi-merchant reversals,
 * commission clawbacks, tax/delivery adjustments, double-entry ledger postings,
 * external wallet connector instructions (Mode A enterprise-owned wallets), and refund liability tracking.
 */
@Service
public class EnterpriseRefundService {
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private static final int LOCK_STRIPES = 256;

    private final Ledger ledger;
    private final EnterpriseItemRefundCalculator refundCalculator;
    private final EnterpriseRefundLiabilityTracker liabilityTracker;
    private final EnterpriseCheckoutService checkoutService;
    private final ExternalWalletEngine walletEngine;
    private final JdbcClient db;

    // Bounded LRU caches (prevent unbounded heap growth / OOM at 1M+ refunds)
    private final Map<UUID, EnterpriseRefundResult> refundsById = boundedLru(MAX_CACHE_ENTRIES);
    private final Map<String, EnterpriseRefundResult> refundsByTenantIdempotency = boundedLru(MAX_CACHE_ENTRIES);
    private final Map<String, EnterpriseRefundResult> refundsByIdempotency = boundedLru(MAX_CACHE_ENTRIES);
    private final Map<String, OrderRefundState> orderRefundStates = boundedLru(MAX_CACHE_ENTRIES);
    private final ReentrantLock[] orderLocks = initLocks();

    private static <K, V> Map<K, V> boundedLru(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    private static ReentrantLock[] initLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private ReentrantLock lockForOrder(String key) {
        return orderLocks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    private static String tenantIdemKey(UUID enterpriseId, String idempotencyKey) {
        return enterpriseId + ":" + idempotencyKey;
    }

    private record OrderRefundState(
            UUID enterpriseId,
            String orderReference,
            long originalGrossPesewas,
            long cumulativeRefundedPesewas
    ) {}

    public EnterpriseRefundService(
            Ledger ledger,
            EnterpriseItemRefundCalculator refundCalculator,
            EnterpriseRefundLiabilityTracker liabilityTracker,
            EnterpriseCheckoutService checkoutService,
            ExternalWalletEngine walletEngine,
            JdbcClient db
    ) {
        this.ledger = Objects.requireNonNull(ledger, "ledger cannot be null");
        this.refundCalculator = Objects.requireNonNull(refundCalculator, "refundCalculator cannot be null");
        this.liabilityTracker = Objects.requireNonNull(liabilityTracker, "liabilityTracker cannot be null");
        this.checkoutService = Objects.requireNonNull(checkoutService, "checkoutService cannot be null");
        this.walletEngine = walletEngine; // optional in minimal contexts
        this.db = db;
    }

    /**
     * Registers or updates an order's original gross amount for refund tracking.
     */
    public void registerOrderGross(UUID enterpriseId, String orderReference, long grossPesewas) {
        if (grossPesewas <= 0) {
            throw new IllegalArgumentException("Original gross must be positive: " + grossPesewas);
        }
        orderRefundStates.put(orderKey(enterpriseId, orderReference),
                new OrderRefundState(enterpriseId, orderReference, grossPesewas, 0L));

        if (db != null) {
            db.sql("""
                INSERT INTO enterprise_order_refund_aggregate
                (id, enterprise_id, order_reference, original_gross_pesewas, cumulative_refunded_pesewas, status)
                VALUES (:id, :eid, :oref, :gross, 0, 'ACTIVE')
                ON CONFLICT (enterprise_id, order_reference) DO UPDATE
                SET original_gross_pesewas = EXCLUDED.original_gross_pesewas
                """)
                .param("id", UUID.randomUUID())
                .param("eid", enterpriseId)
                .param("oref", orderReference)
                .param("gross", grossPesewas)
                .update();
        }
    }

    /**
     * Executes an item-level or full enterprise refund.
     */
    @Transactional
    public EnterpriseRefundResult processRefund(EnterpriseItemRefundRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        // 1. Idempotency Check (tenant-scoped in-memory cache, then authoritative DB)
        String scopedIdemKey = tenantIdemKey(request.enterpriseId(), request.idempotencyKey());
        EnterpriseRefundResult existing = refundsByTenantIdempotency.get(scopedIdemKey);
        if (existing == null && db != null) {
            existing = findRefundInDbByIdempotencyKey(request.enterpriseId(), request.idempotencyKey()).orElse(null);
            if (existing != null) {
                refundsById.put(existing.refundId(), existing);
                refundsByTenantIdempotency.put(scopedIdemKey, existing);
                refundsByIdempotency.put(request.idempotencyKey(), existing);
            }
        }
        if (existing != null) {
            if (!existing.enterpriseId().equals(request.enterpriseId())
                    || !existing.orderReference().equals(request.orderReference())
                    || existing.totalRefundPesewas() != request.totalRefundPesewas()
                    || existing.destination() != request.destination()) {
                throw ApiException.conflict("idempotency_conflict",
                        "Idempotency conflict for key: " + request.idempotencyKey());
            }
            return new EnterpriseRefundResult(
                    existing.refundId(),
                    existing.refundReference(),
                    existing.idempotencyKey(),
                    existing.enterpriseId(),
                    existing.orderReference(),
                    existing.externalCaptureId(),
                    existing.externalCustomerId(),
                    existing.totalRefundPesewas(),
                    existing.currency(),
                    existing.destination(),
                    existing.status(),
                    existing.ledgerTransactionId(),
                    existing.connectorRefundId(),
                    existing.calculatedItems(),
                    existing.deliveryAdjustment(),
                    existing.totalMerchantDebitPesewas(),
                    existing.totalCommissionClawbackPesewas(),
                    existing.totalPlatformFeeClawbackPesewas(),
                    existing.totalReserveReleasePesewas(),
                    existing.totalDeliveryReversalPesewas(),
                    true,
                    existing.createdAt(),
                    existing.completedAt()
            );
        }

        ReentrantLock lock = lockForOrder(orderKey(request.enterpriseId(), request.orderReference()));
        lock.lock();
        try {
            return doProcessRefund(request);
        } finally {
            lock.unlock();
        }
    }

    private EnterpriseRefundResult doProcessRefund(EnterpriseItemRefundRequest request) {
        // 2. Cumulative Refund Tracking & Limit Enforcement with Pessimistic Row Locking
        long totalRefundRequested = request.totalRefundPesewas();
        if (totalRefundRequested <= 0) {
            throw ApiException.badRequest("invalid_amount", "Total refund amount must be positive");
        }

        long originalGross = resolveOriginalGross(request.enterpriseId(), request.orderReference());
        long currentCumulative = 0L;

        if (db != null) {
            // Ensure aggregate record exists so FOR UPDATE can acquire row-level lock
            long insertGross = originalGross > 0 ? originalGross : Math.max(1L, totalRefundRequested);
            db.sql("""
                INSERT INTO enterprise_order_refund_aggregate
                (id, enterprise_id, order_reference, original_gross_pesewas, cumulative_refunded_pesewas, status)
                VALUES (:id, :eid, :oref, :gross, 0, 'ACTIVE')
                ON CONFLICT (enterprise_id, order_reference) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("eid", request.enterpriseId())
                .param("oref", request.orderReference())
                .param("gross", insertGross)
                .update();

            // Acquire exclusive row-level lock on the aggregate
            var locked = db.sql("""
                SELECT original_gross_pesewas, cumulative_refunded_pesewas
                FROM enterprise_order_refund_aggregate
                WHERE enterprise_id = :eid AND order_reference = :oref
                FOR UPDATE
                """)
                .param("eid", request.enterpriseId())
                .param("oref", request.orderReference())
                .query((rs, rowNum) -> new long[] {
                        rs.getLong("original_gross_pesewas"),
                        rs.getLong("cumulative_refunded_pesewas")
                })
                .optional().orElse(null);

            if (locked != null) {
                if (locked[0] > 0) {
                    originalGross = locked[0];
                }
                currentCumulative = locked[1];
            }
        } else {
            // In-memory fallback
            OrderRefundState state = orderRefundStates.get(orderKey(request.enterpriseId(), request.orderReference()));
            if (state != null) {
                if (state.originalGrossPesewas() > 0) {
                    originalGross = state.originalGrossPesewas();
                }
                currentCumulative = state.cumulativeRefundedPesewas();
            }
        }

        if (originalGross > 0 && Math.addExact(currentCumulative, totalRefundRequested) > originalGross) {
            throw ApiException.conflict("refund_limit_exceeded",
                    "Cumulative refund (" + (currentCumulative + totalRefundRequested)
                            + ") exceeds original captured amount (" + originalGross + ")");
        }

        // 3. Calculate Item Reversals and Financial Conservation
        List<CalculatedItemRefund> calculatedItems = refundCalculator.calculate(request.items());

        long sumItemsGross = calculatedItems.stream().mapToLong(CalculatedItemRefund::grossRefundPesewas).reduce(0L, Math::addExact);
        long totalMerchantDebit = calculatedItems.stream().mapToLong(CalculatedItemRefund::merchantReversalPesewas).reduce(0L, Math::addExact);
        long totalCommissionClawback = calculatedItems.stream().mapToLong(CalculatedItemRefund::commissionReversalPesewas).reduce(0L, Math::addExact);
        long totalPlatformFeeClawback = calculatedItems.stream().mapToLong(CalculatedItemRefund::platformFeeReversalPesewas).reduce(0L, Math::addExact);
        long totalReserveRelease = calculatedItems.stream().mapToLong(CalculatedItemRefund::reserveReversalPesewas).reduce(0L, Math::addExact);

        long deliveryReversal = 0L;
        if (request.deliveryAdjustment() != null) {
            DeliveryRefundSpecification del = request.deliveryAdjustment();
            deliveryReversal = del.deliveryGrossPesewas();
            totalCommissionClawback = Math.addExact(totalCommissionClawback, del.marketplaceFeeReversalPesewas());
            totalPlatformFeeClawback = Math.addExact(totalPlatformFeeClawback, del.platformFeeReversalPesewas());
        }

        long calculatedTotal = Math.addExact(sumItemsGross, deliveryReversal);
        if (calculatedTotal != totalRefundRequested) {
            throw new IllegalStateException("Calculated total (" + calculatedTotal
                    + ") does not match requested total (" + totalRefundRequested + ")");
        }

        // 4. Construct Double-Entry Journal Legs
        String currency = request.currency();
        List<Leg> legs = new ArrayList<>();

        // Debits from sub-merchants (pending, reserve, and tax reversal)
        for (CalculatedItemRefund item : calculatedItems) {
            ledger.open("merchant", item.merchantId(), "pending", currency);
            if (item.merchantReversalPesewas() > 0) {
                legs.add(Leg.debit(Ledger.code("merchant", item.merchantId(), "pending", currency), item.merchantReversalPesewas()));
            }
            if (item.reserveReversalPesewas() > 0) {
                ledger.open("merchant", item.merchantId(), "reserved", currency);
                legs.add(Leg.debit(Ledger.code("merchant", item.merchantId(), "reserved", currency), item.reserveReversalPesewas()));
            }
            if (item.taxReversalPesewas() > 0) {
                legs.add(Leg.debit(Ledger.code("merchant", item.merchantId(), "pending", currency), item.taxReversalPesewas()));
            }
        }

        // Debit from delivery partner if delivery adjusted
        if (request.deliveryAdjustment() != null) {
            UUID delPartnerId = request.deliveryAdjustment().deliveryPartnerId();
            ledger.open("merchant", delPartnerId, "pending", currency);
            if (request.deliveryAdjustment().deliveryNetReversalPesewas() > 0) {
                legs.add(Leg.debit(Ledger.code("merchant", delPartnerId, "pending", currency),
                        request.deliveryAdjustment().deliveryNetReversalPesewas()));
            }
        }

        // Debit marketplace commission from enterprise pending bucket
        ledger.open("merchant", request.enterpriseId(), "pending", currency);
        if (totalCommissionClawback > 0) {
            legs.add(Leg.debit(Ledger.code("merchant", request.enterpriseId(), "pending", currency), totalCommissionClawback));
        }

        UUID checkoutId = UUID.nameUUIDFromBytes((request.enterpriseId() + ":" + request.orderReference()).getBytes());

        // Debit platform service fee from platform revenue account (currency-parameterized)
        String revenueBase = "system:revenue:" + currency;
        if (totalPlatformFeeClawback > 0) {
            ledger.openStripedSystem(revenueBase, "revenue", currency, false, checkoutId);
            legs.addAll(ledger.debitStripeOrAcrossStripes(revenueBase, checkoutId, totalPlatformFeeClawback));
        }

        // Credit destination
        String destinationAccount;
        switch (request.destination()) {
            case EXTERNAL_WALLET -> {
                String connectorId = request.destinationDetails() == null ? null : request.destinationDetails().get("connectorId");
                if (connectorId == null || connectorId.isBlank()) {
                    throw ApiException.badRequest("connector_required",
                            "destinationDetails.connectorId is required for an external wallet refund");
                }
                String channel = sanitizeCode(connectorId);
                destinationAccount = ledger.openStripedSystem("system:clearing:" + channel + ":" + currency, "clearing", currency, true, checkoutId);
            }
            case DOMESTIC_MOMO -> {
                String network = request.destinationDetails() == null
                        ? "mtn"
                        : request.destinationDetails().getOrDefault("network", "mtn");
                destinationAccount = ledger.openStripedSystem("system:clearing:" + network.toLowerCase() + ":" + currency, "clearing", currency, true, checkoutId);
            }
            case BANK_ACCOUNT -> {
                destinationAccount = ledger.openStripedSystem("system:clearing:bank:" + currency, "clearing", currency, true, checkoutId);
            }
            case INTERNAL_WALLET -> {
                UUID customerUuid = UUID.nameUUIDFromBytes(request.externalCustomerId().getBytes());
                destinationAccount = ledger.open("enterprise_managed", customerUuid, "available", currency);
            }
            default -> throw new IllegalArgumentException("Unsupported destination: " + request.destination());
        }

        legs.add(Leg.credit(destinationAccount, totalRefundRequested));

        // Invariant verification: sum of debit legs + credit leg must strictly equal zero
        long sumLegs = legs.stream().mapToLong(Leg::amount).sum();
        if (sumLegs != 0) {
            throw new IllegalStateException("Double-entry legs are unbalanced: net sum is " + sumLegs);
        }

        // 5. Post to Authoritative Double-Entry Ledger
        Map<String, String> meta = new TreeMap<>(request.metadata());
        meta.put("orderReference", request.orderReference());
        meta.put("refundDestination", request.destination().name());
        meta.put("externalCustomerId", request.externalCustomerId());
        meta.put("totalRefundPesewas", String.valueOf(totalRefundRequested));

        Ledger.Posting posting = Ledger.Posting.of(
                "enterprise-refund:" + request.enterpriseId(),
                request.idempotencyKey(),
                "enterprise_item_refund",
                legs
        ).describedAs("Item refund for order: " + request.orderReference() + " to " + request.destination())
         .withMetadata(meta);

        Ledger.Posted posted = ledger.post(posting);

        // 6. External wallet connector call (Mode A refundToWallet through the enterprise's registered connector)
        String connectorRefundId = null;
        if (!posted.replay() && request.destination() == RefundDestination.EXTERNAL_WALLET && walletEngine != null && request.externalCaptureId() != null) {
            // The enterprise must name its wallet connector explicitly; no partner is assumed by default.
            String connectorId = request.destinationDetails() == null ? null : request.destinationDetails().get("connectorId");
            if (connectorId == null || connectorId.isBlank()) {
                throw ApiException.badRequest("connector_required",
                        "destinationDetails.connectorId is required for an external wallet refund");
            }
            Optional<ExternalWalletConnector> connectorOpt = walletEngine.getConnector(connectorId);
            if (connectorOpt.isEmpty()) {
                // Fail closed: rolls back the refund posting rather than recording an unconfirmed wallet credit.
                throw ApiException.badRequest("connector_not_registered",
                        "No external wallet connector registered with id: " + connectorId);
            }
            {
                ExternalWalletConnector connector = connectorOpt.get();
                List<RefundAllocationItem> connectorItems = new ArrayList<>();
                for (CalculatedItemRefund cItem : calculatedItems) {
                    connectorItems.add(new RefundAllocationItem(
                            cItem.merchantId().toString(),
                            cItem.itemReference(),
                            cItem.grossRefundPesewas(),
                            cItem.commissionReversalPesewas(),
                            cItem.reason()
                    ));
                }
                if (request.deliveryAdjustment() != null) {
                    DeliveryRefundSpecification del = request.deliveryAdjustment();
                    connectorItems.add(new RefundAllocationItem(
                            del.deliveryPartnerId().toString(),
                            "DELIVERY",
                            del.deliveryGrossPesewas(),
                            del.marketplaceFeeReversalPesewas(),
                            del.reason()
                    ));
                }

                ExternalWalletRefundRequest connReq = new ExternalWalletRefundRequest(
                        request.idempotencyKey(),
                        connectorId,
                        request.externalCaptureId(),
                        request.orderReference(),
                        request.externalCustomerId(),
                        totalRefundRequested,
                        currency,
                        request.reason() != null ? request.reason() : "Item refund",
                        connectorItems
                );
                ExternalWalletRefundResult connResult = connector.refundToWallet(connReq);
                if (connResult != null) {
                    connectorRefundId = connResult.refundId();
                }
            }
        }

        UUID refundId = UUID.randomUUID();
        String refundRef = "ERF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 7. Persist refund header & items before recording liability rows (FK: enterprise_refund_liability.refund_id -> enterprise_refund.id)
        if (db != null) {
            db.sql("""
                INSERT INTO enterprise_refund
                (id, enterprise_id, idempotency_key, refund_reference, order_reference, external_capture_id,
                 external_customer_id, currency, total_refund_pesewas, refund_destination, status,
                 ledger_tx_id, connector_refund_id, reason, created_at, completed_at)
                VALUES
                (:id, :eid, :ikey, :ref, :oref, :capid, :cid, :cur, :total, :dest, 'COMPLETED',
                 :txid, :crid, :reason, :now, :now)
                """)
                .param("id", refundId)
                .param("eid", request.enterpriseId())
                .param("ikey", request.idempotencyKey())
                .param("ref", refundRef)
                .param("oref", request.orderReference())
                .param("capid", request.externalCaptureId())
                .param("cid", request.externalCustomerId())
                .param("cur", currency)
                .param("total", totalRefundRequested)
                .param("dest", request.destination().name())
                .param("txid", posted.transactionId())
                .param("crid", connectorRefundId)
                .param("reason", request.reason())
                .param("now", now)
                .update();

            for (CalculatedItemRefund item : calculatedItems) {
                db.sql("""
                    INSERT INTO enterprise_refund_item
                    (id, refund_id, item_reference, merchant_id, item_gross_pesewas, merchant_reversal_pesewas,
                     commission_reversal_pesewas, platform_fee_reversal_pesewas, reserve_reversal_pesewas,
                     tax_reversal_pesewas, reason, created_at)
                    VALUES
                    (:id, :rid, :iref, :mid, :gross, :mrev, :crev, :prev, :rrev, :trev, :reason, :now)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("rid", refundId)
                    .param("iref", item.itemReference())
                    .param("mid", item.merchantId())
                    .param("gross", item.grossRefundPesewas())
                    .param("mrev", item.merchantReversalPesewas())
                    .param("crev", item.commissionReversalPesewas())
                    .param("prev", item.platformFeeReversalPesewas())
                    .param("rrev", item.reserveReversalPesewas())
                    .param("trev", item.taxReversalPesewas())
                    .param("reason", item.reason())
                    .param("now", now)
                    .update();
            }
        }

        // 8. Record Refund Liabilities for Sub-Merchants (skip on ledger replay to prevent double-recording)
        if (!posted.replay()) {
            for (CalculatedItemRefund cItem : calculatedItems) {
                if (cItem.merchantReversalPesewas() > 0) {
                    liabilityTracker.recordLiability(
                            request.enterpriseId(),
                            cItem.merchantId(),
                            refundId,
                            cItem.merchantReversalPesewas()
                    );
                }
            }

            // 9. Update Order Cumulative Refund State
            long newCumulative = Math.addExact(currentCumulative, totalRefundRequested);
            updateCumulativeRefund(request.enterpriseId(), request.orderReference(), originalGross, newCumulative);
        }

        EnterpriseRefundResult result = new EnterpriseRefundResult(
                refundId,
                refundRef,
                request.idempotencyKey(),
                request.enterpriseId(),
                request.orderReference(),
                request.externalCaptureId(),
                request.externalCustomerId(),
                totalRefundRequested,
                currency,
                request.destination(),
                RefundStatus.COMPLETED,
                posted.transactionId(),
                connectorRefundId,
                calculatedItems,
                request.deliveryAdjustment(),
                totalMerchantDebit,
                totalCommissionClawback,
                totalPlatformFeeClawback,
                totalReserveRelease,
                deliveryReversal,
                posted.replay(),
                now,
                now
        );

        Runnable cachePut = () -> {
            refundsById.put(refundId, result);
            refundsByTenantIdempotency.put(tenantIdemKey(request.enterpriseId(), request.idempotencyKey()), result);
            refundsByIdempotency.put(request.idempotencyKey(), result);
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cachePut.run();
                }
            });
        } else {
            cachePut.run();
        }

        return result;
    }

    public Optional<EnterpriseRefundResult> findRefundById(UUID refundId) {
        return Optional.ofNullable(refundsById.get(refundId));
    }

    public Optional<EnterpriseRefundResult> findRefundByIdempotencyKey(UUID enterpriseId, String idempotencyKey) {
        if (enterpriseId == null || idempotencyKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(refundsByTenantIdempotency.get(tenantIdemKey(enterpriseId, idempotencyKey)));
    }

    public Optional<EnterpriseRefundResult> findRefundByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(refundsByIdempotency.get(idempotencyKey));
    }

    private Optional<EnterpriseRefundResult> findRefundInDbByIdempotencyKey(UUID enterpriseId, String idempotencyKey) {
        var rowOpt = db.sql("""
            SELECT id, enterprise_id, idempotency_key, refund_reference, order_reference, external_capture_id,
                   external_customer_id, currency, total_refund_pesewas, refund_destination, status,
                   ledger_tx_id, connector_refund_id, created_at, completed_at
            FROM enterprise_refund
            WHERE enterprise_id = :eid AND idempotency_key = :ikey
            """)
            .param("eid", enterpriseId)
            .param("ikey", idempotencyKey)
            .query((rs, rowNum) -> new Object[] {
                    rs.getObject("id", UUID.class),
                    rs.getObject("enterprise_id", UUID.class),
                    rs.getString("idempotency_key"),
                    rs.getString("refund_reference"),
                    rs.getString("order_reference"),
                    rs.getString("external_capture_id"),
                    rs.getString("external_customer_id"),
                    rs.getString("currency").trim(),
                    rs.getLong("total_refund_pesewas"),
                    rs.getString("refund_destination"),
                    rs.getString("status"),
                    rs.getObject("ledger_tx_id", UUID.class),
                    rs.getString("connector_refund_id"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("completed_at", OffsetDateTime.class)
            })
            .optional();

        if (rowOpt.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rowOpt.get();
        UUID refundId = (UUID) r[0];
        List<CalculatedItemRefund> items = db.sql("""
            SELECT item_reference, merchant_id, item_gross_pesewas, merchant_reversal_pesewas,
                   commission_reversal_pesewas, platform_fee_reversal_pesewas, reserve_reversal_pesewas,
                   tax_reversal_pesewas, reason
            FROM enterprise_refund_item
            WHERE refund_id = :rid
            ORDER BY created_at ASC
            """)
            .param("rid", refundId)
            .query((rs, rowNum) -> new CalculatedItemRefund(
                    rs.getString("item_reference"),
                    rs.getObject("merchant_id", UUID.class),
                    rs.getLong("item_gross_pesewas"),
                    rs.getLong("merchant_reversal_pesewas"),
                    rs.getLong("commission_reversal_pesewas"),
                    rs.getLong("platform_fee_reversal_pesewas"),
                    rs.getLong("reserve_reversal_pesewas"),
                    rs.getLong("tax_reversal_pesewas"),
                    rs.getString("reason")
            ))
            .list();

        long itemsGross = items.stream().mapToLong(CalculatedItemRefund::grossRefundPesewas).sum();
        long totalMerchantDebit = items.stream().mapToLong(CalculatedItemRefund::merchantReversalPesewas).sum();
        long totalCommissionClawback = items.stream().mapToLong(CalculatedItemRefund::commissionReversalPesewas).sum();
        long totalPlatformFeeClawback = items.stream().mapToLong(CalculatedItemRefund::platformFeeReversalPesewas).sum();
        long totalReserveRelease = items.stream().mapToLong(CalculatedItemRefund::reserveReversalPesewas).sum();
        long totalRefund = (long) r[8];
        long deliveryReversal = Math.max(0L, totalRefund - itemsGross);

        return Optional.of(new EnterpriseRefundResult(
                refundId,
                (String) r[3],
                (String) r[2],
                (UUID) r[1],
                (String) r[4],
                (String) r[5],
                (String) r[6],
                totalRefund,
                (String) r[7],
                RefundDestination.valueOf((String) r[9]),
                RefundStatus.valueOf((String) r[10]),
                (UUID) r[11],
                (String) r[12],
                items,
                null,
                totalMerchantDebit,
                totalCommissionClawback,
                totalPlatformFeeClawback,
                totalReserveRelease,
                deliveryReversal,
                true,
                (OffsetDateTime) r[13],
                (OffsetDateTime) r[14]
        ));
    }

    private long resolveOriginalGross(UUID enterpriseId, String orderReference) {
        // First check in-memory state
        OrderRefundState state = orderRefundStates.get(orderKey(enterpriseId, orderReference));
        if (state != null) {
            return state.originalGrossPesewas();
        }

        // Check EnterpriseCheckoutService
        Optional<EnterpriseCheckoutResult> checkoutOpt = checkoutService.findCheckoutByOrderReference(orderReference);
        if (checkoutOpt.isPresent()) {
            long total = checkoutOpt.get().totalPesewas();
            orderRefundStates.put(orderKey(enterpriseId, orderReference),
                    new OrderRefundState(enterpriseId, orderReference, total, 0L));
            return total;
        }

        // Check DB
        if (db != null) {
            Long dbGross = db.sql("""
                SELECT original_gross_pesewas FROM enterprise_order_refund_aggregate
                WHERE enterprise_id = :eid AND order_reference = :oref
                """)
                .param("eid", enterpriseId)
                .param("oref", orderReference)
                .query(Long.class)
                .optional().orElse(null);
            if (dbGross != null) {
                return dbGross;
            }
        }

        return 0L;
    }

    public long resolveCumulativeRefunded(UUID enterpriseId, String orderReference) {
        if (db != null) {
            Long dbCum = db.sql("""
                SELECT cumulative_refunded_pesewas FROM enterprise_order_refund_aggregate
                WHERE enterprise_id = :eid AND order_reference = :oref
                """)
                .param("eid", enterpriseId)
                .param("oref", orderReference)
                .query(Long.class)
                .optional().orElse(null);
            if (dbCum != null) {
                return dbCum;
            }
        }

        OrderRefundState state = orderRefundStates.get(orderKey(enterpriseId, orderReference));
        if (state != null) {
            return state.cumulativeRefundedPesewas();
        }

        return 0L;
    }

    private void updateCumulativeRefund(UUID enterpriseId, String orderReference, long originalGross, long newCumulative) {
        orderRefundStates.put(orderKey(enterpriseId, orderReference),
                new OrderRefundState(enterpriseId, orderReference, originalGross, newCumulative));

        if (db != null) {
            String status = (originalGross > 0 && newCumulative >= originalGross) ? "FULLY_REFUNDED" : "PARTIALLY_REFUNDED";
            db.sql("""
                UPDATE enterprise_order_refund_aggregate
                SET cumulative_refunded_pesewas = :cum,
                    status = :status,
                    updated_at = now()
                WHERE enterprise_id = :eid AND order_reference = :oref
                """)
                .param("cum", newCumulative)
                .param("status", status)
                .param("eid", enterpriseId)
                .param("oref", orderReference)
                .update();
        }
    }

    private static String orderKey(UUID enterpriseId, String orderReference) {
        return enterpriseId + ":" + orderReference;
    }

    private static String sanitizeCode(String input) {
        if (input == null || input.isBlank()) return "external";
        return input.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }
}
