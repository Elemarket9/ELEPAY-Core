package com.elepay.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestSigningFilterTest {

    private DistributedNonceStore nonceStore;
    private ApiKeys apiKeys;
    private RequestSigningFilter filter;
    private final String globalSecret = "global-test-signing-secret-32bytes!";

    @BeforeEach
    void setUp() {
        nonceStore = new DistributedNonceStore(null, null);
        apiKeys = mock(ApiKeys.class);
        filter = new RequestSigningFilter(globalSecret, false, nonceStore, apiKeys);
    }

    @Test
    @DisplayName("Valid signature with fresh timestamp and unique nonce passes through filter")
    void validSignaturePasses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/orders");
        String body = "{\"amount\":1000,\"currency\":\"GHS\"}";
        request.setContent(body.getBytes());

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-" + UUID.randomUUID();
        String signature = RequestSigningFilter.computeSignature(globalSecret, timestamp, "POST", "/v1/checkout/orders", body);

        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, signature);
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Without a configured secret, a signed request is refused rather than checked against a known key")
    void signatureWithoutConfiguredSecretIsRefused() throws ServletException, IOException {
        var unconfigured = new RequestSigningFilter("", false, nonceStore, apiKeys);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/orders");
        String body = "{}";
        request.setContent(body.getBytes());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        // Signed with the secret earlier releases shipped as a default.
        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, RequestSigningFilter.computeSignature(
                "elepay-default-signing-secret-key-32b", timestamp, "POST", "/v1/checkout/orders", body));
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(RequestSigningFilter.HEADER_NONCE, "nonce-" + UUID.randomUUID());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        unconfigured.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("signing_not_configured");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("Missing timestamp header returns 400 Bad Request")
    void missingTimestampRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/orders");
        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, "some-signature");
        request.addHeader(RequestSigningFilter.HEADER_NONCE, "nonce-1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(response.getContentAsString()).contains("missing_timestamp");
    }

    @Test
    @DisplayName("Timestamp older than 300s window returns 401 Unauthorized")
    void expiredTimestampRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/orders");
        long expiredTime = Instant.now().getEpochSecond() - 301;
        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, "some-signature");
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, String.valueOf(expiredTime));
        request.addHeader(RequestSigningFilter.HEADER_NONCE, "nonce-expired");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("request_expired");
    }

    @Test
    @DisplayName("Timestamp in future beyond 300s returns 401 Unauthorized")
    void futureTimestampRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/orders");
        long futureTime = Instant.now().getEpochSecond() + 305;
        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, "some-signature");
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, String.valueOf(futureTime));
        request.addHeader(RequestSigningFilter.HEADER_NONCE, "nonce-future");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("request_expired");
    }

    @Test
    @DisplayName("Missing nonce header returns 400 Bad Request")
    void missingNonceRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/orders");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, "some-signature");
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(response.getContentAsString()).contains("missing_nonce");
    }

    @Test
    @DisplayName("Replayed nonce returns 409 Conflict")
    void replayedNonceRejected() throws ServletException, IOException {
        String body = "{\"amount\":500}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "replay-nonce-test";
        String sig = RequestSigningFilter.computeSignature(globalSecret, timestamp, "POST", "/v1/payouts", body);

        // First attempt succeeds
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/v1/payouts");
        req1.setContent(body.getBytes());
        req1.addHeader(RequestSigningFilter.HEADER_SIGNATURE, sig);
        req1.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        req1.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(req1, res1, new MockFilterChain());
        assertThat(res1.getStatus()).isEqualTo(200);

        // Second attempt with exact same nonce fails with 409 Conflict
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/v1/payouts");
        req2.setContent(body.getBytes());
        req2.addHeader(RequestSigningFilter.HEADER_SIGNATURE, sig);
        req2.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        req2.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, new MockFilterChain());
        assertThat(res2.getStatus()).isEqualTo(HttpServletResponse.SC_CONFLICT);
        assertThat(res2.getContentAsString()).contains("replay_detected");
    }

    @Test
    @DisplayName("Signature mismatch returns 401 Unauthorized")
    void signatureMismatchRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/orders");
        String body = "{\"amount\":1000}";
        request.setContent(body.getBytes());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, "invalid-bad-signature-hex");
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(RequestSigningFilter.HEADER_NONCE, "nonce-" + UUID.randomUUID());

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("invalid_signature");
    }

    @Test
    @DisplayName("Dynamic Merchant Signing Secret resolves per API credential")
    void dynamicMerchantSigningSecretResolution() throws ServletException, IOException {
        String merchantApiKey = "ek_1234_abcdef1234567890abcdef1234567890";
        String dynamicMerchantSecret = "sec_merchant_dynamic_secret_key_64hexchars";
        when(apiKeys.getSigningSecretForApiKey(merchantApiKey)).thenReturn(Optional.of(dynamicMerchantSecret));

        String body = "{\"beneficiary\":\"+233241000001\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-" + UUID.randomUUID();

        // Signed using dynamic merchant secret
        String signature = RequestSigningFilter.computeSignature(dynamicMerchantSecret, timestamp, "POST", "/v1/wallet/transfers", body);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/wallet/transfers");
        request.setContent(body.getBytes());
        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, signature);
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);
        request.addHeader(RequestSigningFilter.HEADER_API_KEY, merchantApiKey);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Enforced mode rejects non-public mutations without signature")
    void enforcedModeRejectsUnsignedMutations() throws ServletException, IOException {
        RequestSigningFilter strictFilter = new RequestSigningFilter(globalSecret, true, nonceStore, apiKeys);

        MockHttpServletRequest mutationReq = new MockHttpServletRequest("POST", "/v1/checkout/orders");
        MockHttpServletResponse mutationRes = new MockHttpServletResponse();
        strictFilter.doFilter(mutationReq, mutationRes, new MockFilterChain());

        assertThat(mutationRes.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(mutationRes.getContentAsString()).contains("missing_signature");

        // Public mutation endpoints pass through even in enforced mode
        MockHttpServletRequest publicReq = new MockHttpServletRequest("POST", "/v1/rails/mtn/callbacks/payment");
        MockHttpServletResponse publicRes = new MockHttpServletResponse();
        strictFilter.doFilter(publicReq, publicRes, new MockFilterChain());
        assertThat(publicRes.getStatus()).isEqualTo(200);

        // GET reads pass through even in enforced mode
        MockHttpServletRequest getReq = new MockHttpServletRequest("GET", "/v1/checkout/orders/123");
        MockHttpServletResponse getRes = new MockHttpServletResponse();
        strictFilter.doFilter(getReq, getRes, new MockFilterChain());
        assertThat(getRes.getStatus()).isEqualTo(200);
    }

    @Test
    void versionTwoRejectsChangedNonceAndQuery() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String path = "/v1/payouts";
        String nonce = "unique-" + UUID.randomUUID();
        String signature = RequestSigningFilter.computeSignatureV2(globalSecret, timestamp, "POST",
                path, "mode=standard", nonce, "idem-1", "{}");
        for (String changed : List.of("nonce", "query", "idempotency")) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            req.setQueryString(changed.equals("query") ? "mode=other" : "mode=standard");
            req.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            req.addHeader(RequestSigningFilter.HEADER_SIGNATURE, signature);
            req.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
            req.addHeader(RequestSigningFilter.HEADER_NONCE, changed.equals("nonce") ? "other-nonce" : nonce);
            req.addHeader("X-Elepay-Signature-Version", "2");
            req.addHeader("Idempotency-Key", changed.equals("idempotency") ? "idem-2" : "idem-1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(401);
        }
        MockHttpServletRequest valid = new MockHttpServletRequest("POST", path);
        valid.setQueryString("mode=standard");
        valid.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        valid.addHeader(RequestSigningFilter.HEADER_SIGNATURE, signature);
        valid.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        valid.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);
        valid.addHeader("X-Elepay-Signature-Version", "2");
        valid.addHeader("Idempotency-Key", "idem-1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(valid, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void replayStoreFailureStopsRequest() throws Exception {
        DistributedNonceStore store = mock(DistributedNonceStore.class);
        when(store.recordNonceIfAbsent(anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        RequestSigningFilter guarded = new RequestSigningFilter(globalSecret, false, store, apiKeys);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/payouts");
        req.addHeader(RequestSigningFilter.HEADER_SIGNATURE,
                RequestSigningFilter.computeSignature(globalSecret, ts, "POST", "/v1/payouts", ""));
        req.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, ts);
        req.addHeader(RequestSigningFilter.HEADER_NONCE, "outage-nonce");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        guarded.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }
}
