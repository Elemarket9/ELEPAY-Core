package com.elepay.ledger;

import static com.elepay.ledger.Ledger.Leg.credit;
import static com.elepay.ledger.Ledger.Leg.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.common.FinancialTxRetry;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.ledger.Ledger.Posting;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerStripingIT extends PostgresTestBase {

    @Autowired Ledger ledger;
    @Autowired FinancialTxRetry txRetry;
    @Autowired TransactionTemplate tx;

    @Test
    void aggregateBalanceRawBalanceStripeBalancesAndDebitAcrossStripes() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String clearing = Ledger.clearing("sandbox");
        String baseRev = "system:revenue:test-" + runId + ":GHS";

        // Open legacy base account + 3 distinct stripes
        ledger.openSystem(baseRev, "revenue", Ledger.GHS, false);
        List<UUID> keys = distinctStripeKeys(3);
        String s1 = ledger.openStripedSystem(baseRev, "revenue", Ledger.GHS, false, keys.get(0));
        String s2 = ledger.openStripedSystem(baseRev, "revenue", Ledger.GHS, false, keys.get(1));
        String s3 = ledger.openStripedSystem(baseRev, "revenue", Ledger.GHS, false, keys.get(2));

        ledger.post(Posting.of("stripe-it-" + runId, "seed-base", "seed",
                List.of(debit(clearing, 1_000L), credit(baseRev, 1_000L))));
        ledger.post(Posting.of("stripe-it-" + runId, "seed-s1", "seed",
                List.of(debit(clearing, 2_000L), credit(s1, 2_000L))));
        ledger.post(Posting.of("stripe-it-" + runId, "seed-s2", "seed",
                List.of(debit(clearing, 3_000L), credit(s2, 3_000L))));
        ledger.post(Posting.of("stripe-it-" + runId, "seed-s3", "seed",
                List.of(debit(clearing, 4_000L), credit(s3, 4_000L))));

        assertThat(ledger.rawBalance(baseRev)).isEqualTo(1_000L);
        assertThat(ledger.rawBalance(s1)).isEqualTo(2_000L);
        assertThat(ledger.rawBalance(s2)).isEqualTo(3_000L);
        assertThat(ledger.rawBalance(s3)).isEqualTo(4_000L);
        assertThat(ledger.balance(baseRev)).isEqualTo(10_000L);

        Map<String, Long> perStripe = ledger.stripeBalances(baseRev);
        assertThat(perStripe).hasSize(4);
        assertThat(perStripe.get(baseRev)).isEqualTo(1_000L);
        assertThat(perStripe.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(10_000L);

        // Insufficient funds when total across base + all stripes is less than requested
        assertThatThrownBy(() -> ledger.debitAcrossStripes(baseRev, 10_001L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo("insufficient_funds");

        // Drain 6,500 across legacy base + stripes in deterministic code order
        String recipient = ledger.open("merchant", UUID.randomUUID(), "available", Ledger.GHS);
        List<Leg> debitLegs = ledger.debitAcrossStripes(baseRev, 6_500L);
        assertThat(debitLegs.size()).isGreaterThanOrEqualTo(2);
        assertThat(debitLegs.get(0).account()).isEqualTo(baseRev);
        assertThat(debitLegs.get(0).amount()).isEqualTo(-1_000L);

        List<Leg> payoutLegs = new ArrayList<>(debitLegs);
        payoutLegs.add(credit(recipient, 6_500L));
        ledger.post(Posting.of("stripe-it-" + runId, "drain", "payout", payoutLegs));

        assertThat(ledger.rawBalance(baseRev)).isEqualTo(0L);
        assertThat(ledger.balance(baseRev)).isEqualTo(3_500L);
        assertThat(ledger.balance(recipient)).isEqualTo(6_500L);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void openStripedSystemCacheDoesNotPoisonOnTransactionRollback() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String baseClearing = "system:clearing:rb-" + runId + ":GHS";
        UUID routingKey = UUID.randomUUID();
        String expectedStripe = ledger.stripedCode(baseClearing, routingKey);

        tx.executeWithoutResult(status -> {
            String opened = ledger.openStripedSystem(baseClearing, "clearing", Ledger.GHS, true, routingKey);
            assertThat(opened).isEqualTo(expectedStripe);
            status.setRollbackOnly();
        });

        // Row must not exist in DB after rollback
        assertThat(ledger.account(expectedStripe)).isEmpty();

        // Subsequent committed call must re-insert the row (not be skipped by poisoned cache)
        String reopened = ledger.openStripedSystem(baseClearing, "clearing", Ledger.GHS, true, routingKey);
        assertThat(reopened).isEqualTo(expectedStripe);
        assertThat(ledger.account(expectedStripe)).isPresent();

        String merchant = ledger.open("merchant", UUID.randomUUID(), "pending", Ledger.GHS);
        ledger.post(Posting.of("rb-test-" + runId, "post-after-rb", "checkout_payment",
                List.of(debit(reopened, 500L), credit(merchant, 500L))));
        assertThat(ledger.rawBalance(reopened)).isEqualTo(-500L);
        assertThat(ledger.balance(baseClearing)).isEqualTo(-500L);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void highConcurrencyMultiMerchantCheckoutsDistributeAcrossStripesWithZeroDrift() throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        int merchantCount = 24;
        int checkoutsPerMerchant = 10;
        int totalCheckouts = merchantCount * checkoutsPerMerchant; // 240 concurrent checkouts

        long grossPerCheckout = 10_500L;
        long merchantNet = 9_800L;
        long deferredFee = 200L;
        long customerFee = 500L;

        List<UUID> merchants = new ArrayList<>();
        for (int m = 0; m < merchantCount; m++) {
            UUID merchantId = UUID.randomUUID();
            merchants.add(merchantId);
            ledger.open("merchant", merchantId, "pending", Ledger.GHS);
        }

        String clearingBase = Ledger.clearing("mtn");
        long clearingBefore = ledger.balance(clearingBase);
        long deferredBefore = ledger.balance(Ledger.DEFERRED_REVENUE);
        long revenueBefore = ledger.balance(Ledger.REVENUE);
        Map<String, Long> clearingStripesBefore = ledger.stripeBalances(clearingBase);
        Map<String, Long> revenueStripesBefore = ledger.stripeBalances(Ledger.REVENUE);

        CountDownLatch ready = new CountDownLatch(merchantCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(merchantCount);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(merchantCount)) {
            for (int m = 0; m < merchantCount; m++) {
                final UUID merchantId = merchants.get(m);
                final int worker = m;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(15, TimeUnit.SECONDS);
                        for (int i = 0; i < checkoutsPerMerchant; i++) {
                            UUID checkoutId = UUID.randomUUID();
                            final int iter = i;
                            txRetry.execute(6, () -> {
                                String clearingStripe = ledger.clearingStripe("mtn", checkoutId);
                                String deferredStripe = ledger.deferredRevenueStripe(Ledger.GHS, checkoutId);
                                String revenueStripe = ledger.revenueStripe(Ledger.GHS, checkoutId);
                                String merchantPending = Ledger.code("merchant", merchantId, "pending", Ledger.GHS);
                                return ledger.post(Posting.of(
                                        "stress-striped-" + runId,
                                        "m" + worker + "-c" + iter,
                                        "checkout_payment",
                                        List.of(
                                                debit(clearingStripe, grossPerCheckout),
                                                credit(merchantPending, merchantNet),
                                                credit(deferredStripe, deferredFee),
                                                credit(revenueStripe, customerFee)
                                        )));
                            });
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(errors).isEmpty();

        // Verify exact aggregate balances
        assertThat(ledger.balance(clearingBase)).isEqualTo(clearingBefore - totalCheckouts * grossPerCheckout);
        assertThat(ledger.balance(Ledger.DEFERRED_REVENUE)).isEqualTo(deferredBefore + totalCheckouts * deferredFee);
        assertThat(ledger.balance(Ledger.REVENUE)).isEqualTo(revenueBefore + totalCheckouts * customerFee);

        // Verify every merchant received exact pending balance
        for (UUID merchantId : merchants) {
            assertThat(ledger.balance(Ledger.code("merchant", merchantId, "pending", Ledger.GHS)))
                    .isEqualTo(checkoutsPerMerchant * merchantNet);
        }

        // Verify load was genuinely distributed across many :sNN stripes (not concentrated on a single row)
        Map<String, Long> clearingStripesAfter = ledger.stripeBalances(clearingBase);
        Map<String, Long> revenueStripesAfter = ledger.stripeBalances(Ledger.REVENUE);
        long changedClearingStripes = clearingStripesAfter.entrySet().stream()
                .filter(e -> Ledger.isStripeCode(e.getKey()))
                .filter(e -> !e.getValue().equals(clearingStripesBefore.getOrDefault(e.getKey(), 0L)))
                .count();
        long changedRevenueStripes = revenueStripesAfter.entrySet().stream()
                .filter(e -> Ledger.isStripeCode(e.getKey()))
                .filter(e -> !e.getValue().equals(revenueStripesBefore.getOrDefault(e.getKey(), 0L)))
                .count();
        assertThat(changedClearingStripes).isGreaterThanOrEqualTo(20);
        assertThat(changedRevenueStripes).isGreaterThanOrEqualTo(20);

        // Verify multi-stripe revenue payout (like AgentCommissionEngine) succeeds across striped revenue
        long payoutAmount = 50_000L;
        String agentFloat = ledger.open("merchant", UUID.randomUUID(), "available", Ledger.GHS);
        List<Leg> commissionLegs = new ArrayList<>(ledger.debitAcrossStripes(Ledger.REVENUE, payoutAmount));
        commissionLegs.add(credit(agentFloat, payoutAmount));
        ledger.post(Posting.of("stress-striped-" + runId, "agent-comm", "agent_commission_payout", commissionLegs));

        assertThat(ledger.balance(agentFloat)).isEqualTo(payoutAmount);
        assertThat(ledger.balance(Ledger.REVENUE))
                .isEqualTo(revenueBefore + totalCheckouts * customerFee - payoutAmount);

        assertThat(ledger.integrity().ok()).isTrue();
    }

    private List<UUID> distinctStripeKeys(int count) {
        List<UUID> keys = new ArrayList<>();
        Set<Integer> indices = new HashSet<>();
        while (keys.size() < count) {
            UUID candidate = UUID.randomUUID();
            int idx = ledger.stripeIndex(candidate);
            if (indices.add(idx)) {
                keys.add(candidate);
            }
        }
        return keys;
    }
}
