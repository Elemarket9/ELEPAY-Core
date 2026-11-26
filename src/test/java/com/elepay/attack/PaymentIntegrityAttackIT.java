package com.elepay.attack;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.ledger.Ledger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Attack class B — payment integrity. Negative / zero / non-finite amounts, pesewa overflow,
 * idempotent double-spend and payout-retry abuse must all be rejected with no ledger drift.
 */
class PaymentIntegrityAttackIT extends AttackSupport {

    private static final String ALLOWED_IP = "203.0.113.10";

    private String payoutKey() {
        return createKey(merchant, owner, Map.of("label", "payout", "keyType", "payout",
                "allowedIps", List.of(ALLOWED_IP + "/32")));
    }

    @Test
    @DisplayName("B1: negative, zero and non-finite amounts on charge (checkout) and payout (withdrawal) are rejected")
    void nonSensicalAmountsRejected() {
        UUID dest = addReadyDestination(merchant, owner, "0245550005");
        String idem = UUID.randomUUID().toString();

        // --- charge side: checkout creation ---
        var negCharge = api.call("POST", "/v1/merchants/" + merchant + "/checkouts", owner, idem + "-a",
                Map.of("amount", -5000, "description", "neg"));
        assertThat(negCharge.status()).isEqualTo(400);

        var zeroCharge = api.call("POST", "/v1/merchants/" + merchant + "/checkouts", owner, idem + "-b",
                Map.of("amount", 0, "description", "zero"));
        assertThat(zeroCharge.status()).isEqualTo(400);

        // A numeric amount that overflows the signed 64-bit pesewa field cannot be coerced and is refused
        // (never silently truncated or wrapped to a finite amount).
        var overflowAmount = api.call("POST", "/v1/merchants/" + merchant + "/checkouts", owner, idem + "-c",
                Map.of("amount", new java.math.BigInteger("9223372036854775808"), "description", "overflow"));
        assertThat(overflowAmount.status()).isGreaterThanOrEqualTo(400);

        // --- payout side: withdrawal ---
        var negWithdraw = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/withdrawals", payoutKey(),
                ALLOWED_IP, UUID.randomUUID().toString(), Map.of("amount", -5000, "destinationId", dest));
        assertThat(negWithdraw.status()).isEqualTo(400);

        var zeroWithdraw = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/withdrawals", payoutKey(),
                ALLOWED_IP, UUID.randomUUID().toString(), Map.of("amount", 0, "destinationId", dest));
        assertThat(zeroWithdraw.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("B2: pesewa overflow (amounts near Long.MAX) is rejected via Math.addExact with no wraparound and no ledger movement")
    void pesewaOverflowRejected() {
        long funded = 100_000L;
        fundMerchantAvailable(merchant, funded);
        addReadyDestination(merchant, owner, "0245550005");
        long before = ledger.balance(Ledger.code("merchant", merchant, "available", "GHS"));

        // Two positive items that individually pass @Positive but whose sum overflows a signed 64-bit total.
        var res = api.call("POST", "/v1/merchants/" + merchant + "/payout-batches", owner,
                UUID.randomUUID().toString(), Map.of("items", List.of(
                        Map.of("reference", "ovf-1", "amount", Long.MAX_VALUE, "type", "momo", "network", "mtn",
                                "accountNumber", "0245550005", "accountName", "Verified Recipient"),
                        Map.of("reference", "ovf-2", "amount", Long.MAX_VALUE, "type", "momo", "network", "mtn",
                                "accountNumber", "0245550005", "accountName", "Verified Recipient"))));

        // The request must fail (never a 2xx that silently wrapped to a small/negative total).
        assertThat(res.status()).isGreaterThanOrEqualTo(400);
        // No wraparound: balance untouched and the double-entry ledger still balances.
        assertThat(ledger.balance(Ledger.code("merchant", merchant, "available", "GHS"))).isEqualTo(before);
        assertThat(ledger.balance(Ledger.code("merchant", merchant, "reserved", "GHS"))).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("B3: idempotent double-spend — the SAME idempotency key fired concurrently produces exactly one ledger effect")
    void sameIdempotencyKeyIsSingleEffect() throws Exception {
        long funded = 100_000L;
        long amount = 20_000L;
        fundMerchantAvailable(merchant, funded);
        UUID dest = addReadyDestination(merchant, owner, "0245550005");
        String key = payoutKey();
        String sharedIdem = "double-spend-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("amount", amount, "destinationId", dest);

        int threads = 16;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    gate.await();
                    var r = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/withdrawals", key, ALLOWED_IP,
                            sharedIdem, body);
                    if (r.status() == 200) ok.incrementAndGet();
                    return null;
                });
            }
            List<Future<Void>> futures = new ArrayList<>();
            for (var t : tasks) futures.add(pool.submit(t));
            gate.countDown();
            for (var f : futures) f.get();
        }

        // Every request that returned 200 returned the SAME replayed batch, so the money left available
        // exactly once. Exactly one payout row exists for this idempotent withdrawal.
        assertThat(ok.get()).isGreaterThanOrEqualTo(1);
        long withdrawalRows = db.sql("""
                SELECT count(*) FROM payout WHERE owner_type = 'merchant' AND owner_id = :m
                  AND client_reference = :cref""")
                .param("m", merchant).param("cref", "withdrawal-" + sharedIdem).query(Long.class).single();
        assertThat(withdrawalRows).isEqualTo(1L);
        assertThat(ledger.balance(Ledger.code("merchant", merchant, "available", "GHS"))).isEqualTo(funded - amount);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("B4: payout retry invariant — a payout cannot be retried more than its single allowed retry")
    void payoutCannotBeRetriedTwice() {
        fundMerchantAvailable(merchant, 100_000L);
        // Destination msisdn ending in 1 -> the simulated rail DECLINES the payout, so it ends 'failed'
        // (and is therefore retry-eligible). The account still name-verifies (only trailing 0/9 do not).
        UUID failDest = addReadyDestination(merchant, owner, "0245550001");
        String key = payoutKey();

        var withdraw = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/withdrawals", key, ALLOWED_IP,
                UUID.randomUUID().toString(), Map.of("amount", 10_000, "destinationId", failDest));
        assertThat(withdraw.status()).as(withdraw.body().toString()).isEqualTo(200);
        // Drive any pending rail outcomes to final so the payout settles to 'failed'.
        api.call("POST", "/v1/ops/rails/recover", ops1, null, null);

        String reference = withdraw.body().get("items").get(0).get("reference").asString();
        var failed = api.call("GET", "/v1/merchants/" + merchant + "/payouts/" + reference, owner, null, null);
        assertThat(failed.body().get("status").asString()).isEqualTo("failed");

        // First retry is allowed.
        var retry1 = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/payouts/" + reference + "/retry", key,
                ALLOWED_IP, UUID.randomUUID().toString(), Map.of());
        assertThat(retry1.status()).as(retry1.body().toString()).isEqualTo(200);

        // A second retry of the SAME original is refused — a recipient can never be paid twice for one item.
        var retry2 = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/payouts/" + reference + "/retry", key,
                ALLOWED_IP, UUID.randomUUID().toString(), Map.of());
        assertThat(retry2.status()).isEqualTo(409);
        assertThat(retry2.body().get("code").asString()).isEqualTo("already_retried");
    }
}
