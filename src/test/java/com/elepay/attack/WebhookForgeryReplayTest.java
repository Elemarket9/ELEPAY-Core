package com.elepay.attack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.CallbackAuthenticity;
import com.elepay.common.HmacVerification;
import com.elepay.enterprise.wallet.HmacWebhookVerifier;
import com.elepay.enterprise.wallet.PartnerAuthCredentials;
import com.elepay.enterprise.wallet.PartnerScope;
import com.elepay.enterprise.wallet.WebhookSignatureVerificationRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Attack class C — webhook / callback forgery and replay. Exercises the real verifiers
 * ({@link HmacWebhookVerifier}, {@link CallbackAuthenticity}, {@link HmacVerification}) directly and
 * asserts every forged, malformed or replayed message is rejected (fail-closed).
 */
class WebhookForgeryReplayTest {

    private static final String SECRET = "whsec_partner_signing_key_0123456789abcdef";
    private static final String PARTNER = "partner-ghana-01";
    private static final String BODY = "{\"event\":\"payment.captured\",\"amount\":500000,\"currency\":\"GHS\"}";

    private HmacWebhookVerifier verifier;
    private PartnerAuthCredentials credentials;

    @BeforeEach
    void setUp() {
        verifier = new HmacWebhookVerifier(); // in-memory nonce store (unit mode)
        credentials = PartnerAuthCredentials.create(PARTNER, "Ghana Partner", "client-id", "client-secret",
                SECRET, Set.of(PartnerScope.WALLET_CAPTURE));
        CallbackAuthenticity.resetForTesting();
    }

    private WebhookSignatureVerificationRequest request(long ts, String nonce, String signature) {
        return new WebhookSignatureVerificationRequest(PARTNER, BODY, signature, Long.toString(ts), nonce, "1");
    }

    // ---- HmacWebhookVerifier (enterprise, distributed nonce dedupe, record-after-verify) ----------

    @Test
    @DisplayName("C1: a valid signature verifies; a forged, missing or wrong-length signature is rejected")
    void forgedSignaturesRejected() {
        long ts = Instant.now().getEpochSecond();
        String nonce = "nonce-valid";
        String good = HmacWebhookVerifier.computeSignature(SECRET, ts, nonce, BODY);

        assertThat(verifier.verify(request(ts, nonce, good), credentials).valid()).isTrue();

        // Forged: full-length hex, wrong content.
        String forged = good.substring(0, good.length() - 1) + (good.endsWith("0") ? "1" : "0");
        var forgedRes = verifier.verify(request(ts, "nonce-forged", forged), credentials);
        assertThat(forgedRes.valid()).isFalse();
        assertThat(forgedRes.failureReason()).contains("signature mismatch");

        // Missing signature.
        assertThat(verifier.verify(request(ts, "nonce-missing", ""), credentials).valid()).isFalse();

        // Wrong-length signature.
        assertThat(verifier.verify(request(ts, "nonce-short", "deadbeef"), credentials).valid()).isFalse();

        // Wrong signing key entirely.
        String wrongKeySig = HmacWebhookVerifier.computeSignature("attacker-guessed-key", ts, "nonce-wrongkey", BODY);
        assertThat(verifier.verify(request(ts, "nonce-wrongkey", wrongKeySig), credentials).valid()).isFalse();
    }

    @Test
    @DisplayName("C2: replay of a valid (timestamp,nonce,signature) tuple is rejected on the second delivery (nonce dedupe)")
    void replayRejected() {
        long ts = Instant.now().getEpochSecond();
        String nonce = "nonce-replay";
        String sig = HmacWebhookVerifier.computeSignature(SECRET, ts, nonce, BODY);

        assertThat(verifier.verify(request(ts, nonce, sig), credentials).valid()).isTrue();
        var replay = verifier.verify(request(ts, nonce, sig), credentials);
        assertThat(replay.valid()).isFalse();
        assertThat(replay.failureReason()).contains("Replay");
    }

    @Test
    @DisplayName("C3: a correctly-signed message outside the tolerance window is rejected (stale timestamp)")
    void staleTimestampRejected() {
        long stale = Instant.now().getEpochSecond() - (HmacWebhookVerifier.DEFAULT_TOLERANCE_SECONDS + 120);
        String nonce = "nonce-stale";
        String sig = HmacWebhookVerifier.computeSignature(SECRET, stale, nonce, BODY);

        var res = verifier.verify(request(stale, nonce, sig), credentials);
        assertThat(res.valid()).isFalse();
        assertThat(res.failureReason()).containsIgnoringCase("timestamp");
    }

    @Test
    @DisplayName("C4: a failed-signature delivery does NOT consume the nonce (record-after-verify), so the honest message still verifies")
    void nonceRecordedOnlyAfterValidSignature() {
        long ts = Instant.now().getEpochSecond();
        String nonce = "nonce-poison";
        // Attacker floods a bad signature for a nonce the honest partner will later use.
        assertThat(verifier.verify(request(ts, nonce, "00".repeat(32)), credentials).valid()).isFalse();
        // The genuine, correctly-signed message with the same nonce must still be accepted once.
        String sig = HmacWebhookVerifier.computeSignature(SECRET, ts, nonce, BODY);
        assertThat(verifier.verify(request(ts, nonce, sig), credentials).valid()).isTrue();
    }

    // ---- CallbackAuthenticity (rail/gateway callbacks: timestamp.nonce.body) ----------------------

    private Map<String, String> headers(long ts, String nonce, String signature) {
        Map<String, String> h = new HashMap<>();
        h.put("x-callback-timestamp", Long.toString(ts));
        h.put("x-callback-nonce", nonce);
        if (signature != null) h.put("x-callback-signature", signature);
        return h;
    }

    private String sign(long ts, String nonce, String body) {
        return HmacVerification.computeHex(SECRET, ts + "." + nonce + "." + body);
    }

    @Test
    @DisplayName("C5: rail callback — forged, missing and wrong-length signatures are rejected; a valid one passes")
    void railCallbackSignatureChecks() {
        long ts = Instant.now().getEpochSecond();

        // Valid.
        CallbackAuthenticity.verify(SECRET, headers(ts, "cb-ok", sign(ts, "cb-ok", BODY)), BODY, "x-callback-signature");

        // Forged.
        assertThatThrownBy(() -> CallbackAuthenticity.verify(SECRET,
                headers(ts, "cb-forged", "a".repeat(64)), BODY, "x-callback-signature"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("signature is invalid");

        // Missing.
        assertThatThrownBy(() -> CallbackAuthenticity.verify(SECRET,
                headers(ts, "cb-missing", null), BODY, "x-callback-signature"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("signature is missing");

        // Wrong length.
        assertThatThrownBy(() -> CallbackAuthenticity.verify(SECRET,
                headers(ts, "cb-short", "dead"), BODY, "x-callback-signature"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("C6: rail callback — replay of a valid tuple is rejected; a tuple outside the window is rejected")
    void railCallbackReplayAndWindow() {
        long ts = Instant.now().getEpochSecond();
        String nonce = "cb-replay";
        String sig = sign(ts, nonce, BODY);

        CallbackAuthenticity.verify(SECRET, headers(ts, nonce, sig), BODY, "x-callback-signature");
        assertThatThrownBy(() -> CallbackAuthenticity.verify(SECRET, headers(ts, nonce, sig), BODY, "x-callback-signature"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already used");

        long stale = ts - (CallbackAuthenticity.WINDOW_SECONDS + 120);
        assertThatThrownBy(() -> CallbackAuthenticity.verify(SECRET,
                headers(stale, "cb-stale", sign(stale, "cb-stale", BODY)), BODY, "x-callback-signature"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the allowed window");
    }

    @Test
    @DisplayName("C7: HmacVerification.verifyWithTimestamp enforces the replay-tolerance window directly")
    void hmacTimestampToleranceEnforced() {
        long now = Instant.now().getEpochSecond();
        String sig = HmacVerification.computeHex(SECRET, now + "." + BODY);
        assertThat(HmacVerification.verifyWithTimestamp(SECRET, now, BODY, sig, Instant.ofEpochSecond(now), 300)).isTrue();
        // Same signature, evaluated 10 minutes later -> outside tolerance -> rejected.
        assertThat(HmacVerification.verifyWithTimestamp(SECRET, now, BODY, sig, Instant.ofEpochSecond(now + 600), 300))
                .isFalse();
    }
}
