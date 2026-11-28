package com.elepay.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FirebasePushServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void liveFcmHttpV1ExchangesRs256JwtCachesTokenAndDeactivatesStaleTokens() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String pemEscaped = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(kp.getPrivate().getEncoded())
                        .replace("\n", "\\n")
                + "\\n-----END PRIVATE KEY-----";

        AtomicInteger oauthCalls = new AtomicInteger();
        List<String> receivedPayloads = new CopyOnWriteArrayList<>();
        List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/token", exchange -> {
                oauthCalls.incrementAndGet();
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String assertion = null;
                for (String pair : form.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 && "assertion".equals(kv[0])) {
                        assertion = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    }
                }
                assertThat(assertion).isNotNull();
                String[] parts = assertion.split("\\.");
                assertThat(parts).hasSize(3);

                // Verify RS256 signature with the RSA public key
                try {
                    Signature verifier = Signature.getInstance("SHA256withRSA");
                    verifier.initVerify(kp.getPublic());
                    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
                    boolean valid = verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
                    assertThat(valid).isTrue();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
                assertThat(claims.path("iss").asString()).isEqualTo("fcm-bot@elepay-test.iam.gserviceaccount.com");
                assertThat(claims.path("scope").asString()).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");

                byte[] resp = "{\"access_token\":\"ya29.test-fcm-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(resp);
                }
            });

            server.createContext("/v1/projects/elepay-test/messages:send", exchange -> {
                receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                receivedPayloads.add(body);
                JsonNode parsed = JSON.readTree(body);
                String token = parsed.path("message").path("token").asString();
                if (token.startsWith("stale-")) {
                    byte[] err = """
                            {"error":{"code":404,"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, err.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(err);
                    }
                } else {
                    byte[] ok = "{\"name\":\"projects/elepay-test/messages/0:12345\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, ok.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(ok);
                    }
                }
            });

            server.start();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

            JdbcClient db = mock(JdbcClient.class);
            JdbcClient.StatementSpec stmt = mock(JdbcClient.StatementSpec.class);
            when(db.sql(anyString())).thenReturn(stmt);
            when(stmt.param(anyString(), any())).thenReturn(stmt);
            when(stmt.update()).thenReturn(1);

            OutboundUrlGuard urlGuard = new OutboundUrlGuard(true, true, false, Set.of("127.0.0.1"), Set.of(), Set.of("127.0.0.1"));
            FirebasePushService service = new FirebasePushService(
                    db,
                    JSON,
                    urlGuard,
                    new MockEnvironment(),
                    true,
                    "elepay-test",
                    "fcm-bot@elepay-test.iam.gserviceaccount.com",
                    pemEscaped,
                    baseUrl + "/token",
                    baseUrl
            );

            // 1. Valid token delivery
            var res1 = service.sendToToken(
                    "valid-device-token-1234567890",
                    "Payment Received",
                    "ELEPAY: payment received GHS 25.00 ref REF-1",
                    Map.of("type", "checkout.paid", "reference", "REF-1")
            );
            assertThat(res1.delivered()).isEqualTo(1);
            assertThat(res1.deactivated()).isZero();
            assertThat(res1.failed()).isZero();

            // 2. Stale token triggers auto-deactivation and reuses cached OAuth2 access token
            var res2 = service.sendToToken(
                    "stale-device-token-0987654321",
                    "Payment Received",
                    "ELEPAY: payment received GHS 10.00 ref REF-2",
                    Map.of("type", "checkout.paid")
            );
            assertThat(res2.delivered()).isZero();
            assertThat(res2.deactivated()).isEqualTo(1);
            assertThat(res2.failed()).isZero();

            // OAuth2 token endpoint was called only once (cached)
            assertThat(oauthCalls.get()).isEqualTo(1);
            assertThat(receivedAuthHeaders).containsExactly("Bearer ya29.test-fcm-token", "Bearer ya29.test-fcm-token");
            assertThat(receivedPayloads).hasSize(2);
            JsonNode firstMsg = JSON.readTree(receivedPayloads.get(0)).path("message");
            assertThat(firstMsg.path("notification").path("title").asString()).isEqualTo("Payment Received");
            assertThat(firstMsg.path("data").path("reference").asString()).isEqualTo("REF-1");
            verify(db).sql("UPDATE push_device_token SET active = FALSE, updated_at = now() WHERE fcm_token = :token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enabledWithoutCredentialsFailsClosed() {
        JdbcClient db = mock(JdbcClient.class);
        OutboundUrlGuard urlGuard = new OutboundUrlGuard(false, false);

        assertThatThrownBy(() -> new FirebasePushService(
                db, JSON, urlGuard, new MockEnvironment(),
                true, "", "", "",
                "https://oauth2.googleapis.com/token",
                "https://fcm.googleapis.com"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ELEPAY_FCM_PROJECT_ID");
    }

    @Test
    void disabledInNonProdCapturesMaskedDispatches() {
        JdbcClient db = mock(JdbcClient.class);
        OutboundUrlGuard urlGuard = new OutboundUrlGuard(false, false);
        FirebasePushService service = new FirebasePushService(
                db, JSON, urlGuard, new MockEnvironment(),
                false, "", "", "",
                "https://oauth2.googleapis.com/token",
                "https://fcm.googleapis.com"
        );

        var res = service.sendToToken(
                "abcdefghijklmnopqrstuvwx1234",
                "Order Ready",
                "Table 4 order ready",
                Map.of("type", "kitchen.ready")
        );
        assertThat(res.delivered()).isEqualTo(1);
        assertThat(service.recentDispatches()).hasSize(1);
        assertThat(service.recentDispatches().get(0).maskedToken()).isEqualTo("abcdef...1234");
    }
}
