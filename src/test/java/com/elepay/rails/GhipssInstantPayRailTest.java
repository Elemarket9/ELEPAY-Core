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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("GhIPSS Instant Pay & GhQR Production Rail Unit Tests")
class GhipssInstantPayRailTest {
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

    private void startServer(int transferStatus, String transferResponse, int queryStatus, String queryResponse) throws IOException {
        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/gip/v1/transfer", exchange -> {
            byte[] bytes = transferResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(transferStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.createContext("/gip/v1/query/", exchange -> {
            byte[] bytes = queryResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(queryStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.start();
        serverUri = URI.create("http://127.0.0.1:" + testServer.getAddress().getPort());
    }

    @Test
    void productionHostVerification() {
        assertThatThrownBy(() -> GhipssInstantPayRail.productionBase("https://untrusted.ghipss-fake.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GhipssInstantPayRail.productionBase("http://gip.ghipss.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(GhipssInstantPayRail.productionBase("https://gip.ghipss.com").getHost())
                .isEqualTo("gip.ghipss.com");
        assertThat(GhipssInstantPayRail.productionBase("https://switch.ghipss.com").getHost())
                .isEqualTo("switch.ghipss.com");
    }

    @Test
    void verifiesFlatFee() {
        assertThat(GhipssInstantPayRail.FLAT_FEE_PESEWAS).isEqualTo(50L);
    }

    @Test
    void unconfiguredCredentialsReturnsUnavailable() {
        GhipssInstantPayRail unconfigured = new GhipssInstantPayRail(
                json, health, null, null, null, null, null
        );

        PaymentRail.Payout payout = new PaymentRail.Payout(
                "GIP-PAYOUT-1", 50000L, "GHS", "bank", "ghipss", "100200300", "Beneficiary", "Interbank"
        );
        PaymentRail.Result result = unconfigured.payout(payout);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(result.message()).contains("credentials not configured");

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "GIP-CHARGE-1", 50000L, "GHS", "bank", "100200300", "ghipss",
                "u@test.com", "User", "Charge", null
        );
        assertThat(unconfigured.charge(charge).status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(unconfigured.query("payout", "GIP-PAYOUT-1", null).status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
    }

    @Test
    void ghQrPayloadGenerationAndVerification() {
        GhipssInstantPayRail.Credentials creds = new GhipssInstantPayRail.Credentials(
                "GHIPSS01", "api-key", "sign-secret", "web-secret"
        );
        GhipssInstantPayRail rail = new GhipssInstantPayRail(
                json, health, URI.create("https://gip.ghipss.com"), creds, null
        );

        // Generate dynamic GhQR payload for 25.50 GHS
        String payload = rail.generateGhQrPayload("ORDER-12345", 2550L, "Acme Store", "Accra", "TERM01");
        assertThat(payload).isNotEmpty();
        assertThat(payload).contains("936"); // Currency GHS
        assertThat(payload).contains("GH");  // Country Code
        assertThat(payload).contains("25.50"); // Amount
        assertThat(payload).contains("Acme Store");

        // Verify valid GhQR payload
        GhipssInstantPayRail.GhQrVerification verification = rail.verifyGhQrPayload(payload);
        assertThat(verification.valid()).isTrue();
        assertThat(verification.amountPesewas()).isEqualTo(2550L);
        assertThat(verification.reference()).isEqualTo("ORDER-12345");
        assertThat(verification.currency()).isEqualTo("GHS");

        // Tampered payload fails verification
        String tampered = payload.substring(0, payload.length() - 4) + "0000";
        GhipssInstantPayRail.GhQrVerification tamperedVerification = rail.verifyGhQrPayload(tampered);
        assertThat(tamperedVerification.valid()).isFalse();
        assertThat(tamperedVerification.errorMessage()).contains("Invalid QR CRC checksum");
    }

    @Test
    void ghQrDynamicChargeGeneration() {
        GhipssInstantPayRail.Credentials creds = new GhipssInstantPayRail.Credentials(
                "GHIPSS01", "api-key", "sign-secret", "web-secret"
        );
        GhipssInstantPayRail rail = new GhipssInstantPayRail(
                json, health, URI.create("https://gip.ghipss.com"), creds, null
        );

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "QR-REF-100", 1500L, "GHS", "ghqr", null, "ghipss",
                "u@test.com", "Merchant Shop", "Payment for goods", null
        );
        PaymentRail.Result result = rail.charge(charge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(result.redirectUrl()).isNotEmpty(); // Contains the EMVCo MPM QR string
        assertThat(result.fee()).isEqualTo(50L);
    }

    @Test
    void interbankPayoutSuccess() throws Exception {
        startServer(
                200, "{\"responseCode\":\"00\",\"sessionID\":\"GIP-SESS-123\",\"gipTransactionId\":\"GTXN-888\"}",
                200, "{\"paymentReference\":\"GIP-PAY-1\",\"responseCode\":\"00\",\"status\":\"SUCCESSFUL\",\"amount\":\"500.00\",\"currency\":\"GHS\",\"gipTransactionId\":\"GTXN-888\"}"
        );

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        GhipssInstantPayRail.Credentials creds = new GhipssInstantPayRail.Credentials(
                "GHIPSS01", "api-key", "sign-secret", "web-secret"
        );
        GhipssInstantPayRail rail = new GhipssInstantPayRail(json, health, serverUri, creds, client);

        PaymentRail.Payout payout = new PaymentRail.Payout(
                "GIP-PAY-1", 50000L, "GHS", "bank", "ghipss", "0123456789", "Kwame Mensah", "Salary Payment"
        );
        PaymentRail.Result result = rail.payout(payout);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(result.railReference()).isEqualTo("GTXN-888");
        assertThat(result.fee()).isEqualTo(50L);
    }

    @Test
    void interbankPayoutPendingAndQuery() throws Exception {
        startServer(
                200, "{\"responseCode\":\"09\",\"sessionID\":\"GIP-SESS-123\"}",
                200, "{\"paymentReference\":\"GIP-PAY-2\",\"responseCode\":\"00\",\"status\":\"SUCCESSFUL\",\"amount\":\"500.00\",\"currency\":\"GHS\",\"gipTransactionId\":\"GTXN-999\"}"
        );

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        GhipssInstantPayRail.Credentials creds = new GhipssInstantPayRail.Credentials(
                "GHIPSS01", "api-key", "sign-secret", "web-secret"
        );
        GhipssInstantPayRail rail = new GhipssInstantPayRail(json, health, serverUri, creds, client);

        PaymentRail.Payout payout = new PaymentRail.Payout(
                "GIP-PAY-2", 50000L, "GHS", "bank", "ghipss", "0123456789", "Kwame Mensah", "Salary Payment"
        );
        PaymentRail.Result submitResult = rail.payout(payout);
        assertThat(submitResult.status()).isEqualTo(PaymentRail.Status.PENDING);

        PaymentRail.Result queryResult = rail.query("payout", "GIP-PAY-2", null);
        assertThat(queryResult.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(queryResult.amount()).isEqualTo(50000L);
        assertThat(queryResult.railReference()).isEqualTo("GTXN-999");
    }

    @Test
    void callbackSignatureVerification() {
        GhipssInstantPayRail.Credentials creds = new GhipssInstantPayRail.Credentials(
                "GHIPSS01", "api-key", "sign-secret", "ghipss-webhook-secret"
        );
        GhipssInstantPayRail rail = new GhipssInstantPayRail(
                json, health, URI.create("https://gip.ghipss.com"), creds, null
        );

        String body = "{\"event\":\"payout.success\",\"data\":{\"reference\":\"GIP-REF-77\",\"status\":\"00\",\"amount\":\"100.00\",\"currency\":\"GHS\"}}";
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String nonce = "gip-nonce-1";
        String validSig = GhipssInstantPayRail.computeHmac("ghipss-webhook-secret", timestamp + "." + nonce + "." + body);

        PaymentRail.Callback callback = rail.verifyCallback(Map.of(
                "x-ghipss-signature", validSig, "x-callback-timestamp", timestamp, "x-callback-nonce", nonce), body);
        assertThat(callback.result().status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(callback.result().amount()).isEqualTo(10000L);
        assertThat(callback.reference()).isEqualTo("GIP-REF-77");

        assertThatThrownBy(() -> rail.verifyCallback(Map.of(
                "x-ghipss-signature", "wrong_signature", "x-callback-timestamp", timestamp, "x-callback-nonce", "gip-nonce-2"), body))
                .isInstanceOf(PaymentRail.InvalidCallbackException.class)
                .hasMessageContaining("signature");
    }
}
