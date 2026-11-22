package com.elepay.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Algorithm 077: Request Signing & Replay Protection.
 * Verifies signed requests on privileged endpoints:
 * - X-Elepay-Signature: HMAC-SHA256(secret, timestamp + "\n" + method + "\n" + path + "\n" + body)
 * - X-Elepay-Timestamp: validated against a 300-second window
 * - X-Elepay-Nonce: recorded and verified to prevent replay attacks
 */
@Component
public class RequestSigningFilter extends OncePerRequestFilter {
    public static final String HEADER_SIGNATURE = "X-Elepay-Signature";
    public static final String HEADER_TIMESTAMP = "X-Elepay-Timestamp";
    public static final String HEADER_NONCE = "X-Elepay-Nonce";
    public static final long MAX_SKEW_SECONDS = 300L;

    private final String signingSecret;
    private final Map<String, Instant> seenNonces = new ConcurrentHashMap<>();

    public RequestSigningFilter(@Value("${elepay.security.signing-secret:elepay-default-signing-secret-key-32b}") String signingSecret) {
        this.signingSecret = signingSecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String signature = request.getHeader(HEADER_SIGNATURE);
        if (signature == null || signature.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        // 1. Validate Timestamp (within 300s window)
        String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
        if (timestampHeader == null || timestampHeader.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"missing_timestamp\",\"message\":\"X-Elepay-Timestamp header required\"}");
            return;
        }

        long timestampSeconds;
        try {
            long parsed = Long.parseLong(timestampHeader.trim());
            timestampSeconds = parsed > 1_000_000_000_000L ? parsed / 1000L : parsed;
        } catch (NumberFormatException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"invalid_timestamp\",\"message\":\"X-Elepay-Timestamp must be epoch seconds\"}");
            return;
        }

        long nowSeconds = Instant.now().getEpochSecond();
        if (Math.abs(nowSeconds - timestampSeconds) > MAX_SKEW_SECONDS) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"request_expired\",\"message\":\"Timestamp outside 300-second window\"}");
            return;
        }

        // 2. Validate Nonce (Replay Protection)
        String nonce = request.getHeader(HEADER_NONCE);
        if (nonce == null || nonce.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"missing_nonce\",\"message\":\"X-Elepay-Nonce header required\"}");
            return;
        }

        pruneOldNonces(nowSeconds);
        if (seenNonces.putIfAbsent(nonce, Instant.now()) != null) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"replay_detected\",\"message\":\"Nonce has already been used\"}");
            return;
        }

        // 3. Cache Body and Validate Signature
        CachedBodyRequestWrapper wrappedRequest = new CachedBodyRequestWrapper(request);
        String body = wrappedRequest.getBodyString();
        String method = request.getMethod().toUpperCase();
        String path = request.getRequestURI();

        String canonicalPayload = timestampHeader.trim() + "\n" + method + "\n" + path + "\n" + body;
        String calculatedSig = hmacSha256(canonicalPayload, signingSecret);

        if (!MessageDigest.isEqual(calculatedSig.toLowerCase().getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"invalid_signature\",\"message\":\"HMAC signature mismatch\"}");
            return;
        }

        chain.doFilter(wrappedRequest, response);
    }

    public static String computeSignature(String secret, String timestamp, String method, String path, String body) {
        String canonical = timestamp + "\n" + method.toUpperCase() + "\n" + path + "\n" + body;
        return hmacSha256(canonical, secret);
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    private void pruneOldNonces(long nowSeconds) {
        if (seenNonces.size() > 5000) {
            seenNonces.entrySet().removeIf(entry -> Math.abs(nowSeconds - entry.getValue().getEpochSecond()) > MAX_SKEW_SECONDS * 2);
        }
    }

    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        public CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            InputStream is = request.getInputStream();
            this.cachedBody = is.readAllBytes();
        }

        public String getBodyString() {
            return new String(cachedBody, StandardCharsets.UTF_8);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {}

                @Override
                public int read() {
                    return bais.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
