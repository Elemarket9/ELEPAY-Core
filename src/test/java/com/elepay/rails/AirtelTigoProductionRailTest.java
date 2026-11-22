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

@DisplayName("AirtelTigo (AT Money) Production Rail Unit Tests")
class AirtelTigoProductionRailTest {
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
        assertThatThrownBy(() -> AirtelTigoProductionRail.productionBase("https://untrusted.atm-fake.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AirtelTigoProductionRail.productionBase("http://api.at.com.gh"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(AirtelTigoProductionRail.productionBase("https://api.at.com.gh").getHost())
                .isEqualTo("api.at.com.gh");
        assertThat(AirtelTigoProductionRail.productionBase("https://money.airteltigo.com.gh").getHost())
                .isEqualTo("money.airteltigo.com.gh");
    }

    @Test
    void verifiesPartnerFeeSchedules() {
        // 0.50% capped at 500 pesewas
        assertThat(AirtelTigoProductionRail.calculateFee(50_000L)).isEqualTo(250L);
        assertThat(AirtelTigoProductionRail.calculateFee(200_000L)).isEqualTo(500L);
    }

    @Test
    void unconfiguredCredentialsReturnsUnavailable() {
        AirtelTigoProductionRail unconfigured = new AirtelTigoProductionRail(
                json, health, null, null, null, null, null
        );

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "AT-CHARGE-1", 5000L, "GHS", "momo", "0271234567",
                "airteltigo", "u@test.com", "User", "Test", null
        );
        PaymentRail.Result result = unconfigured.charge(charge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(result.message()).contains("credentials not configured");

        PaymentRail.Payout payout = new PaymentRail.Payout(
                "AT-PAYOUT-1", 5000L, "GHS", "momo", "airteltigo", "0271234567", "User", "Payout"
        );
        assertThat(unconfigured.payout(payout).status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(unconfigured.query("charge", "AT-CHARGE-1", null).status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
    }

    @Test
    void chargeCollectionAndDisbursementSubmitSuccess() throws Exception {
        startServer(
                "{\"access_token\":\"at-test-token\",\"expires_in\":3600}",
                202, "{\"reference\":\"AT-123\",\"status\":\"PENDING\"}",
                200, "{\"reference\":\"AT-123\",\"status\":\"SUCCESSFUL\",\"amount\":\"50.00\",\"currency\":\"GHS\",\"transactionId\":\"T-AT-999\"}"
        );

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        AirtelTigoProductionRail.Credentials creds = new AirtelTigoProductionRail.Credentials(
                "at-client", "at-secret", "at-merch", "at-webhook"
        );
        AirtelTigoProductionRail rail = new AirtelTigoProductionRail(json, health, serverUri, creds, client);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "AT-123", 5000L, "GHS", "momo", "0271234567",
                "airteltigo", "u@test.com", "User", "Charge", null
        );
        PaymentRail.Result chargeResult = rail.charge(charge);
        assertThat(chargeResult.status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(chargeResult.railReference()).isEqualTo("AT-AT-123");
        assertThat(chargeResult.fee()).isEqualTo(25L); // 0.50% of 5000 pesewas = 25 pesewas

        PaymentRail.Payout payout = new PaymentRail.Payout(
                "AT-PAYOUT-1", 10000L, "GHS", "momo", "airteltigo", "0271234567", "User", "Payout"
        );
        PaymentRail.Result payoutResult = rail.payout(payout);
        assertThat(payoutResult.status()).isEqualTo(PaymentRail.Status.PENDING);
    }

    @Test
    void queryParsesStatusesCorrectly() throws Exception {
        startServer(
                "{\"access_token\":\"at-test-token\",\"expires_in\":3600}",
                202, "",
                200, "{\"reference\":\"AT-123\",\"status\":\"SUCCESSFUL\",\"amount\":\"50.00\",\"currency\":\"GHS\",\"transactionId\":\"T-AT-999\"}"
        );

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        AirtelTigoProductionRail.Credentials creds = new AirtelTigoProductionRail.Credentials(
                "at-client", "at-secret", "at-merch", "at-webhook"
        );
        AirtelTigoProductionRail rail = new AirtelTigoProductionRail(json, health, serverUri, creds, client);

        PaymentRail.Result queryResult = rail.query("charge", "AT-123", null);
        assertThat(queryResult.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(queryResult.amount()).isEqualTo(5000L);
        assertThat(queryResult.railReference()).isEqualTo("T-AT-999");
        assertThat(queryResult.fee()).isEqualTo(25L);
    }

    @Test
    void parseStatusEdgeCases() {
        var succ = json.readTree("""
                {"reference":"REF-AT-1","status":"COMPLETED","amount":"100.00","currency":"GHS","financialTransactionId":"FIN-AT-1"}
                """);
        assertThat(AirtelTigoProductionRail.parseStatus(succ, "REF-AT-1").status()).isEqualTo(PaymentRail.Status.SUCCEEDED);

        var failed = json.readTree("""
                {"reference":"REF-AT-1","status":"DECLINED","reason":"Insufficient balance"}
                """);
        assertThat(AirtelTigoProductionRail.parseStatus(failed, "REF-AT-1").status()).isEqualTo(PaymentRail.Status.FAILED);

        var pending = json.readTree("""
                {"reference":"REF-AT-1","status":"PROCESSING"}
                """);
        assertThat(AirtelTigoProductionRail.parseStatus(pending, "REF-AT-1").status()).isEqualTo(PaymentRail.Status.PENDING);

        var mismatch = json.readTree("""
                {"reference":"OTHER","status":"COMPLETED","amount":"100.00","currency":"GHS","financialTransactionId":"FIN-AT-1"}
                """);
        assertThat(AirtelTigoProductionRail.parseStatus(mismatch, "REF-AT-1").status()).isEqualTo(PaymentRail.Status.UNKNOWN);
    }

    @Test
    void callbackSignatureVerification() {
        AirtelTigoProductionRail.Credentials creds = new AirtelTigoProductionRail.Credentials(
                "at-client", "at-secret", "at-merch", "test-at-secret"
        );
        AirtelTigoProductionRail rail = new AirtelTigoProductionRail(
                json, health, URI.create("https://api.at.com.gh"), creds, null
        );

        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"AT-100\",\"status\":\"SUCCESSFUL\",\"amount\":5000,\"currency\":\"GHS\"}}";
        String validSig = AirtelTigoProductionRail.computeHmac("test-at-secret", body);

        // Valid signature passes
        PaymentRail.Callback callback = rail.verifyCallback(Map.of("x-airteltigo-signature", validSig), body);
        assertThat(callback.result().status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(callback.result().amount()).isEqualTo(5000L);
        assertThat(callback.reference()).isEqualTo("AT-100");

        // Invalid signature throws
        assertThatThrownBy(() -> rail.verifyCallback(Map.of("x-airteltigo-signature", "invalid_sig"), body))
                .isInstanceOf(PaymentRail.InvalidCallbackException.class)
                .hasMessageContaining("Invalid AirtelTigo callback signature");

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
            byte[] bytes = "{\"access_token\":\"fresh-at-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
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
        AirtelTigoProductionRail.Credentials creds = new AirtelTigoProductionRail.Credentials(
                "at-client", "at-secret", "at-merch", "at-webhook"
        );
        AirtelTigoProductionRail rail = new AirtelTigoProductionRail(json, health, serverUri, creds, client);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "REF-AT-REFRESH", 3000L, "GHS", "momo", "0271234567",
                "airteltigo", "u@test.com", "User", "Charge", null
        );
        PaymentRail.Result result = rail.charge(charge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(tokenRequests.get()).isEqualTo(2);
        assertThat(submitRequests.get()).isEqualTo(2);
    }
}
