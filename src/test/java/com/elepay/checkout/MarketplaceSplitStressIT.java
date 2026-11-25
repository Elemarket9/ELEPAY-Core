package com.elepay.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Adversarial and randomized checks of the marketplace split engine through its HTTP entry points: every
 * pesewa is conserved across random orders, commission rules, reserves and partial refunds; refunds unwind
 * fees exactly; concurrent refunds and payments cannot over-refund or double-charge; disputes and refunds
 * cannot return more than a line; and invalid orders are refused cleanly.
 */
class MarketplaceSplitStressIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;

    TestApi api;
    String ops, opsAdmin, mpOwner, customerToken;
    UUID marketplace, customer, rider;
    final List<UUID> customers = new ArrayList<>();
    final List<UUID> sellers = new ArrayList<>();
    final Map<UUID, String> owners = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + s, "ops");
        opsAdmin = api.token("opsadmin-" + s, "ops_admin");
        mpOwner = api.token("mp-" + s);
        marketplace = merchant(mpOwner, "Market " + s, "marketplace", 0);
        int[] fees = {150, 100, 175};
        for (int i = 0; i < 3; i++) {
            String o = api.token("seller" + i + "-" + s);
            UUID id = merchant(o, "Seller " + i + " " + s, "merchant", fees[i]);
            join(id, o);
            sellers.add(id);
            owners.put(id, o);
        }
        String ro = api.token("rider-" + s);
        rider = merchant(ro, "Rider " + s, "delivery", 50);
        join(rider, ro);
        owners.put(rider, ro);
        var grid = api.call("PUT", "/v1/merchants/" + marketplace + "/fee-schedule", mpOwner, null, Map.of(
                "deliveryConfirmDays", 14, "changeReason", "stress test",
                "rules", List.of(
                        Map.of("category", "fashion", "commissionBps", 1000, "commissionMin", 0, "processingFee", 100),
                        Map.of("category", "beauty", "commissionBps", 777, "commissionMin", 50, "processingFee", 37),
                        Map.of("category", "electronics", "commissionBps", 333, "commissionMin", 0, "processingFee", 0, "reserveBps", 1000),
                        Map.of("category", "*", "commissionBps", 500, "commissionMin", 0, "processingFee", 0))));
        assertThat(grid.status()).as(String.valueOf(grid.body())).isEqualTo(200);
        newCustomer();
    }

    UUID merchant(String owner, String name, String kind, int feeBps) {
        var created = api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", name + " Ltd",
                "tradingName", name, "kind", kind, "contactPhone", "0241234567"));
        UUID id = UUID.fromString(created.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + id + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", feeBps, "feeFixed", 0, "settlementDelayHours", 0));
        return id;
    }

    void join(UUID seller, String owner) {
        String code = api.call("GET", "/v1/merchants/" + seller, owner, null, null).body().get("merchantCode").asString();
        api.call("POST", "/v1/merchants/" + marketplace + "/sellers", mpOwner, null, Map.of("merchantCode", code));
        api.call("POST", "/v1/merchants/" + seller + "/marketplaces/" + marketplace + "/accept", owner, null, null);
    }

    /** Tier-3 KYC so volume is not stopped by the tier-1 wallet limits, which are tested elsewhere. */
    void newCustomer() {
        String subject = "customer-" + UUID.randomUUID().toString().substring(0, 8);
        customerToken = api.token(subject);
        customer = api.enroll(ops, subject, TestApi.uniquePhone(), "Ama Customer");
        db.sql("UPDATE customer SET kyc_tier = 3 WHERE id = :id").param("id", customer).update();
        customers.add(customer);
    }

    long bal(String type, UUID id, String bucket) { return ledger.balance(Ledger.code(type, id, bucket, "GHS")); }
    long held(UUID id) { return bal("merchant", id, "pending") + bal("merchant", id, "reserved") + bal("merchant", id, "available"); }

    /** Every account money from these tests can sit in. Its change must equal what was funded in. */
    long everyoneHolds() {
        long t = held(marketplace);
        for (UUID c : customers) t += bal("customer", c, "available");
        for (UUID id : owners.keySet()) t += held(id);
        return t + ledger.balance(Ledger.REVENUE) + ledger.balance(Ledger.DEFERRED_REVENUE)
                + ledger.balance(Ledger.REVENUE_EARNED) + ledger.balance(Ledger.REFUNDS_IN_FLIGHT);
    }

    long fund(long needed) {
        long amount = needed - bal("customer", customer, "available"), funded = Math.max(0, amount);
        while (amount > 0) {
            long chunk = Math.min(amount, 500_000);
            var r = api.call("POST", "/v1/wallet/topups", customerToken, "fund-" + UUID.randomUUID(),
                    Map.of("amount", chunk, "method", "momo", "phone", "0245550005", "network", "mtn"));
            assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
            api.call("POST", "/v1/ops/rails/recover", ops, null, null);
            amount -= chunk;
        }
        return funded;
    }

    record Alloc(UUID merchant, String kind, long amount, String category) {}

    TestApi.Response create(List<Alloc> allocs) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (var a : allocs) {
            Map<String, Object> m = new HashMap<>(Map.of("merchantId", a.merchant(), "kind", a.kind(), "amount", a.amount(), "description", "item"));
            if (a.category() != null) m.put("category", a.category());
            body.add(m);
        }
        return api.call("POST", "/v1/merchants/" + marketplace + "/checkouts", mpOwner, UUID.randomUUID().toString(),
                Map.of("description", "order", "allocations", body));
    }

    String created(List<Alloc> allocs) {
        var r = create(allocs);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        return r.body().get("reference").asString();
    }

    long total(String ref) {
        return api.call("GET", "/v1/public/checkouts/" + ref, null, null, null).body().get("methods").get(0).get("total").asLong();
    }

    JsonNode lines(String ref) {
        return api.call("GET", "/v1/merchants/" + marketplace + "/checkouts/" + ref, mpOwner, null, null).body().get("lines");
    }

    TestApi.Response pay(String ref) { return api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", customerToken, UUID.randomUUID().toString(), null); }

    TestApi.Response refund(String ref, int line, long amount) {
        return api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/allocations/" + line + "/refunds",
                mpOwner, UUID.randomUUID().toString(), Map.of("amount", amount, "reason", "stress test"));
    }

    void deliver(String ref) { api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/deliveries", mpOwner, null, Map.of()); }

    @Test
    void randomOrdersRefundsAndSettlementConserveEveryPesewa() {
        var rnd = new Random(20261125L);
        String[] categories = {"fashion", "beauty", "electronics", "groceries", null};
        long baseline = everyoneHolds(), fundedIn = 0;
        int paidOrders = 0;
        for (int c = 0; c < 40; c++) {
            if (c > 0 && c % 8 == 0) newCustomer();
            List<Alloc> allocs = new ArrayList<>();
            for (int i = 0, n = 1 + rnd.nextInt(5); i < n; i++) {
                long amount = switch (rnd.nextInt(4)) {
                    case 0 -> 1 + rnd.nextInt(99);
                    case 1 -> 100 + rnd.nextInt(9_900);
                    default -> 1_000 + rnd.nextInt(39_000);
                };
                allocs.add(new Alloc(sellers.get(rnd.nextInt(3)), "sale", amount, categories[rnd.nextInt(categories.length)]));
            }
            if (rnd.nextBoolean()) allocs.add(new Alloc(rider, "delivery", 500 + rnd.nextInt(3_000), null));
            var r = create(allocs);
            if (r.status() != 200) {
                // The only acceptable refusal for these inputs: commission and fees larger than a tiny item's price.
                assertThat(r.body().path("code").asString()).isEqualTo("fees_exceed_amount");
                continue;
            }
            String ref = r.body().get("reference").asString();
            long total = total(ref);
            fundedIn += fund(total);
            long before = bal("customer", customer, "available");
            assertThat(pay(ref).status()).isEqualTo(200);
            assertThat(before - bal("customer", customer, "available")).isEqualTo(total);
            paidOrders++;
            JsonNode ls = lines(ref);
            for (JsonNode l : ls) {
                assertThat(l.get("net").asLong() + l.get("reserveAmount").asLong() + l.get("fee").asLong()
                        + l.get("commission").asLong() + l.get("processingFee").asLong())
                        .as("line parts add up to its price: " + l).isEqualTo(l.get("amount").asLong());
            }
            for (JsonNode l : ls) {
                if (rnd.nextInt(3) != 0) continue;
                int line = l.get("line").asInt();
                long left = l.get("amount").asLong(), feeBack = 0, platformBack = 0;
                for (int p = 0, pieces = 1 + rnd.nextInt(3); p < pieces && left > 0; p++) {
                    long amount = p == pieces - 1 ? left : Math.max(1, left * (1 + rnd.nextInt(60)) / 100);
                    var refund = refund(ref, line, amount);
                    assertThat(refund.status()).as(String.valueOf(refund.body())).isEqualTo(200);
                    feeBack += refund.body().get("feeReturned").asLong();
                    platformBack += refund.body().get("platformFeeReturned").asLong();
                    left -= amount;
                }
                if (left == 0) {
                    assertThat(feeBack).as("Elepay fee fully returned: " + l).isEqualTo(l.get("fee").asLong());
                    assertThat(platformBack).as("commission fully returned: " + l)
                            .isEqualTo(l.get("commission").asLong() + l.get("processingFee").asLong());
                    assertThat(refund(ref, line, 1).status()).isEqualTo(409);
                }
            }
            if (rnd.nextBoolean()) deliver(ref);
        }
        api.call("POST", "/v1/ops/settlements/run", ops, null, Map.of("ignoreDelay", true));

        assertThat(paidOrders).isGreaterThan(25);
        assertThat(everyoneHolds() - baseline).as("money in equals money held").isEqualTo(fundedIn);
        List<UUID> all = new ArrayList<>(owners.keySet());
        all.add(marketplace);
        for (UUID id : all)
            for (String bucket : List.of("pending", "reserved", "available"))
                assertThat(bal("merchant", id, bucket)).as(id + " " + bucket).isNotNegative();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void simultaneousRefundsNeverReturnMoreThanTheLine() throws Exception {
        String ref = created(List.of(new Alloc(sellers.get(0), "sale", 5_000, "fashion")));
        fund(total(ref));
        assertThat(pay(ref).status()).isEqualTo(200);
        long before = bal("customer", customer, "available");
        var pool = Executors.newFixedThreadPool(12);
        AtomicInteger ok = new AtomicInteger(), refused = new AtomicInteger();
        List<Future<?>> attempts = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            attempts.add(pool.submit(() -> {
                int status = refund(ref, 0, 1_000).status();
                (status == 200 ? ok : refused).incrementAndGet();
            }));
        for (var f : attempts) f.get();
        pool.shutdown();
        assertThat(ok.get()).isEqualTo(5);
        assertThat(refused.get()).isEqualTo(7);
        assertThat(bal("customer", customer, "available") - before).isEqualTo(5_000);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void simultaneousPaymentsChargeOnce() throws Exception {
        String ref = created(List.of(new Alloc(sellers.get(1), "sale", 7_000, "beauty"), new Alloc(rider, "delivery", 1_000, null)));
        long total = total(ref);
        fund(total * 3);
        long before = bal("customer", customer, "available");
        var pool = Executors.newFixedThreadPool(6);
        List<Future<Integer>> taps = new ArrayList<>();
        for (int i = 0; i < 6; i++) taps.add(pool.submit(() -> pay(ref).status()));
        int paid = 0;
        for (var f : taps) if (f.get() == 200) paid++;
        pool.shutdown();
        assertThat(paid).isEqualTo(1);
        assertThat(before - bal("customer", customer, "available")).isEqualTo(total);
    }

    @Test
    void refundAfterSettlementAndDisputeCannotReturnMoreThanTheLine() {
        String ref = created(List.of(new Alloc(sellers.get(0), "sale", 20_000, "fashion"),
                new Alloc(sellers.get(2), "sale", 10_000, "electronics")));
        fund(total(ref));
        assertThat(pay(ref).status()).isEqualTo(200);
        deliver(ref);
        api.call("POST", "/v1/ops/settlements/run", ops, null, Map.of("ignoreDelay", true));
        assertThat(bal("merchant", sellers.get(0), "available")).isPositive();

        // A settled line can still be refunded in full: the seller's available balance pays it back.
        var settledRefund = refund(ref, 0, 20_000);
        assertThat(settledRefund.status()).isEqualTo(200);
        assertThat(bal("merchant", sellers.get(0), "available")).isZero();

        // GHS 60 of a GHS 100 line disputed: only the undisputed GHS 40 can be refunded.
        var dispute = api.call("POST", "/v1/ops/disputes", ops, null,
                Map.of("checkout", ref, "allocationLine", 1, "amount", 6_000, "reason", "not received"));
        assertThat(dispute.status()).isEqualTo(200);
        assertThat(refund(ref, 1, 5_000).status()).isEqualTo(409);
        assertThat(refund(ref, 1, 4_000).status()).isEqualTo(200);
        long before = bal("customer", customer, "available");
        var resolved = api.call("POST", "/v1/ops/disputes/" + dispute.body().get("id").asString() + "/resolve", opsAdmin, null,
                Map.of("outcome", "customer_won", "note", "stress test"));
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(bal("customer", customer, "available") - before).isEqualTo(6_000);
        JsonNode line = lines(ref).get(1);
        assertThat(line.get("refunded").asLong()).isEqualTo(10_000);
        assertThat(line.get("disputed").asLong()).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void invalidOrdersAndRefundsAreRefusedCleanly() {
        assertThat(create(List.of(new Alloc(sellers.get(1), "sale", 1, "beauty"))).status()).isEqualTo(400);
        UUID outsider = merchant(api.token("out-" + UUID.randomUUID()), "Outsider", "merchant", 100);
        assertThat(create(List.of(new Alloc(outsider, "sale", 1_000, "fashion"))).status()).isEqualTo(403);
        assertThat(create(List.of(new Alloc(sellers.get(0), "sale", 0, "fashion"))).status()).isEqualTo(400);
        assertThat(create(List.of(new Alloc(sellers.get(0), "sale", -500, "fashion"))).status()).isEqualTo(400);
        var huge = create(List.of(new Alloc(sellers.get(0), "sale", Long.MAX_VALUE / 2, "fashion")));
        assertThat(huge.status()).isEqualTo(400);
        assertThat(huge.body().path("code").asString()).isEqualTo("amount_too_large");
        List<Alloc> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add(new Alloc(sellers.get(i % 3), "sale", 100, "fashion"));
        assertThat(create(many).status()).isEqualTo(400);

        String ref = created(List.of(new Alloc(sellers.get(0), "sale", 3_000, "fashion"), new Alloc(sellers.get(1), "sale", 3_000, "beauty")));
        fund(total(ref));
        assertThat(pay(ref).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + sellers.get(1) + "/checkouts/" + ref + "/allocations/0/refunds",
                owners.get(sellers.get(1)), UUID.randomUUID().toString(), Map.of("amount", 100, "reason", "x")).status()).isEqualTo(404);
        assertThat(api.call("GET", "/v1/merchants/" + sellers.get(0) + "/checkouts/" + ref, owners.get(sellers.get(0)), null, null)
                .body().get("lines")).hasSize(1);
        assertThat(refund(ref, 0, 0).status()).isEqualTo(400);
        assertThat(refund(ref, 0, 3_001).status()).isEqualTo(409);
        assertThat(api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/deliveries", mpOwner, null, Map.of()).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/deliveries", mpOwner, null, Map.of()).status()).isEqualTo(200);
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
