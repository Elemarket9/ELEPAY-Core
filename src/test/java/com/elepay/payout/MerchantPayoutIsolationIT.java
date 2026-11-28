package com.elepay.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.enterprise.accounts.EnterpriseManagedAccountService;
import com.elepay.enterprise.checkout.CalculatedMerchantAllocation;
import com.elepay.enterprise.payout.EnterpriseWithdrawalRequest;
import com.elepay.enterprise.payout.ExternalWalletWithdrawalEngine;
import com.elepay.enterprise.payout.WithdrawalDestinationType;
import com.elepay.enterprise.settlement.ConditionalSettlementReleaseEngine;
import com.elepay.enterprise.settlement.DeliveryEscrowService;
import com.elepay.enterprise.treasury.EnterpriseTreasuryService;
import com.elepay.ledger.Ledger;
import com.elepay.rails.SimulatedRail;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Adversarial isolation proof for ELEPAY's settlement and payout flow against real PostgreSQL.
 *
 * <p>Proves that Merchant A's payouts can NEVER draw from Merchant B's funds under:
 * <ul>
 *   <li>High-concurrency withdrawal races (distinct keys and identical idempotency keys)</li>
 *   <li>Concurrent retry storms and cross-merchant retry/destination tampering</li>
 *   <li>Payout reversals (rail returns) with duplicate callbacks and concurrent re-withdrawals</li>
 *   <li>Enterprise settlement batch cross-tenant merchant targeting &amp; real-DB external withdrawal retry</li>
 *   <li>Concurrent enterprise treasury prefunding reservations</li>
 * </ul>
 */
class MerchantPayoutIsolationIT extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired SimulatedRail simulated;
    @Autowired ConditionalSettlementReleaseEngine settlementEngine;
    @Autowired DeliveryEscrowService escrowService;
    @Autowired EnterpriseManagedAccountService enterpriseService;
    @Autowired EnterpriseTreasuryService treasuryService;
    @Autowired ExternalWalletWithdrawalEngine externalWithdrawalEngine;

    private TestApi api;
    private String ops;
    private String ownerA;
    private String ownerB;
    private UUID merchantA;
    private UUID merchantB;
    private UUID destASucceeds;
    private UUID destAFails;
    private UUID destAReturns;
    private UUID destBSucceeds;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("iso-ops-" + s, "ops");
        ownerA = api.token("iso-owner-a-" + s);
        ownerB = api.token("iso-owner-b-" + s);

        merchantA = createApprovedMerchant(ownerA, "Merchant A " + s);
        merchantB = createApprovedMerchant(ownerB, "Merchant B " + s);

        destASucceeds = insertVerifiedDestination(merchantA, "momo", "mtn", "233245550005", "Merchant A MoMo");
        destAFails = insertVerifiedDestination(merchantA, "momo", "telecel", "233205550001", "Merchant A Declined");
        destAReturns = insertVerifiedDestination(merchantA, "momo", "mtn", "233245550006", "Merchant A Returnable");
        destBSucceeds = insertVerifiedDestination(merchantB, "momo", "mtn", "233245550005", "Merchant B MoMo");
    }

    private UUID createApprovedMerchant(String ownerToken, String name) {
        var created = api.call("POST", "/v1/merchants", ownerToken, null, Map.of(
                "legalName", name + " Ltd",
                "tradingName", name,
                "kind", "merchant",
                "contactPhone", TestApi.uniquePhone()));
        assertThat(created.status()).isEqualTo(200);
        UUID id = UUID.fromString(created.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + id + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0)).status())
                .isEqualTo(200);
        return id;
    }

    private UUID insertVerifiedDestination(UUID merchantId, String type, String network, String accountNumber, String accountName) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO settlement_destination
                    (id, owner_type, owner_id, type, network, account_number, account_name,
                     verification, verified_name, status, created_by, cooling_off_until)
                VALUES (:id, 'merchant', :m, :t, :n, :a, :name, 'verified', :name, 'active', 'test', now() - interval '1 hour')""")
                .param("id", id).param("m", merchantId).param("t", type)
                .param("n", network).param("a", accountNumber).param("name", accountName).update();
        return id;
    }

    private void fundMerchant(UUID merchantId, long amount) {
        ledger.post(Ledger.Posting.of("test-iso-fund", UUID.randomUUID().toString(), "merchant_funding", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), amount),
                Ledger.Leg.credit(Ledger.code("merchant", merchantId, "available", Ledger.GHS), amount))));
    }

    private long available(UUID merchantId) {
        return ledger.balance(Ledger.code("merchant", merchantId, "available", Ledger.GHS));
    }

    private long reserved(UUID merchantId) {
        return ledger.balance(Ledger.code("merchant", merchantId, "reserved", Ledger.GHS));
    }

    private long payoutJournalEntriesFor(UUID merchantId) {
        return db.sql("""
                SELECT count(*)
                FROM journal_entry e
                JOIN journal_transaction t ON t.id = e.transaction_id
                JOIN ledger_account a ON a.id = e.account_id
                WHERE a.owner_type = 'merchant' AND a.owner_id = :m
                  AND t.kind IN ('payout_reservation', 'payout_completion', 'payout_release', 'payout_return')""")
                .param("m", merchantId).query(Long.class).single();
    }

    private String railRef(String payoutRef) {
        return db.sql("SELECT t.reference FROM payout p JOIN rail_transaction t ON t.id = p.rail_tx WHERE p.reference = :r")
                .param("r", payoutRef).query(String.class).single();
    }

    private int sendSignedCallback(String railRef, String status) {
        var signed = simulated.callback(railRef, status, null);
        return api.http.post().uri("/v1/rails/sandbox/callbacks")
                .header("x-sandbox-signature", signed.getKey())
                .header("Content-Type", "application/json")
                .body(signed.getValue())
                .exchange((req, res) -> res.getStatusCode().value());
    }

    @Test
    @DisplayName("Concurrent payouts for Merchant A serialize cleanly and never draw from Merchant B's funds")
    void concurrentPayoutsForMerchantACannotDrawMerchantBFunds() throws Exception {
        fundMerchant(merchantA, 10_000L);
        fundMerchant(merchantB, 100_000L);

        int threads = 12;
        long eachAmount = 6_000L; // Only 1 of 12 can fit in Merchant A's 10,000 balance
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        var res = api.call("POST", "/v1/merchants/" + merchantA + "/withdrawals",
                                ownerA, "conc-w-" + UUID.randomUUID(),
                                Map.of("amount", eachAmount, "destinationId", destASucceeds));
                        if (res.status() == 200) {
                            succeeded.incrementAndGet();
                        } else if (res.status() == 409 && "insufficient_funds".equals(res.body().path("code").asString())) {
                            insufficient.incrementAndGet();
                        } else {
                            unexpected.add("status=" + res.status() + " body=" + res.body());
                        }
                    } catch (Throwable t) {
                        unexpected.add(t.toString());
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).isEmpty();
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(threads - 1);

        // Settle rail transactions
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);

        // Merchant A spent exactly 6,000 -> 4,000 left; Merchant B completely untouched at 100,000
        assertThat(available(merchantA)).isEqualTo(4_000L);
        assertThat(reserved(merchantA)).isZero();
        assertThat(available(merchantB)).isEqualTo(100_000L);
        assertThat(reserved(merchantB)).isZero();
        assertThat(payoutJournalEntriesFor(merchantB)).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("Concurrent identical idempotency keys, retry storms, and cross-merchant tampering never use Merchant B's funds")
    void concurrentIdempotencyAndRetryStormCannotTouchMerchantB() throws Exception {
        fundMerchant(merchantA, 10_000L);
        fundMerchant(merchantB, 100_000L);

        // 1. Cross-merchant destination tampering: Merchant A cannot withdraw to Merchant B's destination
        var crossDest = api.call("POST", "/v1/merchants/" + merchantA + "/withdrawals",
                ownerA, "cross-dest-" + UUID.randomUUID(),
                Map.of("amount", 1_000L, "destinationId", destBSucceeds));
        assertThat(crossDest.status()).isEqualTo(403);
        assertThat(crossDest.body().get("code").asString()).isEqualTo("destination_not_allowlisted");

        // 2. 8 concurrent identical-key withdrawal requests for Merchant A (8,000 each) -> only 1 debit of 8,000
        String sharedKey = "idem-shared-" + UUID.randomUUID();
        int sameKeyThreads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(sameKeyThreads);
        CountDownLatch ready = new CountDownLatch(sameKeyThreads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> batchRefs = ConcurrentHashMap.newKeySet();
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger inProgressCount = new AtomicInteger();
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < sameKeyThreads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        var res = api.call("POST", "/v1/merchants/" + merchantA + "/withdrawals",
                                ownerA, sharedKey,
                                Map.of("amount", 8_000L, "destinationId", destASucceeds));
                        if (res.status() == 200) {
                            okCount.incrementAndGet();
                            batchRefs.add(res.body().get("reference").asString());
                        } else if (res.status() == 409 && "idempotency_in_progress".equals(res.body().path("code").asString())) {
                            inProgressCount.incrementAndGet();
                        } else {
                            unexpected.add("status=" + res.status() + " body=" + res.body());
                        }
                    } catch (Throwable t) {
                        unexpected.add(t.toString());
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).isEmpty();
        assertThat(okCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(okCount.get() + inProgressCount.get()).isEqualTo(sameKeyThreads);
        assertThat(batchRefs).hasSize(1);

        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        assertThat(available(merchantA)).isEqualTo(2_000L);
        assertThat(reserved(merchantA)).isZero();

        // 3. Declined payout for Merchant A (1,500) -> fails & releases back to 2,000; then 6 concurrent retry storms
        var failedWithdrawal = api.call("POST", "/v1/merchants/" + merchantA + "/withdrawals",
                ownerA, "fail-w-" + UUID.randomUUID(),
                Map.of("amount", 1_500L, "destinationId", destAFails));
        assertThat(failedWithdrawal.status()).isEqualTo(200);
        String failedPayoutRef = failedWithdrawal.body().get("items").get(0).get("reference").asString();
        assertThat(failedWithdrawal.body().get("items").get(0).get("status").asString()).isEqualTo("failed");
        assertThat(available(merchantA)).isEqualTo(2_000L);

        // Merchant B cannot retry Merchant A's failed payout
        var crossRetry = api.call("POST", "/v1/merchants/" + merchantB + "/payouts/" + failedPayoutRef + "/retry",
                ownerB, "cross-retry-" + UUID.randomUUID(), null);
        assertThat(crossRetry.status()).isEqualTo(404);

        // 6 concurrent retries by Merchant A with distinct keys -> at most 1 retry created
        int retryThreads = 6;
        ExecutorService retryPool = Executors.newFixedThreadPool(retryThreads);
        CountDownLatch rReady = new CountDownLatch(retryThreads);
        CountDownLatch rStart = new CountDownLatch(1);
        AtomicInteger retrySucceeded = new AtomicInteger();
        AtomicInteger alreadyRetried = new AtomicInteger();
        List<String> retryUnexpected = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < retryThreads; i++) {
                futures.add(retryPool.submit(() -> {
                    rReady.countDown();
                    try {
                        rStart.await();
                        var res = api.call("POST", "/v1/merchants/" + merchantA + "/payouts/" + failedPayoutRef + "/retry",
                                ownerA, "retry-storm-" + UUID.randomUUID(), null);
                        if (res.status() == 200) {
                            retrySucceeded.incrementAndGet();
                        } else if (res.status() == 409 && "already_retried".equals(res.body().path("code").asString())) {
                            alreadyRetried.incrementAndGet();
                        } else {
                            retryUnexpected.add("status=" + res.status() + " body=" + res.body());
                        }
                    } catch (Throwable t) {
                        retryUnexpected.add(t.toString());
                    }
                }));
            }
            assertThat(rReady.await(10, TimeUnit.SECONDS)).isTrue();
            rStart.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            retryPool.shutdownNow();
        }

        assertThat(retryUnexpected).isEmpty();
        assertThat(retrySucceeded.get()).isEqualTo(1);
        assertThat(alreadyRetried.get()).isEqualTo(retryThreads - 1);

        // Merchant A's balance is exact 2,000; Merchant B is completely untouched at 100,000
        assertThat(available(merchantA)).isEqualTo(2_000L);
        assertThat(reserved(merchantA)).isZero();
        assertThat(available(merchantB)).isEqualTo(100_000L);
        assertThat(reserved(merchantB)).isZero();
        assertThat(payoutJournalEntriesFor(merchantB)).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("Payout reversal (return) with duplicate callbacks and concurrent re-withdrawals credits Merchant A once and never touches Merchant B")
    void payoutReversalWithDuplicateCallbacksAndConcurrentWithdrawalsIsolatesMerchants() throws Exception {
        fundMerchant(merchantA, 10_000L);
        fundMerchant(merchantB, 100_000L);

        // Merchant A withdraws 9,000 -> succeeds (available = 1,000)
        var initial = api.call("POST", "/v1/merchants/" + merchantA + "/withdrawals",
                ownerA, "init-ret-" + UUID.randomUUID(),
                Map.of("amount", 9_000L, "destinationId", destAReturns));
        assertThat(initial.status()).isEqualTo(200);
        String payoutRef = initial.body().get("items").get(0).get("reference").asString();
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        assertThat(available(merchantA)).isEqualTo(1_000L);
        assertThat(reserved(merchantA)).isZero();

        // Trigger rail return (reversal) and hammer with 4 concurrent duplicate return callbacks + 4 concurrent 9,000 withdrawals
        String rRef = railRef(payoutRef);
        simulated.returnPayout(rRef);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger newWithdrawalsSucceeded = new AtomicInteger();
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();
            // 4 duplicate return callbacks
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        int st = sendSignedCallback(rRef, "returned");
                        if (st != 200) unexpected.add("callback status=" + st);
                    } catch (Throwable t) {
                        unexpected.add("cb: " + t);
                    }
                }));
            }
            // 4 concurrent re-withdrawal attempts of 9,000 each
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        var res = api.call("POST", "/v1/merchants/" + merchantA + "/withdrawals",
                                ownerA, "post-ret-" + UUID.randomUUID(),
                                Map.of("amount", 9_000L, "destinationId", destASucceeds));
                        if (res.status() == 200) {
                            newWithdrawalsSucceeded.incrementAndGet();
                        } else if (!(res.status() == 409 && "insufficient_funds".equals(res.body().path("code").asString()))) {
                            unexpected.add("w status=" + res.status() + " body=" + res.body());
                        }
                    } catch (Throwable t) {
                        unexpected.add("w: " + t);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).isEmpty();
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);

        // Return credited at most once (+9,000 -> 10,000), so at most 1 new 9,000 withdrawal could succeed
        assertThat(newWithdrawalsSucceeded.get()).isBetween(0, 1);
        long expectedA = 10_000L - (newWithdrawalsSucceeded.get() * 9_000L);
        assertThat(available(merchantA)).isEqualTo(expectedA);
        assertThat(reserved(merchantA)).isZero();

        // Merchant B is 100% untouched
        assertThat(available(merchantB)).isEqualTo(100_000L);
        assertThat(reserved(merchantB)).isZero();
        assertThat(payoutJournalEntriesFor(merchantB)).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("Enterprise payout batch blocks cross-enterprise merchant targeting, external retry resolves real PO ref, and treasury reserve serializes")
    void enterpriseIsolationRetryAndTreasuryConcurrency() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        var ent1 = enterpriseService.registerEnterprise("ENT1-" + s, "Enterprise One", "e1@test.gh", "+233240001001", null);
        var ent2 = enterpriseService.registerEnterprise("ENT2-" + s, "Enterprise Two", "e2@test.gh", "+233240001002", null);

        // 1. Enterprise 1 tries to include merchantB (which does not belong to Enterprise 1) in executeMerchantPayoutBatch
        fundMerchant(merchantB, 50_000L);
        assertThatThrownBy(() -> settlementEngine.executeMerchantPayoutBatch(ent1.id(), List.of(merchantB), "GHS"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not associated with enterprise");
        assertThat(available(merchantB)).isEqualTo(50_000L);
        assertThat(reserved(merchantB)).isZero();

        // Also register an escrow item for merchantA under ent1, verify ent2 still cannot target merchantA
        escrowService.registerOrderItems(ent1.id(), "ORD-" + s,
                List.of(new CalculatedMerchantAllocation(0, merchantA, "M-A", "GENERAL", "item-1", 10_000L, 1_000L, 0L, 0L, 9_000L, true)),
                "GHS", null);
        assertThatThrownBy(() -> settlementEngine.executeMerchantPayoutBatch(ent2.id(), List.of(merchantA), "GHS"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not associated with enterprise");

        // 2. ExternalWalletWithdrawalEngine real-DB retry uses real payout.reference (PO-...) and preserves idempotency
        ledger.open("merchant", ent1.id(), "available", Ledger.GHS);
        ledger.open("merchant", ent1.id(), "reserved", Ledger.GHS);
        ledger.post(Ledger.Posting.of("ent-fund", "ent1-" + s, "enterprise_funding", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 50_000L),
                Ledger.Leg.credit(Ledger.code("merchant", ent1.id(), "available", Ledger.GHS), 50_000L))));

        // 0205550001 is declined by SimulatedRail so the payout transitions to 'failed' and funds release back to available
        var req = new EnterpriseWithdrawalRequest(
                "ewd-idem-" + s, ent1.id(), "partner-" + s, "cust-1", null,
                "Kofi Mensah", "GHA-111", 1, 10_000L, "GHS",
                WithdrawalDestinationType.MOMO, "telecel", "0205550001", Map.of());
        var first = externalWithdrawalEngine.requestWithdrawal(req);
        assertThat(first.payoutId()).isNotNull();

        // Retry against real PostgreSQL + real Payouts (previously failed with 409/404 because it passed EWD-... instead of PO-...)
        String retryKey = "ewd-retry-" + s;
        var retried = externalWithdrawalEngine.retryWithdrawal(first.withdrawalReference(), retryKey);
        assertThat(retried.retryOf()).isEqualTo(first.withdrawalId());
        assertThat(retried.payoutId()).isNotNull().isNotEqualTo(first.payoutId());

        // Replaying the same retry key returns the same retry record without double-creating
        var replayedRetry = externalWithdrawalEngine.retryWithdrawal(first.withdrawalReference(), retryKey);
        assertThat(replayedRetry.withdrawalId()).isEqualTo(retried.withdrawalId());

        // 3. Concurrent EnterpriseTreasuryService.reserveFunding serializes under FOR UPDATE and never over-reserves
        treasuryService.depositPrefunding(ent2.id(), 10_000L, "GHS", "DEP-CONC-" + s, "Concurrent reserve test");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reservedOk = new AtomicInteger();
        AtomicInteger reservedRejected = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        treasuryService.reserveFunding(ent2.id(), 6_000L, "GHS");
                        reservedOk.incrementAndGet();
                    } catch (ApiException e) {
                        if ("insufficient_prefunding".equals(e.code())) {
                            reservedRejected.incrementAndGet();
                        } else {
                            throw e;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reservedOk.get()).isEqualTo(1);
        assertThat(reservedRejected.get()).isEqualTo(5);
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
