package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Hostile-rail INBOUND resilience: craft malicious / edge callbacks straight at the real
 * callback ingestion controller ({@code POST /v1/rails/{rail}/callbacks}) and prove the ledger
 * and the idempotency machinery stay consistent regardless of how the upstream rail misbehaves.
 *
 * <p>These cases drive the whole path through the running Spring context: a real merchant,
 * a real checkout, a real pending rail charge on the test {@code sandbox} rail
 * ({@link SimulatedRail}, whose callbacks are cryptographically signed and authoritative), then
 * attacker-controlled callbacks delivered over HTTP. Money conservation is asserted via the
 * {@link Ledger} (balances + {@link Ledger#integrity()}) and via direct {@link JdbcClient}
 * reads of {@code rail_transaction} / {@code rail_callback}.
 *
 * <p>The scheduled recovery poller is disabled so that a transaction advances <em>only</em> as a
 * direct consequence of the callback under test, never because a background job resolved it.
 *
 * <p>The timestamp-window / nonce-replay boundary (the {@code CallbackAuthenticity} scheme) is
 * covered separately by {@link CallbackAuthenticityResilienceIT}, since the sandbox rail signs the
 * body only.
 */
@DisplayName("Hostile inbound rail callback resilience (ledger + idempotency)")
class HostileInboundCallbackResilienceIT extends PostgresTestBase {

    @DynamicPropertySource
    static void disableRecovery(DynamicPropertyRegistry registry) {
        // A transaction must change state only because of the callback under test.
        registry.add("elepay.rails.recovery-enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired SimulatedRail simulated;

    private final HttpClient http = HttpClient.newHttpClient();
    private TestApi api;
    private String ops;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        ops = api.token("ops-inbound-" + suffix(), "ops", "ops_admin");
    }

    // ------------------------------------------------------------------------------------------
    // Duplicate delivery -> credited exactly once
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A callback redelivered twice with the identical signed body credits the merchant exactly once")
    void duplicateCallbackCreditsExactlyOnce() {
        Fixture f = pendingCharge("0245550003", 50_000);
        var signed = simulated.callback(f.railReference(), "succeeded", null);

        var first = postCallback("sandbox", signed.getKey(), signed.getValue());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("accepted").contains("applied:SUCCEEDED");
        long credited = merchantTotal(f.merchant());
        assertThat(credited).as("merchant is credited by the first callback").isPositive();

        // Exact same signed body again (same dedupe key = sha256(body)).
        var second = postCallback("sandbox", signed.getKey(), signed.getValue());
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("duplicate");

        assertThat(merchantTotal(f.merchant())).as("no double credit").isEqualTo(credited);
        assertThat(callbackRows("sandbox", f.railReference())).isEqualTo(1);
        assertThat(railStatus(f.railReference())).isEqualTo("succeeded");
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("The same signed callback delivered concurrently by a hostile rail credits exactly once")
    void concurrentDuplicateCallbacksCreditExactlyOnce() throws Exception {
        Fixture f = pendingCharge("0245550003", 40_000);
        var signed = simulated.callback(f.railReference(), "succeeded", null);

        int fan = 24;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < fan; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    var r = postCallback("sandbox", signed.getKey(), signed.getValue());
                    if (r.body().contains("\"status\":\"accepted\"")) accepted.incrementAndGet();
                    else if (r.body().contains("duplicate")) duplicate.incrementAndGet();
                    return null;
                }));
            }
            gate.countDown();
            for (var fut : futures) fut.get();
        }

        assertThat(accepted.get()).as("exactly one delivery is applied").isEqualTo(1);
        assertThat(duplicate.get()).isEqualTo(fan - 1);
        assertThat(callbackRows("sandbox", f.railReference())).isEqualTo(1);
        assertThat(merchantTotal(f.merchant())).isPositive();
        assertThat(railStatus(f.railReference())).isEqualTo("succeeded");
        assertThat(ledger.integrity().ok()).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Out-of-order / contradictory delivery
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Out-of-order callbacks (success, then an earlier PENDING, then a contradictory FAILED) never corrupt the ledger")
    void outOfOrderCallbacksDoNotCorruptLedger() {
        Fixture f = pendingCharge("0245550003", 60_000);

        var success = simulated.callback(f.railReference(), "succeeded", null);
        assertThat(postCallback("sandbox", success.getKey(), success.getValue()).statusCode()).isEqualTo(200);
        long credited = merchantTotal(f.merchant());
        assertThat(credited).isPositive();
        assertThat(railStatus(f.railReference())).isEqualTo("succeeded");

        // An earlier lifecycle state arrives late: PENDING after a terminal SUCCEEDED.
        var late = simulated.callback(f.railReference(), "pending", null);
        assertThat(postCallback("sandbox", late.getKey(), late.getValue()).statusCode()).isEqualTo(200);
        assertThat(railStatus(f.railReference())).as("terminal state is final").isEqualTo("succeeded");
        assertThat(merchantTotal(f.merchant())).isEqualTo(credited);

        // A contradictory FAILED arrives after SUCCEEDED: must not reverse/double, only flag.
        var failed = simulated.callback(f.railReference(), "failed", null);
        assertThat(postCallback("sandbox", failed.getKey(), failed.getValue()).statusCode()).isEqualTo(200);
        assertThat(railStatus(f.railReference())).isEqualTo("succeeded");
        assertThat(merchantTotal(f.merchant())).as("no reversal, no negative balance").isEqualTo(credited);
        assertThat(merchantTotal(f.merchant())).isNotNegative();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Forged / missing signature -> rejected, no state change
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A forged / garbage HMAC signature is rejected (401) and changes no ledger or callback state")
    void forgedSignatureRejectedNoStateChange() {
        Fixture f = pendingCharge("0245550003", 30_000);
        var legit = simulated.callback(f.railReference(), "succeeded", null);

        var forged = postCallback("sandbox", "deadbeefdeadbeefdeadbeef", legit.getValue());
        assertThat(forged.statusCode()).isEqualTo(401);

        assertThat(callbackRows("sandbox", f.railReference())).as("no callback persisted").isZero();
        assertThat(railStatus(f.railReference())).isEqualTo("pending");
        assertThat(merchantTotal(f.merchant())).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("A callback with NO signature header is rejected (401) and changes no state")
    void missingSignatureRejectedNoStateChange() {
        Fixture f = pendingCharge("0245550003", 30_000);
        var legit = simulated.callback(f.railReference(), "succeeded", null);

        var missing = postCallbackNoSig("sandbox", legit.getValue());
        assertThat(missing.statusCode()).isEqualTo(401);

        assertThat(callbackRows("sandbox", f.railReference())).isZero();
        assertThat(railStatus(f.railReference())).isEqualTo("pending");
        assertThat(merchantTotal(f.merchant())).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Amount mismatch -> never credits
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A SUCCESS callback whose amount differs from the intent is flagged (mismatch) and never credits the ledger")
    void callbackAmountMismatchDoesNotCreditLedger() {
        Fixture f = pendingCharge("0245550003", 50_000);
        // Hostile rail claims success for a different (inflated) amount.
        var tampered = simulated.callback(f.railReference(), "succeeded", 999_999L);

        var r = postCallback("sandbox", tampered.getKey(), tampered.getValue());
        assertThat(r.statusCode()).isEqualTo(200);

        assertThat(railStatus(f.railReference())).as("amount evidence rejected").isEqualTo("mismatch");
        assertThat(merchantTotal(f.merchant())).as("no credit on tampered amount").isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Malformed / oversized body -> rejected cleanly, no partial write
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A malformed JSON body is rejected cleanly: no 2xx apply, no partial write, no stack-trace leak, server stays healthy")
    void malformedJsonRejectedCleanly() {
        Fixture f = pendingCharge("0245550003", 30_000);
        String malformed = "{\"event\":\"transaction.succeeded\",\"data\":{";
        String sig = simulated.sign(malformed); // validly signed garbage: auth passes, parsing must fail-closed

        var r = postCallback("sandbox", sig, malformed);
        assertThat(r.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(r.body()).doesNotContain("\tat ").doesNotContain("com.elepay.")
                .doesNotContain("Exception:"); // no raw stack trace leaked to the caller

        assertThat(railStatus(f.railReference())).as("no partial write").isEqualTo("pending");
        assertThat(merchantTotal(f.merchant())).isZero();
        assertThat(ledger.integrity().ok()).isTrue();

        // The ingestion path is not wedged: a subsequent legitimate callback still works.
        var good = simulated.callback(f.railReference(), "succeeded", null);
        assertThat(postCallback("sandbox", good.getKey(), good.getValue()).statusCode()).isEqualTo(200);
        assertThat(railStatus(f.railReference())).isEqualTo("succeeded");
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("An oversized hostile body is handled without 5xx and fails closed (401) with no state change")
    void oversizedHostileBodyRejectedCleanly() {
        Fixture f = pendingCharge("0245550003", 30_000);
        String padding = "A".repeat(1_300_000);
        String huge = "{\"event\":\"transaction.succeeded\",\"junk\":\"" + padding
                + "\",\"data\":{\"reference\":\"" + f.railReference()
                + "\",\"status\":\"succeeded\",\"amount\":30000,\"currency\":\"GHS\"}}";

        // Not signed with the real secret: a large body must be rejected at the auth boundary, not crash the server.
        var r = postCallback("sandbox", "00", huge);
        assertThat(r.statusCode()).isEqualTo(401);

        assertThat(callbackRows("sandbox", f.railReference())).isZero();
        assertThat(railStatus(f.railReference())).isEqualTo("pending");
        assertThat(merchantTotal(f.merchant())).isZero();
        assertThat(ledger.integrity().ok()).isTrue();

        // Server still healthy afterwards.
        var good = simulated.callback(f.railReference(), "succeeded", null);
        assertThat(postCallback("sandbox", good.getKey(), good.getValue()).statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private record Fixture(UUID merchant, String checkoutReference, String railReference) {}

    /** Creates an approved merchant + checkout, pays it on the sandbox rail, and leaves the charge PENDING. */
    private Fixture pendingCharge(String phone, long amount) {
        String s = suffix();
        String owner = api.token("merch-owner-" + s);
        UUID merchant = createApprovedMerchant(owner, "Resilience Merchant " + s);

        var checkout = api.call("POST", "/v1/merchants/" + merchant + "/checkouts", owner,
                UUID.randomUUID().toString(), Map.of("amount", amount, "description", "Inbound resilience " + s));
        assertThat(checkout.status()).as(String.valueOf(checkout.body())).isEqualTo(200);
        String reference = checkout.body().get("reference").asString();

        var pay = api.call("POST", "/v1/public/checkouts/" + reference + "/pay", null,
                UUID.randomUUID().toString(), Map.of("method", "momo", "phone", phone, "network", "mtn"));
        assertThat(pay.status()).as(String.valueOf(pay.body())).isEqualTo(200);

        String railRef = db.sql("""
                SELECT rt.reference FROM rail_transaction rt
                JOIN payment_attempt pa ON pa.rail_tx = rt.id
                JOIN checkout c ON c.id = pa.checkout_id
                WHERE c.reference = :ref AND rt.rail = 'sandbox'
                ORDER BY rt.created_at DESC LIMIT 1""")
                .param("ref", reference).query(String.class).single();
        assertThat(railStatus(railRef)).isEqualTo("pending");
        return new Fixture(merchant, reference, railRef);
    }

    private UUID createApprovedMerchant(String ownerToken, String name) {
        var created = api.call("POST", "/v1/merchants", ownerToken, null, Map.of(
                "legalName", name + " Ltd", "tradingName", name, "kind", "merchant", "contactPhone", "0241234567"));
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(200);
        UUID id = UUID.fromString(created.body().get("id").asString());
        var review = api.call("POST", "/v1/ops/merchants/" + id + "/review", ops, null, Map.of(
                "decision", "approve", "feeBps", 100, "feeFixed", 0, "settlementDelayHours", 0));
        assertThat(review.status()).as(String.valueOf(review.body())).isEqualTo(200);
        return id;
    }

    private long merchantTotal(UUID merchant) {
        long total = 0;
        for (String bucket : List.of("available", "pending", "reserved")) {
            total += ledger.balance(Ledger.code("merchant", merchant, bucket, "GHS"));
        }
        return total;
    }

    private String railStatus(String reference) {
        return db.sql("SELECT status FROM rail_transaction WHERE reference = :r")
                .param("r", reference).query(String.class).single();
    }

    private int callbackRows(String rail, String reference) {
        return db.sql("SELECT count(*) FROM rail_callback WHERE rail = :rail AND reference = :r")
                .param("rail", rail).param("r", reference).query(Integer.class).single();
    }

    private HttpResponse<String> postCallback(String rail, String signature, String body) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/rails/" + rail + "/callbacks"))
                    .header("Content-Type", "application/json")
                    .header("x-sandbox-signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse<String> postCallbackNoSig(String rail, String body) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/rails/" + rail + "/callbacks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
