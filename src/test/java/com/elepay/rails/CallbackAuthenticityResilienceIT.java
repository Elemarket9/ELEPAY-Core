package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.common.CallbackAuthenticity;
import com.elepay.ledger.Ledger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Hostile inbound callback resilience for a rail whose webhooks are authenticated with the
 * {@code timestamp.nonce.body} HMAC scheme and a bounded replay window
 * ({@link CallbackAuthenticity}). The {@code sandbox} rail used by
 * {@link HostileInboundCallbackResilienceIT} signs the body only; this class enables the real
 * {@link AirtelTigoProductionRail} (authoritative, CallbackAuthenticity-based) purely to exercise
 * that signature / timestamp-window / nonce-replay boundary through the real
 * {@code POST /v1/rails/airteltigo/callbacks} controller.
 *
 * <p>No network is touched: the callback path verifies and applies locally. A matching
 * {@code rail_transaction} is seeded directly so matching + apply are exercised; the rail's
 * outbound base URL is never called (recovery polling is disabled).
 */
@DisplayName("Inbound callback authenticity: HMAC, timestamp window and nonce replay")
class CallbackAuthenticityResilienceIT extends PostgresTestBase {

    private static final String SECRET = "at-resilience-webhook-secret-32bytes!!";

    @DynamicPropertySource
    static void enableAirtelTigo(DynamicPropertyRegistry registry) {
        registry.add("elepay.rails.airteltigo.enabled", () -> "true");
        registry.add("elepay.rails.airteltigo.webhook-secret", () -> SECRET);
        registry.add("elepay.rails.airteltigo.client-id", () -> "resilience-client");
        registry.add("elepay.rails.airteltigo.client-secret", () -> "resilience-secret");
        registry.add("elepay.rails.airteltigo.merchant-id", () -> "resilience-merchant");
        registry.add("elepay.rails.recovery-enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void freshNonceSpace() {
        CallbackAuthenticity.resetForTesting();
    }

    @Test
    @DisplayName("Missing timestamp / nonce headers are rejected (401) and persist nothing")
    void missingTimestampNonceRejected() {
        String reference = seedPending(5_000);
        String body = body(reference, "SUCCESSFUL", 5_000);
        String sig = signFor(Instant.now().getEpochSecond(), "n", body);

        var r = send(sig, null, null, body); // no timestamp/nonce
        assertThat(r.statusCode()).isEqualTo(401);
        assertUnchanged("airteltigo", reference);
    }

    @Test
    @DisplayName("A missing signature header is rejected (401) and persists nothing")
    void missingSignatureRejected() {
        String reference = seedPending(5_000);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        var r = send(null, ts, "nonce-" + reference, body(reference, "SUCCESSFUL", 5_000));
        assertThat(r.statusCode()).isEqualTo(401);
        assertUnchanged("airteltigo", reference);
    }

    @Test
    @DisplayName("A forged signature over the correct timestamp/nonce/body is rejected (401)")
    void forgedSignatureRejected() {
        String reference = seedPending(5_000);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        var r = send("not-a-real-signature", ts, "nonce-" + reference, body(reference, "SUCCESSFUL", 5_000));
        assertThat(r.statusCode()).isEqualTo(401);
        assertUnchanged("airteltigo", reference);
    }

    @Test
    @DisplayName("A validly signed tuple replayed OUTSIDE the timestamp window (stale and future) is rejected (401)")
    void replayOutsideTimestampWindowRejected() {
        String reference = seedPending(5_000);
        String body = body(reference, "SUCCESSFUL", 5_000);
        long now = Instant.now().getEpochSecond();

        long stale = now - (CallbackAuthenticity.WINDOW_SECONDS + 120);
        var r1 = send(signFor(stale, "stale-" + reference, body), String.valueOf(stale), "stale-" + reference, body);
        assertThat(r1.statusCode()).as("stale timestamp rejected").isEqualTo(401);

        long future = now + (CallbackAuthenticity.WINDOW_SECONDS + 120);
        var r2 = send(signFor(future, "future-" + reference, body), String.valueOf(future), "future-" + reference, body);
        assertThat(r2.statusCode()).as("future timestamp rejected").isEqualTo(401);

        assertUnchanged("airteltigo", reference);
    }

    @Test
    @DisplayName("A captured, previously valid tuple replayed verbatim is accepted once then rejected (nonce reuse); applied exactly once")
    void capturedValidTupleReplayedRejected() {
        String reference = seedPending(5_000);
        String body = body(reference, "SUCCESSFUL", 5_000);
        long ts = Instant.now().getEpochSecond();
        String nonce = "captured-" + reference;
        String sig = signFor(ts, nonce, body);

        var first = send(sig, String.valueOf(ts), nonce, body);
        assertThat(first.statusCode()).as("first valid delivery accepted").isEqualTo(200);
        assertThat(first.body()).contains("applied:SUCCEEDED");
        assertThat(railStatus(reference)).isEqualTo("succeeded");

        // Verbatim replay of the captured tuple: same ts + nonce + sig + body.
        var replay = send(sig, String.valueOf(ts), nonce, body);
        assertThat(replay.statusCode()).as("verbatim replay rejected (nonce reuse)").isEqualTo(401);

        assertThat(callbackRows("airteltigo", reference)).as("applied exactly once").isEqualTo(1);
        assertThat(railStatus(reference)).isEqualTo("succeeded");
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("An authoritative callback whose amount differs from the intent is flagged (mismatch), not credited")
    void callbackAmountMismatchFlaggedNotCredited() {
        String reference = seedPending(5_000);
        String body = body(reference, "SUCCESSFUL", 9_999); // hostile: wrong amount
        long ts = Instant.now().getEpochSecond();
        String nonce = "mismatch-" + reference;

        var r = send(signFor(ts, nonce, body), String.valueOf(ts), nonce, body);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(railStatus(reference)).as("amount evidence rejected").isEqualTo("mismatch");
        assertThat(ledger.integrity().ok()).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Seeds a PENDING airteltigo charge whose success/mismatch no domain listener acts on (purpose is inert). */
    private String seedPending(long amount) {
        String reference = "EPCRES" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        db.sql("""
                INSERT INTO rail_transaction (id, rail, direction, reference, purpose, purpose_id, amount, currency, status)
                VALUES (:id, 'airteltigo', 'charge', :ref, 'resilience', :pid, :amount, 'GHS', 'pending')""")
                .param("id", UUID.randomUUID()).param("ref", reference).param("pid", UUID.randomUUID())
                .param("amount", amount).update();
        return reference;
    }

    private static String body(String reference, String status, long amount) {
        return "{\"event\":\"charge.update\",\"data\":{\"reference\":\"" + reference + "\",\"status\":\"" + status
                + "\",\"amount\":" + amount + ",\"currency\":\"GHS\",\"railReference\":\"AT-" + reference + "\"}}";
    }

    private static String signFor(long ts, String nonce, String body) {
        return AirtelTigoProductionRail.computeHmac(SECRET, ts + "." + nonce + "." + body);
    }

    private HttpResponse<String> send(String signature, String timestamp, String nonce, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/v1/rails/airteltigo/callbacks"))
                    .header("Content-Type", "application/json");
            if (signature != null) b.header("x-airteltigo-signature", signature);
            if (timestamp != null) b.header("x-callback-timestamp", timestamp);
            if (nonce != null) b.header("x-callback-nonce", nonce);
            return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertUnchanged(String rail, String reference) {
        assertThat(callbackRows(rail, reference)).as("nothing persisted").isZero();
        assertThat(railStatus(reference)).isEqualTo("pending");
        assertThat(ledger.integrity().ok()).isTrue();
    }

    private String railStatus(String reference) {
        return db.sql("SELECT status FROM rail_transaction WHERE reference = :r")
                .param("r", reference).query(String.class).single();
    }

    private int callbackRows(String rail, String reference) {
        return db.sql("SELECT count(*) FROM rail_callback WHERE rail = :rail AND reference = :r")
                .param("rail", rail).param("r", reference).query(Integer.class).single();
    }
}
