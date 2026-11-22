package com.elepay.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Algorithm 077: Request Signing & Replay Protection")
class RequestSigningFilterTest {

    private static final String SECRET = "test-secret-key-for-request-signing-32b!";
    private RequestSigningFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setup() {
        filter = new RequestSigningFilter(SECRET);
        chain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("Unsigned request passes through unchanged")
    void unsignedRequestPasses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/merchants");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);
        verify(chain, times(1)).doFilter(any(), any());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Valid signed request with timestamp and nonce passes verification")
    void validSignedRequestPasses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/merchants/payouts");
        String body = "{\"amount\":10000}";
        request.setContent(body.getBytes());

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String sig = RequestSigningFilter.computeSignature(SECRET, timestamp, "POST", "/v1/merchants/payouts", body);

        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, sig);
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Expired timestamp outside 300s window is rejected with 401")
    void expiredTimestampRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/merchants/payouts");
        String body = "{\"amount\":10000}";
        request.setContent(body.getBytes());

        // 301 seconds in the past
        String timestamp = String.valueOf(Instant.now().getEpochSecond() - 301);
        String nonce = UUID.randomUUID().toString();
        String sig = RequestSigningFilter.computeSignature(SECRET, timestamp, "POST", "/v1/merchants/payouts", body);

        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, sig);
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("Replayed request with identical nonce is rejected with 409 Conflict")
    void replayedNonceRejected() throws ServletException, IOException {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "fixed-nonce-" + UUID.randomUUID();
        String body = "{\"amount\":5000}";
        String sig = RequestSigningFilter.computeSignature(SECRET, timestamp, "POST", "/v1/merchants/payouts", body);

        // First attempt succeeds
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/v1/merchants/payouts");
        req1.setContent(body.getBytes());
        req1.addHeader(RequestSigningFilter.HEADER_SIGNATURE, sig);
        req1.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        req1.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(req1, res1, chain);
        assertEquals(200, res1.getStatus());

        // Replay attempt with same nonce returns 409
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/v1/merchants/payouts");
        req2.setContent(body.getBytes());
        req2.addHeader(RequestSigningFilter.HEADER_SIGNATURE, sig);
        req2.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        req2.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, chain);
        assertEquals(409, res2.getStatus());
    }

    @Test
    @DisplayName("Tampered body causes HMAC signature mismatch and returns 401")
    void tamperedBodyRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/merchants/payouts");
        String originalBody = "{\"amount\":10000}";
        String tamperedBody = "{\"amount\":99999}";
        request.setContent(tamperedBody.getBytes());

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String sig = RequestSigningFilter.computeSignature(SECRET, timestamp, "POST", "/v1/merchants/payouts", originalBody);

        request.addHeader(RequestSigningFilter.HEADER_SIGNATURE, sig);
        request.addHeader(RequestSigningFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(RequestSigningFilter.HEADER_NONCE, nonce);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(401, response.getStatus());
    }
}
