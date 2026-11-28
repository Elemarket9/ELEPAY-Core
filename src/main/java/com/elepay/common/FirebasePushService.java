package com.elepay.common;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Firebase Cloud Messaging (FCM HTTP v1) push notification service and device token registry.
 *
 * <p>Uses Google OAuth2 service-account JWT exchange (RS256) against {@code oauth2.googleapis.com/token}
 * and sends messages to {@code fcm.googleapis.com/v1/projects/{projectId}/messages:send}. No external
 * Firebase Admin SDK is required and no credentials are ever hardcoded.
 *
 * <p>When {@code elepay.notifications.fcm.enabled=false} (default in local/dev/test), device tokens are
 * still persisted and dispatches are captured in-memory in non-production profiles without leaking raw
 * device tokens to logs. When {@code enabled=true}, service-account credentials are validated fail-closed
 * and stale/unregistered FCM tokens are automatically deactivated on HTTP 404 / {@code UNREGISTERED}.
 */
@Service
public class FirebasePushService {
    private static final Logger log = LoggerFactory.getLogger(FirebasePushService.class);
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Set<String> ALLOWED_PLATFORMS = Set.of("android", "ios", "web");
    private static final Set<String> STALE_TOKEN_ERRORS = Set.of("UNREGISTERED", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH");
    private static final int MAX_CAPTURED_DISPATCHES = 200;

    public record RegisteredDevice(
            UUID id,
            String subject,
            UUID merchantId,
            String platform,
            String maskedToken,
            String appId,
            boolean active,
            OffsetDateTime lastSeenAt
    ) {}

    public record PushDispatchResult(
            int attempted,
            int delivered,
            int deactivated,
            int failed
    ) {}

    public record CapturedDispatch(
            String maskedToken,
            String title,
            String body,
            Map<String, String> data,
            Instant timestamp
    ) {}

    private record CachedAccessToken(String token, Instant expiresAt) {
        boolean isUsable(Instant now) {
            return token != null && !token.isBlank() && expiresAt != null && now.isBefore(expiresAt.minusSeconds(60));
        }
    }

    private final JdbcClient db;
    private final ObjectMapper json;
    private final OutboundUrlGuard urlGuard;
    private final Environment env;
    private final HttpClient httpClient;

    private final boolean enabled;
    private final String projectId;
    private final String clientEmail;
    private final String privateKeyPem;
    private final String tokenUri;
    private final String fcmBaseUrl;

    private final Object tokenLock = new Object();
    private volatile CachedAccessToken cachedToken;
    private volatile PrivateKey parsedPrivateKey;

    private final List<CapturedDispatch> capturedDispatches = new CopyOnWriteArrayList<>();

    @Autowired
    public FirebasePushService(
            JdbcClient db,
            ObjectMapper json,
            OutboundUrlGuard urlGuard,
            Environment env,
            @Value("${elepay.notifications.fcm.enabled:false}") boolean enabled,
            @Value("${elepay.notifications.fcm.project-id:}") String projectId,
            @Value("${elepay.notifications.fcm.client-email:}") String clientEmail,
            @Value("${elepay.notifications.fcm.private-key:}") String privateKeyPem,
            @Value("${elepay.notifications.fcm.token-uri:https://oauth2.googleapis.com/token}") String tokenUri,
            @Value("${elepay.notifications.fcm.base-url:https://fcm.googleapis.com}") String fcmBaseUrl) {
        this.db = db;
        this.json = json;
        this.urlGuard = urlGuard;
        this.env = env;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.enabled = enabled;
        this.projectId = trim(projectId);
        this.clientEmail = trim(clientEmail);
        this.privateKeyPem = privateKeyPem == null ? "" : privateKeyPem.trim();
        this.tokenUri = trim(tokenUri).isEmpty() ? "https://oauth2.googleapis.com/token" : trim(tokenUri);
        this.fcmBaseUrl = trim(fcmBaseUrl).isEmpty()
                ? "https://fcm.googleapis.com"
                : trim(fcmBaseUrl).replaceAll("/+$", "");

        if (this.enabled) {
            validateLiveConfiguration();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<CapturedDispatch> recentDispatches() {
        return Collections.unmodifiableList(capturedDispatches);
    }

    public void clearCapturedDispatches() {
        capturedDispatches.clear();
    }

    /**
     * Registers or refreshes an FCM device token for an authenticated subject.
     * Rebinds the token if the physical device changes user accounts on sign-in.
     */
    @Transactional
    public RegisteredDevice registerDevice(String subject, UUID merchantId, String platform, String fcmToken, String appId) {
        if (subject == null || subject.isBlank()) {
            throw ApiException.badRequest("subject_required", "Authenticated subject is required");
        }
        String normalizedPlatform = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PLATFORMS.contains(normalizedPlatform)) {
            throw ApiException.badRequest("invalid_platform", "Platform must be one of: android, ios, web");
        }
        String normalizedToken = fcmToken == null ? "" : fcmToken.trim();
        if (normalizedToken.length() < 20 || normalizedToken.length() > 4096 || containsWhitespace(normalizedToken)) {
            throw ApiException.badRequest("invalid_fcm_token", "FCM device token is malformed");
        }
        String normalizedAppId = (appId == null || appId.isBlank()) ? "merchant-app" : appId.trim();
        if (normalizedAppId.length() > 64) {
            throw ApiException.badRequest("invalid_app_id", "appId must be 64 characters or fewer");
        }

        UUID newId = UUID.randomUUID();
        return db.sql("""
                INSERT INTO push_device_token (id, subject, merchant_id, platform, fcm_token, app_id, active, last_seen_at, created_at, updated_at)
                VALUES (:id, :subject, :merchantId, :platform, :token, :appId, TRUE, now(), now(), now())
                ON CONFLICT (fcm_token) DO UPDATE
                SET subject = EXCLUDED.subject,
                    merchant_id = EXCLUDED.merchant_id,
                    platform = EXCLUDED.platform,
                    app_id = EXCLUDED.app_id,
                    active = TRUE,
                    last_seen_at = now(),
                    updated_at = now()
                RETURNING id, subject, merchant_id, platform, fcm_token, app_id, active, last_seen_at
                """)
                .param("id", newId)
                .param("subject", subject.trim())
                .param("merchantId", merchantId)
                .param("platform", normalizedPlatform)
                .param("token", normalizedToken)
                .param("appId", normalizedAppId)
                .query((rs, rowNum) -> new RegisteredDevice(
                        rs.getObject("id", UUID.class),
                        rs.getString("subject"),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getString("platform"),
                        maskToken(rs.getString("fcm_token")),
                        rs.getString("app_id"),
                        rs.getBoolean("active"),
                        rs.getObject("last_seen_at", OffsetDateTime.class)
                ))
                .single();
    }

    /**
     * Deactivates an FCM token owned by the caller (e.g., on sign-out).
     */
    @Transactional
    public boolean unregisterDevice(String subject, String fcmToken) {
        if (subject == null || subject.isBlank() || fcmToken == null || fcmToken.isBlank()) {
            return false;
        }
        int updated = db.sql("""
                UPDATE push_device_token
                SET active = FALSE, updated_at = now()
                WHERE subject = :subject AND fcm_token = :token AND active = TRUE
                """)
                .param("subject", subject.trim())
                .param("token", fcmToken.trim())
                .update();
        return updated > 0;
    }

    /**
     * Lists active registered devices for the caller, with masked FCM tokens.
     */
    public List<RegisteredDevice> listDevices(String subject) {
        if (subject == null || subject.isBlank()) {
            return List.of();
        }
        return db.sql("""
                SELECT id, subject, merchant_id, platform, fcm_token, app_id, active, last_seen_at
                FROM push_device_token
                WHERE subject = :subject AND active = TRUE
                ORDER BY last_seen_at DESC
                """)
                .param("subject", subject.trim())
                .query((rs, rowNum) -> new RegisteredDevice(
                        rs.getObject("id", UUID.class),
                        rs.getString("subject"),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getString("platform"),
                        maskToken(rs.getString("fcm_token")),
                        rs.getString("app_id"),
                        rs.getBoolean("active"),
                        rs.getObject("last_seen_at", OffsetDateTime.class)
                ))
                .list();
    }

    /**
     * Sends a push notification to all active devices registered to a given user subject.
     */
    public PushDispatchResult sendToSubject(String subject, String title, String body, Map<String, String> data) {
        if (subject == null || subject.isBlank()) {
            return new PushDispatchResult(0, 0, 0, 0);
        }
        List<String> tokens = db.sql("""
                SELECT fcm_token FROM push_device_token
                WHERE subject = :subject AND active = TRUE
                """)
                .param("subject", subject.trim())
                .query(String.class)
                .list();
        return sendToTokens(tokens, title, body, data);
    }

    /**
     * Sends a push notification to all active devices registered to a given merchant.
     */
    public PushDispatchResult sendToMerchant(UUID merchantId, String title, String body, Map<String, String> data) {
        if (merchantId == null) {
            return new PushDispatchResult(0, 0, 0, 0);
        }
        List<String> tokens = db.sql("""
                SELECT fcm_token FROM push_device_token
                WHERE merchant_id = :merchantId AND active = TRUE
                """)
                .param("merchantId", merchantId)
                .query(String.class)
                .list();
        return sendToTokens(tokens, title, body, data);
    }

    /**
     * Sends a push notification to a single FCM token.
     */
    public PushDispatchResult sendToToken(String fcmToken, String title, String body, Map<String, String> data) {
        if (fcmToken == null || fcmToken.isBlank()) {
            return new PushDispatchResult(0, 0, 0, 0);
        }
        return sendToTokens(List.of(fcmToken.trim()), title, body, data);
    }

    private PushDispatchResult sendToTokens(List<String> tokens, String title, String body, Map<String, String> data) {
        if (tokens == null || tokens.isEmpty()) {
            return new PushDispatchResult(0, 0, 0, 0);
        }
        Map<String, String> safeData = sanitizeData(data);
        int delivered = 0;
        int deactivated = 0;
        int failed = 0;

        for (String token : tokens) {
            if (!enabled) {
                recordNonProdDispatch(token, title, body, safeData);
                delivered++;
                continue;
            }
            SendOutcome outcome = sendSingleLive(token, title, body, safeData, true);
            switch (outcome) {
                case DELIVERED -> delivered++;
                case STALE_DEACTIVATED -> {
                    deactivateToken(token);
                    deactivated++;
                }
                case FAILED -> failed++;
            }
        }
        return new PushDispatchResult(tokens.size(), delivered, deactivated, failed);
    }

    private enum SendOutcome { DELIVERED, STALE_DEACTIVATED, FAILED }

    private SendOutcome sendSingleLive(String fcmToken, String title, String body, Map<String, String> data, boolean allowAuthRetry) {
        try {
            String endpoint = fcmBaseUrl + "/v1/projects/" + projectId + "/messages:send";
            urlGuard.validatePartnerEgress(endpoint);
            String accessToken = getAccessToken();

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", fcmToken);
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("title", title == null ? "ELEPAY" : title);
            notification.put("body", body == null ? "" : body);
            message.put("notification", notification);
            if (!data.isEmpty()) {
                message.put("data", data);
            }
            message.put("android", Map.of("priority", "HIGH"));
            message.put("apns", Map.of("payload", Map.of("aps", Map.of("sound", "default"))));

            String payload = json.writeValueAsString(Map.of("message", message));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return SendOutcome.DELIVERED;
            }
            if (status == 401 && allowAuthRetry) {
                invalidateAccessToken();
                return sendSingleLive(fcmToken, title, body, data, false);
            }
            if (isStaleTokenResponse(status, response.body())) {
                log.info("Deactivating stale FCM device token {}", maskToken(fcmToken));
                return SendOutcome.STALE_DEACTIVATED;
            }
            log.warn("FCM HTTP v1 delivery failed for token {} with status {}", maskToken(fcmToken), status);
            return SendOutcome.FAILED;
        } catch (Exception e) {
            log.warn("FCM HTTP v1 delivery error for token {}: {}", maskToken(fcmToken), e.getMessage());
            return SendOutcome.FAILED;
        }
    }

    private boolean isStaleTokenResponse(int status, String responseBody) {
        if (status == 404) {
            return true;
        }
        if (status != 400 || responseBody == null || responseBody.isBlank()) {
            return false;
        }
        try {
            JsonNode root = json.readTree(responseBody);
            JsonNode error = root.path("error");
            String errorStatus = error.path("status").asString("");
            if ("NOT_FOUND".equals(errorStatus)) {
                return true;
            }
            for (JsonNode detail : error.path("details")) {
                String code = detail.path("errorCode").asString("");
                if (STALE_TOKEN_ERRORS.contains(code)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void deactivateToken(String fcmToken) {
        try {
            db.sql("UPDATE push_device_token SET active = FALSE, updated_at = now() WHERE fcm_token = :token")
                    .param("token", fcmToken)
                    .update();
        } catch (Exception e) {
            log.warn("Failed to deactivate stale FCM token {}: {}", maskToken(fcmToken), e.getMessage());
        }
    }

    private String getAccessToken() throws Exception {
        Instant now = Instant.now();
        CachedAccessToken current = cachedToken;
        if (current != null && current.isUsable(now)) {
            return current.token();
        }
        synchronized (tokenLock) {
            current = cachedToken;
            now = Instant.now();
            if (current != null && current.isUsable(now)) {
                return current.token();
            }
            urlGuard.validatePartnerEgress(tokenUri);
            String assertion = buildSignedJwtAssertion(now);
            String formBody = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                    + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Google OAuth2 token exchange failed with HTTP " + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            String accessToken = body.path("access_token").asString("");
            long expiresIn = body.path("expires_in").asLong(3600L);
            if (accessToken.isBlank()) {
                throw new IllegalStateException("Google OAuth2 token response missing access_token");
            }
            cachedToken = new CachedAccessToken(accessToken, now.plusSeconds(Math.max(120L, expiresIn)));
            return accessToken;
        }
    }

    private void invalidateAccessToken() {
        synchronized (tokenLock) {
            cachedToken = null;
        }
    }

    private String buildSignedJwtAssertion(Instant now) throws Exception {
        long iat = now.getEpochSecond();
        long exp = iat + 3600;

        String headerJson = json.writeValueAsString(Map.of("alg", "RS256", "typ", "JWT"));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientEmail);
        claims.put("scope", FCM_SCOPE);
        claims.put("aud", tokenUri);
        claims.put("iat", iat);
        claims.put("exp", exp);
        String claimsJson = json.writeValueAsString(claims);

        Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
        String signingInput = urlEncoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
                + "."
                + urlEncoder.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(getPrivateKey());
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String encodedSig = urlEncoder.encodeToString(signature.sign());
        return signingInput + "." + encodedSig;
    }

    private PrivateKey getPrivateKey() throws Exception {
        PrivateKey key = parsedPrivateKey;
        if (key != null) {
            return key;
        }
        synchronized (tokenLock) {
            if (parsedPrivateKey != null) {
                return parsedPrivateKey;
            }
            String normalized = privateKeyPem
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            parsedPrivateKey = KeyFactory.getInstance("RSA").generatePrivate(spec);
            return parsedPrivateKey;
        }
    }

    private void validateLiveConfiguration() {
        if (projectId.isBlank() || clientEmail.isBlank() || privateKeyPem.isBlank()) {
            throw new IllegalStateException(
                    "elepay.notifications.fcm.enabled=true requires ELEPAY_FCM_PROJECT_ID, "
                            + "ELEPAY_FCM_CLIENT_EMAIL, and ELEPAY_FCM_PRIVATE_KEY");
        }
        try {
            getPrivateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Invalid ELEPAY_FCM_PRIVATE_KEY (expected PKCS#8 RSA PEM)", e);
        }
    }

    private void recordNonProdDispatch(String token, String title, String body, Map<String, String> data) {
        boolean isProd = env != null && env.acceptsProfiles(Profiles.of("prod", "production"));
        if (!isProd) {
            if (capturedDispatches.size() >= MAX_CAPTURED_DISPATCHES) {
                capturedDispatches.remove(0);
            }
            capturedDispatches.add(new CapturedDispatch(maskToken(token), title, body, data, Instant.now()));
        }
        log.debug("FCM disabled; skipped live push to token {} (title='{}')", maskToken(token), title);
    }

    private static Map<String, String> sanitizeData(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null) {
                clean.put(k.trim(), v);
            }
        });
        return Collections.unmodifiableMap(clean);
    }

    private static boolean containsWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static String maskToken(String token) {
        if (token == null || token.length() <= 10) {
            return "***";
        }
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }
}
