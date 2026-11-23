package com.elepay.rails;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("Telecel Cash Production Rail Unit Tests")
class TelecelCashProductionRailTest {
    private final ObjectMapper json = new ObjectMapper();
    private NetworkHealthDetector health;
    private HttpServer testServer;
    private URI serverUri;

    @BeforeEach
    void setUp() {
        health = mock(NetworkHealthDetector.class);
    }

    @AfterEach
    void tearDown() {
        if (testServer != null) {
            testServer.stop(0);
        }
    }

    private void startServer(String tokenResponse, int submitStatus, String submitResponse, int queryStatus, String queryResponse) throws IOException {
        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/v1/oauth2/token", exchange -> {
            byte[] bytes = tokenResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.createContext("/v1/payments/collections", exchange -> {
            byte[] bytes = submitResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(submitStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.createContext("/v1/payments/disbursements", exchange -> {
            byte[] bytes = submitResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(submitStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.createContext("/v1/payments/status/", exchange -> {
            byte[] bytes = queryResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(queryStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.start();
        serverUri = URI.create("http://127.0.0.1:" + testServer.getAddress().getPort());
    }

    @Test
    void productionHostVerification() {
        assertThatThrownBy(() -> TelecelCashProductionRail.productionBase("https://untrusted.telecel-fake.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TelecelCashProductionRail.productionBase("http://api.telecel.com.gh"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TelecelCashProductionRail.productionBase("https://api.telecel.com.gh").getHost())
                .isEqualTo("api.telecel.com.gh");
        assertThat(TelecelCashProductionRail.productionBase("https://vodacash.vodafone.com.gh").getHost())
                .isEqualTo("vodacash.vodafone.com.gh");
    }

    @Test
    void verifiesPartnerFeeSchedules() {
        // 0.75% capped at 750 pesewas
        assertThat(TelecelCashProductionRail.calculateFee(50_000L)).isEqualTo(375L);
        assertThat(TelecelCashProductionRail.calculateFee(200_000L)).isEqualTo(750L);
    }

    @Test
    void unconfiguredCredentialsReturnsUnavailable() {
        TelecelCashProductionRail unconfigured = new TelecelCashProductionRail(
                json, health, null, null, null, null, null
        );

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "TEL-CHARGE-1", 5000L, "GHS", "momo", "0201234567",
                "telecel", "u@test.com", "User", "Test", null
        );
        PaymentRail.Result result = unconfigured.charge(charge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(result.message()).contains("credentials not configured");

        PaymentRail.Payout payout = new PaymentRail.Payout(
                "TEL-PAYOUT-1", 5000L, "GHS", "momo", "telecel", "0201234567", "User", "Payout"
        );
        assertThat(unconfigured.payout(payout).status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(unconfigured.query("charge", "TEL-CHARGE-1", null).status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
    }

    @Test
    void chargeCollectionAndDisbursementSubmitSuccess() throws Exception {
        startServer(
                "{\"access_token\":\"telecel-test-token\",\"expires_in\":3600}",
                202, "{\"reference\":\"TEL-123\",\"status\":\"PENDING\"}",
                200, "{\"reference\":\"TEL-123\",\"status\":\"SUCCESSFUL\",\"amount\":\"50.00\",\"currency\":\"GHS\",\"transactionId\":\"T-999\"}"
        );

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        TelecelCashProductionRail.Credentials creds = new TelecelCashProductionRail.Credentials(
                "tel-client", "tel-secret", "tel-merch", "tel-webhook"
        );
        TelecelCashProductionRail rail = new TelecelCashProductionRail(json, health, serverUri, creds, client);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "TEL-123", 5000L, "GHS", "momo", "0201234567",
                "telecel", "u@test.com", "User", "Charge", null
        );
        PaymentRail.Result chargeResult = rail.charge(charge);
        assertThat(chargeResult.status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(chargeResult.railReference()).isEqualTo("TEL-TEL-123");
        assertThat(chargeResult.fee()).isEqualTo(37L); // 0.75% of 5000 pesewas = 37.5 -> 37 pesewas

        PaymentRail.Payout payout = new PaymentRail.Payout(
                "TEL-PAYOUT-1", 10000L, "GHS", "momo", "telecel", "0201234567", "User", "Payout"
        );
        PaymentRail.Result payoutResult = rail.payout(payout);
        assertThat(payoutResult.status()).isEqualTo(PaymentRail.Status.PENDING);
    }

    @Test
    void queryParsesStatusesCorrectly() throws Exception {
        startServer(
                "{\"access_token\":\"telecel-test-token\",\"expires_in\":3600}",
                202, "",
                200, "{\"reference\":\"TEL-123\",\"status\":\"SUCCESSFUL\",\"amount\":\"50.00\",\"currency\":\"GHS\",\"transactionId\":\"T-999\"}"
        );

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        TelecelCashProductionRail.Credentials creds = new TelecelCashProductionRail.Credentials(
                "tel-client", "tel-secret", "tel-merch", "tel-webhook"
        );
        TelecelCashProductionRail rail = new TelecelCashProductionRail(json, health, serverUri, creds, client);

        PaymentRail.Result queryResult = rail.query("charge", "TEL-123", null);
        assertThat(queryResult.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(queryResult.amount()).isEqualTo(5000L);
        assertThat(queryResult.railReference()).isEqualTo("T-999");
        assertThat(queryResult.fee()).isEqualTo(37L);
    }

    @Test
    void parseStatusEdgeCases() {
        var succ = json.readTree("""
                {"reference":"REF-1","status":"COMPLETED","amount":"100.00","currency":"GHS","financialTransactionId":"FIN-1"}
                """);
        assertThat(TelecelCashProductionRail.parseStatus(succ, "REF-1").status()).isEqualTo(PaymentRail.Status.SUCCEEDED);

        var failed = json.readTree("""
                {"reference":"REF-1","status":"DECLINED","reason":"Insufficient balance"}
                """);
        assertThat(TelecelCashProductionRail.parseStatus(failed, "REF-1").status()).isEqualTo(PaymentRail.Status.FAILED);

        var pending = json.readTree("""
                {"reference":"REF-1","status":"PROCESSING"}
                """);
        assertThat(TelecelCashProductionRail.parseStatus(pending, "REF-1").status()).isEqualTo(PaymentRail.Status.PENDING);

        var mismatch = json.readTree("""
                {"reference":"OTHER","status":"COMPLETED","amount":"100.00","currency":"GHS","financialTransactionId":"FIN-1"}
                """);
        assertThat(TelecelCashProductionRail.parseStatus(mismatch, "REF-1").status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }

    @Test
    void callbackSignatureVerification() {
        TelecelCashProductionRail.Credentials creds = new TelecelCashProductionRail.Credentials(
                "tel-client", "tel-secret", "tel-merch", "test-secret"
        );
        TelecelCashProductionRail rail = new TelecelCashProductionRail(
                json, health, URI.create("https://api.telecel.com.gh"), creds, null
        );

        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"TEL-100\",\"status\":\"SUCCESSFUL\",\"amount\":5000,\"currency\":\"GHS\"}}";
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String nonce = "telecel-nonce-1";
        String validSig = TelecelCashProductionRail.computeHmac("test-secret", timestamp + "." + nonce + "." + body);

        // Valid signature passes
        PaymentRail.Callback callback = rail.verifyCallback(Map.of(
                "x-telecel-signature", validSig, "x-callback-timestamp", timestamp, "x-callback-nonce", nonce), body);
        assertThat(callback.result().status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(callback.result().amount()).isEqualTo(5000L);
        assertThat(callback.reference()).isEqualTo("TEL-100");

        // Invalid signature throws
        assertThatThrownBy(() -> rail.verifyCallback(Map.of(
                "x-telecel-signature", "invalid_sig", "x-callback-timestamp", timestamp, "x-callback-nonce", "telecel-nonce-2"), body))
                .isInstanceOf(PaymentRail.InvalidCallbackException.class)
                .hasMessageContaining("signature");

        // Missing signature throws
        assertThatThrownBy(() -> rail.verifyCallback(Map.of(), body))
                .isInstanceOf(PaymentRail.InvalidCallbackException.class);
    }

    @Test
    void tokenRefreshRetriesOn401() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger(0);
        AtomicInteger submitRequests = new AtomicInteger(0);

        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/v1/oauth2/token", exchange -> {
            tokenRequests.incrementAndGet();
            byte[] bytes = "{\"access_token\":\"fresh-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.createContext("/v1/payments/collections", exchange -> {
            int count = submitRequests.incrementAndGet();
            if (count == 1) {
                exchange.sendResponseHeaders(401, 0);
                exchange.close();
            } else {
                exchange.sendResponseHeaders(202, 0);
                exchange.close();
            }
        });
        testServer.start();
        serverUri = URI.create("http://127.0.0.1:" + testServer.getAddress().getPort());

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        TelecelCashProductionRail.Credentials creds = new TelecelCashProductionRail.Credentials(
                "tel-client", "tel-secret", "tel-merch", "tel-webhook"
        );
        TelecelCashProductionRail rail = new TelecelCashProductionRail(json, health, serverUri, creds, client);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "REF-TEL-REFRESH", 3000L, "GHS", "momo", "0201234567",
                "telecel", "u@test.com", "User", "Charge", null
        );
        PaymentRail.Result result = rail.charge(charge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(tokenRequests.get()).isEqualTo(2);
        assertThat(submitRequests.get()).isEqualTo(2);
    }
}
