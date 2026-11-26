package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.common.FinancialTxRetry;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import com.elepay.wallet.Wallets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * P1 #5: High-concurrency stress tests hammering shared clearing and merchant accounts.
 *
 * <p>Verifies under simultaneous multi-thread contention that:
 * <ol>
 *   <li>Zero {@code SQLSTATE 25P02} (aborted transaction) errors and zero unhandled 500s occur.</li>
 *   <li>Global double-entry invariant ({@code SUM(debits) == SUM(credits)} per currency) holds.</li>
 *   <li>Stored account balances match reconstructed journal sums with zero drift.</li>
 * </ol>
 */
class ClearingAccountStressIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired FinancialTxRetry txRetry;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired(required = false) KillSwitchService killSwitchService;

    TestApi api;
    String ops;

    @BeforeEach
    void setUp() {
        if (killSwitchService != null) {
            killSwitchService.setModuleEnabled("consumer-wallet", true, "stress test setup", "test");
            killSwitchService.setModuleEnabled("checkout", true, "stress test setup", "test");
        }
        api = new TestApi(port);
        ops = api.token("stress-ops-" + UUID.randomUUID(), "ops");
    }

    @Test
    void concurrentLedgerPostingsOnSharedClearingAccountPreserveDoubleEntryAndZeroDrift() throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String clearing = Ledger.clearing("sandbox");
        UUID merchantAId = UUID.randomUUID();
        UUID merchantBId = UUID.randomUUID();
        String merchantA = ledger.open("merchant", merchantAId, "available", Ledger.GHS);
        String merchantB = ledger.open("merchant", merchantBId, "available", Ledger.GHS);
        String revenue = Ledger.REVENUE;

        // Seed merchantA and merchantB so opposing-direction postings never hit insufficient_funds
        long seedAmount = 500_000L;
        ledger.post(Ledger.Posting.of("stress-seed-" + runId, "seed-a", "topup", List.of(
                Ledger.Leg.debit(clearing, seedAmount),
                Ledger.Leg.credit(merchantA, seedAmount))));
        ledger.post(Ledger.Posting.of("stress-seed-" + runId, "seed-b", "topup", List.of(
                Ledger.Leg.debit(clearing, seedAmount),
                Ledger.Leg.credit(merchantB, seedAmount))));

        long clearingBefore = ledger.balance(clearing);
        long revenueBefore = ledger.balance(revenue);

        int threads = 16;
        int iterationsPerThread = 10;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final int worker = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(15, TimeUnit.SECONDS);
                        for (int i = 0; i < iterationsPerThread; i++) {
                            final int iter = i;
                            txRetry.execute(6, () -> {
                                String scope = "stress-clearing-" + runId;
                                String key = "w" + worker + "-i" + iter;
                                if (worker % 2 == 0) {
                                    // Collection into merchantA + revenue from shared clearing
                                    return ledger.post(Ledger.Posting.of(scope, key, "stress_collect", List.of(
                                            Ledger.Leg.debit(clearing, 1_000L),
                                            Ledger.Leg.credit(merchantA, 950L),
                                            Ledger.Leg.credit(revenue, 50L))));
                                } else {
                                    // Opposing lock set: debit merchantB, credit shared clearing & merchantA
                                    return ledger.post(Ledger.Posting.of(scope, key, "stress_payout", List.of(
                                            Ledger.Leg.debit(merchantB, 600L),
                                            Ledger.Leg.credit(clearing, 400L),
                                            Ledger.Leg.credit(merchantA, 200L))));
                                }
                            });
                        }
                    } catch (Throwable ex) {
                        errors.add(ex);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(errors)
                .as("No 25P02 or unhandled exceptions under shared clearing contention")
                .isEmpty();

        // 8 even workers * 10 iters: clearing -1000, merchantA +950, revenue +50
        // 8 odd  workers * 10 iters: merchantB -600, clearing +400, merchantA +200
        long evenOps = (threads / 2L) * iterationsPerThread;
        long oddOps = (threads / 2L) * iterationsPerThread;
        assertThat(ledger.balance(clearing)).isEqualTo(clearingBefore - (evenOps * 1_000L) + (oddOps * 400L));
        assertThat(ledger.balance(merchantA)).isEqualTo(seedAmount + (evenOps * 950L) + (oddOps * 200L));
        assertThat(ledger.balance(merchantB)).isEqualTo(seedAmount - (oddOps * 600L));
        assertThat(ledger.balance(revenue)).isEqualTo(revenueBefore + (evenOps * 50L));

        assertGlobalLedgerInvariants();
    }

    @Test
    void concurrentWalletCheckoutPaymentsAndReserveReleasesOnSameSellerHaveZero500sAndZeroDrift() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String mpOwner = api.token("stress-mp-" + s);
        String sellerOwner = api.token("stress-seller-" + s);

        var mpResp = api.call("POST", "/v1/merchants", mpOwner, null, Map.of(
                "legalName", "Stress Market Ltd", "tradingName", "Stress Market",
                "kind", "marketplace", "contactPhone", TestApi.uniquePhone(), "contactEmail", "mp-" + s + "@elepay.gh"));
        assertThat(mpResp.status()).isEqualTo(200);
        UUID mp = UUID.fromString(mpResp.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + mp + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0));

        var sellerResp = api.call("POST", "/v1/merchants", sellerOwner, null, Map.of(
                "legalName", "Stress Seller Ltd", "tradingName", "Stress Seller",
                "kind", "merchant", "contactPhone", TestApi.uniquePhone(), "contactEmail", "sl-" + s + "@elepay.gh"));
        assertThat(sellerResp.status()).isEqualTo(200);
        UUID seller = UUID.fromString(sellerResp.body().get("id").asString());
        String sellerCode = sellerResp.body().get("merchantCode").asString();
        api.call("POST", "/v1/ops/merchants/" + seller + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0));
        assertThat(api.call("POST", "/v1/merchants/" + mp + "/sellers", mpOwner, null,
                Map.of("merchantCode", sellerCode)).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + seller + "/marketplaces/" + mp + "/accept",
                sellerOwner, null, null).status()).isEqualTo(200);

        int concurrency = 12;
        long checkoutAmount = 5_000L;
        int reserveBps = 2000; // 1,000p reserved per checkout
        long reserveAmount = 1_000L;

        record CheckoutJob(String buyerToken, String checkoutRef) {}
        List<CheckoutJob> jobs = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            String sub = "stress-buyer-" + s + "-" + i;
            String buyerToken = api.token(sub);
            UUID buyer = api.enroll(ops, sub, TestApi.uniquePhone(), "Buyer " + i);
            ledger.post(Ledger.Posting.of("stress-fund-" + s, "buyer-" + i, "topup", List.of(
                    Ledger.Leg.debit(Ledger.clearing("sandbox"), 10_000L),
                    Ledger.Leg.credit(Wallets.available(buyer), 10_000L))));

            var created = api.call("POST", "/v1/merchants/" + mp + "/checkouts", mpOwner,
                    UUID.randomUUID().toString(),
                    Map.of("description", "Stress Order " + i, "allocations", List.of(
                            Map.of("merchantId", seller, "kind", "sale", "amount", checkoutAmount,
                                    "reserveBps", reserveBps))));
            assertThat(created.status()).isEqualTo(200);
            jobs.add(new CheckoutJob(buyerToken, created.body().get("reference").asString()));
        }

        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                final CheckoutJob job = jobs.get(i);
                final int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(15, TimeUnit.SECONDS);
                        var paid = api.call("POST", "/v1/checkouts/" + job.checkoutRef() + "/pay/wallet",
                                job.buyerToken(), "pay-" + s + "-" + idx, Map.of());
                        if (paid.status() != 200) {
                            failures.add("pay[" + idx + "] status=" + paid.status() + " body=" + paid.body());
                            return;
                        }
                        var released = api.call("POST",
                                "/v1/merchants/" + mp + "/checkouts/" + job.checkoutRef() + "/allocations/0/release-reserve",
                                ops, "rel-" + s + "-" + idx,
                                Map.of("amount", reserveAmount, "reason", "concurrent stress release"));
                        if (released.status() != 200) {
                            failures.add("release[" + idx + "] status=" + released.status() + " body=" + released.body());
                        }
                    } catch (Throwable ex) {
                        failures.add("worker[" + idx + "] exception=" + ex + " is25P02=" + containsSqlState25P02(ex));
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures)
                .as("Zero HTTP 500s, zero 25P02 errors, and all concurrent wallet payments + reserve releases succeed")
                .isEmpty();

        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", Ledger.GHS))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", seller, "pending", Ledger.GHS)))
                .isEqualTo(concurrency * checkoutAmount);

        assertGlobalLedgerInvariants();
    }

    private void assertGlobalLedgerInvariants() {
        Ledger.Integrity integrity = ledger.integrity();
        assertThat(integrity.accountsWithDrift()).isZero();
        assertThat(integrity.unbalancedTransactions()).isZero();
        assertThat(integrity.ok()).as("Ledger integrity: %s", integrity).isTrue();

        record CurrencyTotals(String currency, long credits, long debits) {}
        List<CurrencyTotals> totals = db.sql("""
                SELECT currency,
                       COALESCE(SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), 0)::bigint AS total_credits,
                       COALESCE(SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END), 0)::bigint AS total_debits
                FROM journal_entry
                GROUP BY currency""")
                .query((rs, i) -> new CurrencyTotals(rs.getString(1), rs.getLong(2), rs.getLong(3)))
                .list();
        assertThat(totals).isNotEmpty();
        for (CurrencyTotals t : totals) {
            assertThat(t.credits())
                    .as("Global double-entry invariant for currency %s (SUM credits == SUM debits)", t.currency())
                    .isEqualTo(t.debits());
        }

        long driftedAccounts = db.sql("""
                SELECT count(*) FROM ledger_account_reconstruction
                WHERE projected_balance <> journal_balance""")
                .query(Long.class).single();
        assertThat(driftedAccounts).isZero();
    }

    private static boolean containsSqlState25P02(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx && "25P02".equals(sqlEx.getSQLState())) {
                return true;
            }
            if (cur.getMessage() != null
                    && (cur.getMessage().contains("25P02") || cur.getMessage().contains("current transaction is aborted"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
