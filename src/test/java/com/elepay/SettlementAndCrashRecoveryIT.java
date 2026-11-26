package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.ledger.Ledger;
import com.elepay.rails.RailTransactions;
import com.elepay.rails.SimulatedRail;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Targeted integration tests for:
 * 1. Multi-merchant split settlement math & strict minor-unit (pesewa) conservation across
 *    odd/prime amounts, merchant fees, marketplace commissions, processing fees, rolling reserves,
 *    delivery-gated settlement, and reserve release.
 * 2. Ambiguous rail provider outcomes ({@code timeout_succeeded} via magic last digit {@code 2}
 *    and {@code lost} via magic last digit {@code 4} on {@link SimulatedRail}) and recovery
 *    reconciliation without double-crediting.
 */
class SettlementAndCrashRecoveryIT extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired RailTransactions rails;
    @Autowired SimulatedRail simulatedRail;
    @Autowired JdbcClient db;

    private TestApi api;
    private String ops;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        ops = api.token("settle-ops-" + UUID.randomUUID(), "ops");
    }

    private record MarketplaceFixture(
            UUID marketplace, String mpOwner,
            UUID sellerA, String sellerAOwner,
            UUID sellerB, String sellerBOwner,
            UUID rider, String riderOwner
    ) {}

    private MarketplaceFixture setupMarketplace(int sellerAFeeBps, long sellerAFeeFixed,
                                                int sellerBFeeBps, long sellerBFeeFixed,
                                                int riderFeeBps) {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String mpOwner = api.token("mp-owner-" + s);
        String sellerAOwner = api.token("seller-a-" + s);
        String sellerBOwner = api.token("seller-b-" + s);
        String riderOwner = api.token("rider-" + s);

        var mpResp = api.call("POST", "/v1/merchants", mpOwner, null, Map.of(
                "legalName", "Split Market " + s, "tradingName", "Split Market " + s,
                "kind", "marketplace", "contactPhone", TestApi.uniquePhone(),
                "contactEmail", "mp-" + s + "@elepay.gh"));
        assertThat(mpResp.status()).isEqualTo(200);
        UUID mp = UUID.fromString(mpResp.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + mp + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0));

        var aResp = api.call("POST", "/v1/merchants", sellerAOwner, null, Map.of(
                "legalName", "Seller A " + s, "tradingName", "Seller A " + s,
                "kind", "merchant", "contactPhone", TestApi.uniquePhone(),
                "contactEmail", "a-" + s + "@elepay.gh"));
        UUID sellerA = UUID.fromString(aResp.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + sellerA + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", sellerAFeeBps, "feeFixed", sellerAFeeFixed,
                        "settlementDelayHours", 0));
        linkSeller(mp, mpOwner, sellerA, sellerAOwner, aResp.body().get("merchantCode").asString());

        var bResp = api.call("POST", "/v1/merchants", sellerBOwner, null, Map.of(
                "legalName", "Seller B " + s, "tradingName", "Seller B " + s,
                "kind", "merchant", "contactPhone", TestApi.uniquePhone(),
                "contactEmail", "b-" + s + "@elepay.gh"));
        UUID sellerB = UUID.fromString(bResp.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + sellerB + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", sellerBFeeBps, "feeFixed", sellerBFeeFixed,
                        "settlementDelayHours", 0));
        linkSeller(mp, mpOwner, sellerB, sellerBOwner, bResp.body().get("merchantCode").asString());

        var rResp = api.call("POST", "/v1/merchants", riderOwner, null, Map.of(
                "legalName", "Rider " + s, "tradingName", "Rider " + s,
                "kind", "delivery", "contactPhone", TestApi.uniquePhone(),
                "contactEmail", "r-" + s + "@elepay.gh"));
        UUID rider = UUID.fromString(rResp.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + rider + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", riderFeeBps, "feeFixed", 0,
                        "settlementDelayHours", 0));
        linkSeller(mp, mpOwner, rider, riderOwner, rResp.body().get("merchantCode").asString());

        return new MarketplaceFixture(mp, mpOwner, sellerA, sellerAOwner, sellerB, sellerBOwner, rider, riderOwner);
    }

    private void linkSeller(UUID mp, String mpOwner, UUID seller, String sellerOwner, String sellerCode) {
        assertThat(api.call("POST", "/v1/merchants/" + mp + "/sellers", mpOwner, null,
                Map.of("merchantCode", sellerCode)).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + seller + "/marketplaces/" + mp + "/accept",
                sellerOwner, null, null).status()).isEqualTo(200);
    }

    @Test
    void multiMerchantSplitSettlementConservesEveryPesewaAcrossFeesReservesAndDelivery() {
        // Odd fee rates & prime minor-unit amounts so integer division produces non-trivial remainders
        var fx = setupMarketplace(175, 11L, 133, 7L, 100);

        // Configure marketplace commission grid with non-round bps and reserves
        var sched = api.call("PUT", "/v1/merchants/" + fx.marketplace() + "/fee-schedule", fx.mpOwner(), null,
                Map.of("deliveryConfirmDays", 7, "reserveBps", 0, "changeReason", "IT split test",
                        "rules", List.of(
                                Map.of("category", "electronics", "commissionBps", 733, "commissionMin", 50,
                                        "processingFee", 37, "reserveBps", 517),
                                Map.of("category", "*", "commissionBps", 333, "commissionMin", 20,
                                        "processingFee", 13, "reserveBps", 250))));
        assertThat(sched.status()).isEqualTo(200);

        String buyerSub = "buyer-split-" + UUID.randomUUID();
        String buyerToken = api.token(buyerSub);
        UUID buyer = api.enroll(ops, buyerSub, TestApi.uniquePhone(), "Split Buyer");
        String buyerWallet = com.elepay.wallet.Wallets.available(buyer);

        long amountA = 10_007L;
        long amountB = 5_003L;
        long amountRider = 1_999L;
        long subtotal = amountA + amountB + amountRider; // 17,009 pesewas

        ledger.post(Ledger.Posting.of("fund-split", UUID.randomUUID().toString(), "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 50_000L),
                Ledger.Leg.credit(buyerWallet, 50_000L))));

        long deferredBefore = ledger.balance(Ledger.DEFERRED_REVENUE);
        long revenueBefore = ledger.balance(Ledger.REVENUE);

        var created = api.call("POST", "/v1/merchants/" + fx.marketplace() + "/checkouts",
                fx.mpOwner(), UUID.randomUUID().toString(),
                Map.of("allocations", List.of(
                        Map.of("merchantId", fx.sellerA(), "kind", "sale", "amount", amountA,
                                "description", "Phone", "category", "electronics"),
                        Map.of("merchantId", fx.sellerB(), "kind", "sale", "amount", amountB,
                                "description", "Case", "category", "accessories"),
                        Map.of("merchantId", fx.rider(), "kind", "delivery", "amount", amountRider,
                                "description", "Courier"))));
        assertThat(created.status()).isEqualTo(200);
        String ref = created.body().get("reference").asString();

        var paid = api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", buyerToken,
                UUID.randomUUID().toString(), Map.of());
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.body().get("status").asString()).isEqualTo("paid");
        long totalPaid = paid.body().get("total").asLong();
        long customerFee = paid.body().get("fee").asLong();
        assertThat(totalPaid).isEqualTo(subtotal + customerFee);

        // Verify exact pesewa conservation at payment posting:
        // sum(all merchant pending + all merchant reserved + marketplace pending + deferred delta + revenue delta) == totalPaid
        long aPending = ledger.balance(Ledger.code("merchant", fx.sellerA(), "pending", Ledger.GHS));
        long aReserved = ledger.balance(Ledger.code("merchant", fx.sellerA(), "reserved", Ledger.GHS));
        long bPending = ledger.balance(Ledger.code("merchant", fx.sellerB(), "pending", Ledger.GHS));
        long bReserved = ledger.balance(Ledger.code("merchant", fx.sellerB(), "reserved", Ledger.GHS));
        long rPending = ledger.balance(Ledger.code("merchant", fx.rider(), "pending", Ledger.GHS));
        long mpPending = ledger.balance(Ledger.code("merchant", fx.marketplace(), "pending", Ledger.GHS));
        long deferredDelta = ledger.balance(Ledger.DEFERRED_REVENUE) - deferredBefore;
        long revenueDelta = ledger.balance(Ledger.REVENUE) - revenueBefore;

        assertThat(aPending).isPositive();
        assertThat(aReserved).isEqualTo(amountA * 517L / 10_000L); // 517 pesewas
        assertThat(bPending).isPositive();
        assertThat(bReserved).isEqualTo(amountB * 250L / 10_000L); // 125 pesewas
        assertThat(rPending).isPositive();
        assertThat(mpPending).isPositive();
        assertThat(deferredDelta).isPositive();
        assertThat(revenueDelta).isEqualTo(customerFee);

        long conservedAtPayment = aPending + aReserved + bPending + bReserved + rPending + mpPending
                + deferredDelta + revenueDelta;
        assertThat(conservedAtPayment).isEqualTo(totalPaid);
        assertThat(ledger.balance(buyerWallet)).isEqualTo(50_000L - totalPaid);

        // Confirm delivery -> triggers settlement (settlementDelayHours = 0)
        var delivered = api.call("POST", "/v1/merchants/" + fx.marketplace() + "/checkouts/" + ref + "/deliveries",
                fx.mpOwner(), null, Map.of());
        assertThat(delivered.status()).isEqualTo(200);

        // All pending buckets drained into available; deferred merchant fees recognized into revenue
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerA(), "pending", Ledger.GHS))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerB(), "pending", Ledger.GHS))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", fx.rider(), "pending", Ledger.GHS))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", fx.marketplace(), "pending", Ledger.GHS))).isZero();

        long aAvail = ledger.balance(Ledger.code("merchant", fx.sellerA(), "available", Ledger.GHS));
        long bAvail = ledger.balance(Ledger.code("merchant", fx.sellerB(), "available", Ledger.GHS));
        long rAvail = ledger.balance(Ledger.code("merchant", fx.rider(), "available", Ledger.GHS));
        long mpAvail = ledger.balance(Ledger.code("merchant", fx.marketplace(), "available", Ledger.GHS));
        long totalRevenueDelta = ledger.balance(Ledger.REVENUE) - revenueBefore;

        assertThat(aAvail).isEqualTo(aPending);
        assertThat(bAvail).isEqualTo(bPending);
        assertThat(rAvail).isEqualTo(rPending);
        assertThat(mpAvail).isEqualTo(mpPending);
        assertThat(ledger.balance(Ledger.DEFERRED_REVENUE)).isEqualTo(deferredBefore);
        assertThat(totalRevenueDelta).isEqualTo(customerFee + deferredDelta);

        // Exact conservation post-settlement (reserves still held)
        assertThat(aAvail + aReserved + bAvail + bReserved + rAvail + mpAvail + totalRevenueDelta)
                .isEqualTo(totalPaid);

        // Release Seller A's reserve -> moves cleanly from reserved to available (line already settled)
        var rel = api.call("POST", "/v1/merchants/" + fx.marketplace() + "/checkouts/" + ref + "/allocations/0/release-reserve",
                ops, "rel-" + UUID.randomUUID(), Map.of("amount", aReserved, "reason", "Post-delivery release"));
        assertThat(rel.status()).isEqualTo(200);
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerA(), "reserved", Ledger.GHS))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerA(), "available", Ledger.GHS)))
                .isEqualTo(aAvail + aReserved);

        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void timeoutSucceededRailOutcomeRecoversExactlyOnceWithoutDoubleCrediting() {
        var fx = setupMarketplace(100, 0L, 150, 0L, 0);

        var created = api.call("POST", "/v1/merchants/" + fx.marketplace() + "/checkouts",
                fx.mpOwner(), UUID.randomUUID().toString(),
                Map.of("allocations", List.of(
                        Map.of("merchantId", fx.sellerA(), "kind", "sale", "amount", 10_000L, "description", "Item A"),
                        Map.of("merchantId", fx.sellerB(), "kind", "sale", "amount", 5_000L, "description", "Item B"))));
        assertThat(created.status()).isEqualTo(200);
        String ref = created.body().get("reference").asString();

        // Last digit '2' triggers SimulatedRail timeout_succeeded: rail records succeeded, submit returns UNKNOWN
        var pay = api.call("POST", "/v1/public/checkouts/" + ref + "/pay", null, UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0241234562", "network", "mtn"));
        assertThat(pay.status()).isEqualTo(200);
        assertThat(pay.body().get("status").asString()).isEqualTo("processing");
        assertThat(pay.body().get("railStatus").asString()).isEqualTo("unknown");

        // Neither seller is credited while outcome is unknown
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerA(), "pending", Ledger.GHS))).isZero();
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerB(), "pending", Ledger.GHS))).isZero();

        String railRef = db.sql("""
                SELECT rt.reference FROM rail_transaction rt
                JOIN payment_attempt pa ON pa.rail_tx = rt.id
                JOIN checkout c ON c.id = pa.checkout_id
                WHERE c.reference = :ref""")
                .param("ref", ref).query(String.class).single();

        // Recovery queries SimulatedRail -> SUCCEEDED -> posts split once
        int resolved = rails.recover(100, true);
        assertThat(resolved).isGreaterThanOrEqualTo(1);

        var afterRecover = api.call("GET", "/v1/public/checkouts/" + ref + "/status", null, null, null);
        assertThat(afterRecover.status()).isEqualTo(200);
        assertThat(afterRecover.body().get("status").asString()).isEqualTo("paid");
        assertThat(afterRecover.body().get("railStatus").asString()).isEqualTo("succeeded");

        long sellerAPending = ledger.balance(Ledger.code("merchant", fx.sellerA(), "pending", Ledger.GHS));
        long sellerBPending = ledger.balance(Ledger.code("merchant", fx.sellerB(), "pending", Ledger.GHS));
        assertThat(sellerAPending).isEqualTo(9_900L); // 10,000 - 1% (100)
        assertThat(sellerBPending).isEqualTo(4_925L); // 5,000 - 1.5% (75)

        long journalCountAfterRecovery = db.sql("SELECT count(*) FROM journal_transaction").query(Long.class).single();

        // Second recovery pass must be a no-op
        assertThat(rails.recover(100, true)).isZero();

        // Late signed callback from rail after recovery must be absorbed without double-crediting
        var signedCb = simulatedRail.callback(railRef, "succeeded", null);
        int cbStatus = api.http.post().uri("/v1/rails/sandbox/callbacks")
                .header("x-sandbox-signature", signedCb.getKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(signedCb.getValue())
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(cbStatus).isEqualTo(200);

        // Exact same callback replayed -> deduplicated
        int cbReplayStatus = api.http.post().uri("/v1/rails/sandbox/callbacks")
                .header("x-sandbox-signature", signedCb.getKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(signedCb.getValue())
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(cbReplayStatus).isEqualTo(200);

        // Balances and journal count must be strictly unchanged
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerA(), "pending", Ledger.GHS))).isEqualTo(sellerAPending);
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerB(), "pending", Ledger.GHS))).isEqualTo(sellerBPending);
        assertThat(db.sql("SELECT count(*) FROM journal_transaction").query(Long.class).single())
                .isEqualTo(journalCountAfterRecovery);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void lostRailChargeBacksOffDuringGraceWindowAndFailsCleanlyAfterGraceElapsed() {
        var fx = setupMarketplace(100, 0L, 100, 0L, 0);

        var created = api.call("POST", "/v1/merchants/" + fx.marketplace() + "/checkouts",
                fx.mpOwner(), UUID.randomUUID().toString(),
                Map.of("allocations", List.of(
                        Map.of("merchantId", fx.sellerA(), "kind", "sale", "amount", 7_500L, "description", "Lost Item"))));
        assertThat(created.status()).isEqualTo(200);
        String ref = created.body().get("reference").asString();

        // Last digit '4' triggers SimulatedRail lost: submit returns UNKNOWN and rail never records it (query -> NOT_FOUND)
        var pay = api.call("POST", "/v1/public/checkouts/" + ref + "/pay", null, UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0241234564", "network", "mtn"));
        assertThat(pay.status()).isEqualTo(200);
        assertThat(pay.body().get("status").asString()).isEqualTo("processing");
        assertThat(pay.body().get("railStatus").asString()).isEqualTo("unknown");

        UUID railTxId = db.sql("""
                SELECT rt.id FROM rail_transaction rt
                JOIN payment_attempt pa ON pa.rail_tx = rt.id
                JOIN checkout c ON c.id = pa.checkout_id
                WHERE c.reference = :ref""")
                .param("ref", ref).query(UUID.class).single();

        // 1. Recovery inside 10-minute grace window: stays 'unknown' and backs off next_check_at into the future
        OffsetDateTime beforeCheck = OffsetDateTime.now();
        rails.recover(100, true);
        assertThat(rails.find(railTxId).orElseThrow().status()).isEqualTo("unknown");
        OffsetDateTime nextCheckAt = db.sql("SELECT next_check_at FROM rail_transaction WHERE id = :id")
                .param("id", railTxId).query(OffsetDateTime.class).single();
        assertThat(nextCheckAt).isAfter(beforeCheck.plusSeconds(15));
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerA(), "pending", Ledger.GHS))).isZero();

        // 2. Simulate 10-minute grace period elapsing -> recovery marks rail_tx failed and resets checkout to 'open'
        db.sql("UPDATE rail_transaction SET created_at = now() - interval '15 minutes' WHERE id = :id")
                .param("id", railTxId).update();
        int resolved = rails.recover(100, true);
        assertThat(resolved).isGreaterThanOrEqualTo(1);

        assertThat(rails.find(railTxId).orElseThrow().status()).isEqualTo("failed");
        var afterGrace = api.call("GET", "/v1/public/checkouts/" + ref + "/status", null, null, null);
        assertThat(afterGrace.status()).isEqualTo(200);
        assertThat(afterGrace.body().get("status").asString()).isEqualTo("open");
        assertThat(ledger.balance(Ledger.code("merchant", fx.sellerA(), "pending", Ledger.GHS))).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
