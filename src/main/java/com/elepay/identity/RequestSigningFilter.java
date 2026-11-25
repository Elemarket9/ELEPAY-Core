package com.elepay.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Algorithm 077: Request Signing & Replay Protection.
 * Verifies signed requests on privileged and mutation endpoints:
 * - X-Elepay-Signature: HMAC-SHA256(secret, timestamp + "\n" + method + "\n" + path + "\n" + body)
 * - X-Elepay-Timestamp: validated against a strict 300-second window
 * - X-Elepay-Nonce: verified and recorded via distributed nonce store to prevent replay attacks
 * - Dynamic Merchant Signing Secrets: resolves cryptographically secure signing secrets per credential
 */
@Component
public class RequestSigningFilter extends OncePerRequestFilter {
    public static final String HEADER_SIGNATURE = "X-Elepay-Signature";
    public static final String HEADER_TIMESTAMP = "X-Elepay-Timestamp";
    public static final String HEADER_NONCE = "X-Elepay-Nonce";
    public static final String HEADER_API_KEY = "X-Elepay-Api-Key";
    public static final String HEADER_ENFORCE = "X-Elepay-Enforce-Signature";
    public static final long MAX_SKEW_SECONDS = 300L;

    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");
    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/sandbox/", "/actuator/", "/v1/public/", "/v1/fx/partners/",
            "/v1/ussd/", "/dev/", "/developer/", "/v3/api-docs", "/index.html", "/pay/"
    );

    private final String defaultSigningSecret;
    private final boolean enforceSigning;
    @Value("${elepay.security.request-signing.require-v2:false}")
    private boolean requireV2;
    private final DistributedNonceStore nonceStore;
    private final ApiKeys apiKeys;

    public RequestSigningFilter(String defaultSigningSecret) {
        this(defaultSigningSecret, false, new DistributedNonceStore(), null);
    }

    @Autowired
    public RequestSigningFilter(
            @Value("${elepay.security.signing-secret:}") String defaultSigningSecret,
            @Value("${elepay.security.request-signing.enforce:false}") boolean enforceSigning,
            DistributedNonceStore nonceStore,
            @Autowired(required = false) ApiKeys apiKeys) {
        this.defaultSigningSecret = defaultSigningSecret;
        this.enforceSigning = enforceSigning;
        this.nonceStore = nonceStore;
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = RequestPaths.normalized(request);
        if (path == null) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_path", "The request path is not allowed");
            return;
        }
        String method = request.getMethod().toUpperCase();
        boolean isMutation = MUTATION_METHODS.contains(method);
        boolean isPublic = isPublicPath(path);
        boolean enforceHeader = "true".equalsIgnoreCase(request.getHeader(HEADER_ENFORCE));
        boolean apiCredential = request.getHeader(HEADER_API_KEY) != null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isOidcJwtSession = auth instanceof JwtAuthenticationToken && auth.isAuthenticated() && !apiCredential;
        boolean isOpsPath = path.startsWith("/v1/ops/") || path.equals("/v1/ops");
        boolean isAdminPath = path.startsWith("/admin/") || path.equals("/admin");
        boolean isPrivilegedOpsOrAdmin = isAdminPath || (isOpsPath && !isOidcJwtSession);
        boolean requireSignature = (enforceHeader
                || (enforceSigning && (!requireV2 || apiCredential || isPrivilegedOpsOrAdmin)))
                && isMutation && !isPublic;

        String signature = request.getHeader(HEADER_SIGNATURE);
        if (signature == null || signature.isBlank()) {
            if (requireSignature) {
                sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "missing_signature",
                        "X-Elepay-Signature header required for non-public mutations");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // 1. Validate Timestamp (within strict 300s window)
        String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
        if (timestampHeader == null || timestampHeader.isBlank()) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "missing_timestamp",
                    "X-Elepay-Timestamp header required");
            return;
        }

        long timestampSeconds;
        try {
            long parsed = Long.parseLong(timestampHeader.trim());
            timestampSeconds = parsed > 1_000_000_000_000L ? parsed / 1000L : parsed;
        } catch (NumberFormatException e) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_timestamp",
                    "X-Elepay-Timestamp must be epoch seconds");
            return;
        }

        long nowSeconds = Instant.now().getEpochSecond();
        if (timestampSeconds < nowSeconds - MAX_SKEW_SECONDS || timestampSeconds > nowSeconds + MAX_SKEW_SECONDS) {
            sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "request_expired",
                    "Timestamp outside 300-second window");
            return;
        }

        // 2. Validate Nonce (Distributed Replay Protection)
        String nonce = request.getHeader(HEADER_NONCE);
        if (nonce == null || nonce.isBlank()) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "missing_nonce",
                    "X-Elepay-Nonce header required");
            return;
        }

        // 3. Resolve Dynamic Signing Secret per API Credential
        String signingSecret = resolveSigningSecret(request);
        if (signingSecret == null || signingSecret.isBlank()) {
            // No per-key secret and no platform secret: a signature cannot be verified, so it is not accepted.
            sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "signing_not_configured",
                    "Request signing is not configured for this credential");
            return;
        }

        // 4. Cache Body and Validate HMAC-SHA256 Signature
        CachedBodyRequestWrapper wrappedRequest = new CachedBodyRequestWrapper(request);
        String body = wrappedRequest.getBodyString();

        String canonicalPayload = timestampHeader.trim() + "\n" + method + "\n" + path + "\n" + body;
        String version = request.getHeader("X-Elepay-Signature-Version");
        boolean v2 = "2".equals(version);
        if ((requireV2 && !v2) || (version != null && !v2 && !"1".equals(version))) {
            sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "signature_version_required", "Use signature version 2");
            return;
        }
        if (nonce.length() > 128 || !nonce.matches("[A-Za-z0-9._~-]+")) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_nonce", "Invalid nonce");
            return;
        }
        if (v2) {
            canonicalPayload = canonicalV2(timestampHeader.trim(), method, path, request.getQueryString(),
                    nonce, request.getHeader("Idempotency-Key"), body);
        }
        String calculatedSig = hmacSha256(canonicalPayload, signingSecret);

        if (!MessageDigest.isEqual(calculatedSig.toLowerCase().getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_signature",
                    "HMAC signature mismatch");
            return;
        }

        // Authenticate first. Legacy signatures are deduplicated even if the unsigned nonce changes.
        try {
            String replayKey = com.elepay.common.Idempotency.sha256(v2
                    ? "v2:" + hmacSha256(nonce, signingSecret) : "v1:" + calculatedSig);
            if (!nonceStore.recordNonceIfAbsent(replayKey, Duration.ofSeconds(MAX_SKEW_SECONDS * 2 + 1))) {
                sendJsonError(response, HttpServletResponse.SC_CONFLICT, "replay_detected", "Request has already been used");
                return;
            }
        } catch (RuntimeException unavailable) {
            sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "replay_store_unavailable", "Retry later with a fresh signed request");
            return;
        }
        chain.doFilter(wrappedRequest, response);
    }

    private boolean isPublicPath(String path) {
        if (path == null) return false;
        if (path.matches("/v1/rails/[^/]+/callbacks(?:/.*)?")) return true;
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    private String resolveSigningSecret(HttpServletRequest request) {
        // A. From authenticated principal if ApiKeyAuthentication
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthentication apiKeyAuth && apiKeyAuth.signingSecret() != null) {
            return apiKeyAuth.signingSecret();
        }

        // B. From X-Elepay-Api-Key header if ApiKeys service is available
        String apiKeyHeader = request.getHeader(HEADER_API_KEY);
        if (apiKeyHeader != null && apiKeys != null) {
            var secretOpt = apiKeys.getSigningSecretForApiKey(apiKeyHeader.trim());
            if (secretOpt.isPresent() && !secretOpt.get().isBlank()) {
                return secretOpt.get();
            }
        }

        return apiKeyHeader != null ? null : defaultSigningSecret;
    }

    private void sendJsonError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"detail\":\"" + message + "\",\"message\":\"" + message + "\",\"status\":" + status + "}");
    }

    public static String computeSignature(String secret, String timestamp, String method, String path, String body) {
        String canonical = timestamp + "\n" + method.toUpperCase() + "\n" + path + "\n" + (body == null ? "" : body);
        return hmacSha256(canonical, secret);
    }

    public static String computeSignatureV2(String secret, String timestamp, String method, String path,
                                             String query, String nonce, String idempotencyKey, String body) {
        return hmacSha256(canonicalV2(timestamp, method.toUpperCase(java.util.Locale.ROOT), path, query,
                nonce, idempotencyKey, body), secret);
    }

    static String canonicalV2(String timestamp, String method, String path, String query, String nonce,
                              String idempotencyKey, String body) {
        StringBuilder result = new StringBuilder("elepay-request:v2");
        for (String field : new String[]{timestamp, method, path, query, nonce, idempotencyKey, body}) {
            String value = field == null ? "" : field;
            result.append(':').append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
        return result.toString();
    }

    public static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        public CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            InputStream is = request.getInputStream();
            this.cachedBody = is != null ? is.readAllBytes() : new byte[0];
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
