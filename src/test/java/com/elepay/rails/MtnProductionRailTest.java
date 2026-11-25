package com.elepay.rails;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("MTN Mobile Money Production Rail Unit Tests")
class MtnProductionRailTest {
    private final ObjectMapper json = new ObjectMapper();
    private NetworkHealthDetector health;
    private HttpServer testServer;
    private URI serverUri;

    @BeforeEach
    void setUp() throws IOException {
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
        testServer.createContext("/collection/token/", exchange -> {
            byte[] bytes = tokenResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.createContext("/disbursement/token/", exchange -> {
            byte[] bytes = tokenResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.createContext("/collection/v1_0/requesttopay", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] bytes = queryResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(queryStatus, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } else {
                byte[] bytes = submitResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(submitStatus, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            }
        });
        testServer.createContext("/disbursement/v1_0/transfer", exchange -> {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] bytes = queryResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(queryStatus, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } else {
                byte[] bytes = submitResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(submitStatus, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            }
        });
        testServer.start();
        serverUri = URI.create("http://127.0.0.1:" + testServer.getAddress().getPort());
    }

    @Test
    void productionHostCannotBeReplacedBySandboxOrArbitraryDestination() {
        assertThatThrownBy(() -> MtnProductionRail.productionBase("https://sandbox.momodeveloper.mtn.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MtnProductionRail.productionBase("https://example.org"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MtnProductionRail.productionBase("https://proxy.momoapi.mtn.com").getHost())
                .isEqualTo("proxy.momoapi.mtn.com");
        assertThat(MtnProductionRail.productionBase("https://momoapi.momo.africa").getHost())
                .isEqualTo("momoapi.momo.africa");
    }

    @Test
    void providerIdIsStableAndDirectionSpecific() {
        var id = MtnProductionRail.providerId("charge", "EP-123");
        assertThat(id.version()).isEqualTo(4);
        assertThat(id).isEqualTo(MtnProductionRail.providerId("charge", "EP-123"));
        assertThat(id).isNotEqualTo(MtnProductionRail.providerId("payout", "EP-123"));
    }

    @Test
    void calculatesFeeAccurately() {
        assertThat(MtnProductionRail.calculateFee(50_000L)).isEqualTo(500L); // 1%
        assertThat(MtnProductionRail.calculateFee(200_000L)).isEqualTo(1000L); // capped at 1000 pesewas
    }

    @Test
    void unconfiguredCredentialsReturnsUnavailable() {
        MtnProductionRail unconfigured = new MtnProductionRail(
                json, health, null, null, null, null, null, null, null
        );

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "REF-001", 1000L, "GHS", "momo", "0241234567",
                "mtn", "c@test.com", "Customer", "Test", null
        );
        PaymentRail.Result result = unconfigured.charge(charge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(result.message()).contains("credentials not configured");

        PaymentRail.Payout payout = new PaymentRail.Payout(
                "REF-002", 1000L, "GHS", "momo", "mtn", "0241234567", "Customer", "Payout"
        );
        assertThat(unconfigured.payout(payout).status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(unconfigured.query("charge", "REF-001", null).status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
    }

    @Test
    void chargeCollectionAndDisbursementSubmitSuccess() throws Exception {
        startServer(
                "{\"access_token\":\"mtn-test-token\",\"expires_in\":3600}",
                202, "",
                200, "{\"externalId\":\"EPC123\",\"status\":\"SUCCESSFUL\",\"amount\":\"10.25\",\"currency\":\"GHS\",\"financialTransactionId\":\"F-123\"}"
        );

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        MtnProductionRail.Credentials collCreds = new MtnProductionRail.Credentials("col-user", "col-key", "col-sub");
        MtnProductionRail.Credentials disbCreds = new MtnProductionRail.Credentials("disb-user", "disb-key", "disb-sub");
        MtnProductionRail rail = new MtnProductionRail(json, health, serverUri, collCreds, disbCreds, client);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "EPC123", 1025L, "GHS", "momo", "0241234567",
                "mtn", "c@test.com", "Customer", "Test", null
        );
        PaymentRail.Result chargeResult = rail.charge(charge);
        assertThat(chargeResult.status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(chargeResult.railReference()).isNotEmpty();
        assertThat(chargeResult.fee()).isEqualTo(10L); // 1% of 1025 pesewas = 10 pesewas

        PaymentRail.Payout payout = new PaymentRail.Payout(
                "EPP123", 5000L, "GHS", "momo", "mtn", "0241234567", "Customer", "Payout"
        );
        PaymentRail.Result payoutResult = rail.payout(payout);
        assertThat(payoutResult.status()).isEqualTo(PaymentRail.Status.PENDING);
    }

    @Test
    void queryParsesSuccessfulPendingAndFailedStatuses() throws Exception {
        startServer(
                "{\"access_token\":\"mtn-test-token\",\"expires_in\":3600}",
                202, "",
                200, "{\"externalId\":\"EPC123\",\"status\":\"SUCCESSFUL\",\"amount\":\"10.25\",\"currency\":\"GHS\",\"financialTransactionId\":\"F-123\"}"
        );

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        MtnProductionRail.Credentials collCreds = new MtnProductionRail.Credentials("col-user", "col-key", "col-sub");
        MtnProductionRail.Credentials disbCreds = new MtnProductionRail.Credentials("disb-user", "disb-key", "disb-sub");
        MtnProductionRail rail = new MtnProductionRail(json, health, serverUri, collCreds, disbCreds, client);

        PaymentRail.Result success = rail.query("charge", "EPC123", null);
        assertThat(success.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(success.amount()).isEqualTo(1025L);
        assertThat(success.railReference()).isEqualTo("F-123");
        assertThat(success.fee()).isEqualTo(10L);
    }

    @Test
    void successRequiresReferenceAmountCurrencyAndFinancialId() {
        var confirmed = json.readTree("""
                {"externalId":"EPC123","status":"SUCCESSFUL","amount":"10.25","currency":"GHS","financialTransactionId":"F-123"}
                """);
        var result = MtnProductionRail.parseStatus(confirmed, "EPC123");
        assertThat(result.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(result.amount()).isEqualTo(1025L);
        assertThat(result.railReference()).isEqualTo("F-123");
        assertThat(MtnProductionRail.parseStatus(confirmed, "other").status()).isEqualTo(PaymentRail.Status.UNKNOWN);

        var missing = json.readTree("""
                {"externalId":"EPC123","status":"SUCCESSFUL","amount":"10.25","currency":"GHS"}
                """);
        assertThat(MtnProductionRail.parseStatus(missing, "EPC123").status()).isEqualTo(PaymentRail.Status.UNKNOWN);

        var failed = json.readTree("""
                {"externalId":"EPC123","status":"FAILED","reason":"Subscriber account inactive"}
                """);
        assertThat(MtnProductionRail.parseStatus(failed, "EPC123").status()).isEqualTo(PaymentRail.Status.FAILED);

        var pending = json.readTree("""
                {"externalId":"EPC123","status":"PENDING"}
                """);
        assertThat(MtnProductionRail.parseStatus(pending, "EPC123").status()).isEqualTo(PaymentRail.Status.PENDING);
    }

    @Test
    void tokenRefreshRetriesOn401() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger(0);
        AtomicInteger submitRequests = new AtomicInteger(0);

        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/collection/token/", exchange -> {
            tokenRequests.incrementAndGet();
            byte[] bytes = "{\"access_token\":\"fresh-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.createContext("/collection/v1_0/requesttopay", exchange -> {
            int count = submitRequests.incrementAndGet();
            if (count == 1) {
                // First attempt receives 401 Unauthorized
                exchange.sendResponseHeaders(401, 0);
                exchange.close();
            } else {
                // Second attempt succeeds
                exchange.sendResponseHeaders(202, 0);
                exchange.close();
            }
        });
        testServer.start();
        serverUri = URI.create("http://127.0.0.1:" + testServer.getAddress().getPort());

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        MtnProductionRail.Credentials collCreds = new MtnProductionRail.Credentials("col-user", "col-key", "col-sub");
        MtnProductionRail.Credentials disbCreds = new MtnProductionRail.Credentials("disb-user", "disb-key", "disb-sub");
        MtnProductionRail rail = new MtnProductionRail(json, health, serverUri, collCreds, disbCreds, client);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "EPC-REFRESH-1", 2000L, "GHS", "momo", "0241234567",
                "mtn", "c@test.com", "Customer", "Test", null
        );
        PaymentRail.Result result = rail.charge(charge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(tokenRequests.get()).isEqualTo(2); // Initial + refreshed after 401
        assertThat(submitRequests.get()).isEqualTo(2); // Attempt 1 (401) + retry (202)
    }

    @Test
    void sanitizeNoteReplacesAmpersandStripsSpecialCharsAndBoundsLength() {
        assertThat(MtnProductionRail.sanitizeNote("Jollof & Chicken", "EP-1"))
                .isEqualTo("Jollof and Chicken");
        assertThat(MtnProductionRail.sanitizeNote("  Waakye & \"Beef\" <special>'s;  ", "EP-2"))
                .isEqualTo("Waakye and Beef special s");
        assertThat(MtnProductionRail.sanitizeNote("   ", "EP-3"))
                .isEqualTo("ELEPAY EP-3");
        assertThat(MtnProductionRail.sanitizeNote("A".repeat(150), "EP-4"))
                .hasSize(100);
    }

    @Test
    void chargeSendsSanitizedPayerMessageAndPayeeNoteWithUserAgentHeader() throws Exception {
        var capturedBody = new java.util.concurrent.atomic.AtomicReference<String>();
        var capturedTokenUa = new java.util.concurrent.atomic.AtomicReference<String>();
        var capturedSubmitUa = new java.util.concurrent.atomic.AtomicReference<String>();

        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/collection/token/", exchange -> {
            capturedTokenUa.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes = "{\"access_token\":\"mtn-test-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.createContext("/collection/v1_0/requesttopay", exchange -> {
            capturedSubmitUa.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, 0);
            exchange.close();
        });
        testServer.start();
        serverUri = URI.create("http://127.0.0.1:" + testServer.getAddress().getPort());

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        MtnProductionRail.Credentials collCreds = new MtnProductionRail.Credentials("col-user", "col-key", "col-sub");
        MtnProductionRail.Credentials disbCreds = new MtnProductionRail.Credentials("disb-user", "disb-key", "disb-sub");
        MtnProductionRail rail = new MtnProductionRail(json, health, serverUri, collCreds, disbCreds, client);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "EPC-WAF-1", 1500L, "GHS", "momo", "0241234567",
                "mtn", "c@test.com", "Customer", "Jollof & Chicken <Order>", null
        );
        PaymentRail.Result result = rail.charge(charge);

        assertThat(result.status()).isEqualTo(PaymentRail.Status.PENDING);
        assertThat(capturedTokenUa.get()).isEqualTo("ELEPAY-Core/1.0");
        assertThat(capturedSubmitUa.get()).isEqualTo("ELEPAY-Core/1.0");
        var sentJson = json.readTree(capturedBody.get());
        assertThat(sentJson.path("payerMessage").asString("")).isEqualTo("Jollof and Chicken Order");
        assertThat(sentJson.path("payeeNote").asString("")).isEqualTo("Jollof and Chicken Order");
    }

    @Test
    void htmlWafResponseOnSubmitQueryAndTokenFailsCleanlyWithoutJsonCrash() throws Exception {
        String wafHtml = "<html><head><title>Request Rejected</title></head><body>The requested URL was rejected. Please consult with your administrator.</body></html>";

        startServer(
                "{\"access_token\":\"mtn-test-token\",\"expires_in\":3600}",
                200, wafHtml,
                200, wafHtml
        );

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        MtnProductionRail.Credentials collCreds = new MtnProductionRail.Credentials("col-user", "col-key", "col-sub");
        MtnProductionRail.Credentials disbCreds = new MtnProductionRail.Credentials("disb-user", "disb-key", "disb-sub");
        MtnProductionRail rail = new MtnProductionRail(json, health, serverUri, collCreds, disbCreds, client);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "EPC-WAF-2", 1500L, "GHS", "momo", "0241234567",
                "mtn", "c@test.com", "Customer", "Jollof & Chicken", null
        );
        PaymentRail.Result submitResult = rail.charge(charge);
        assertThat(submitResult.status()).isEqualTo(PaymentRail.Status.UNKNOWN);
        assertThat(submitResult.message()).containsIgnoringCase("WAF");

        PaymentRail.Result queryResult = rail.query("charge", "EPC-WAF-2", null);
        assertThat(queryResult.status()).isEqualTo(PaymentRail.Status.UNKNOWN);
        assertThat(queryResult.message()).containsIgnoringCase("WAF");

        // Also verify HTTP 200 HTML WAF page on /token/ endpoint
        testServer.stop(0);
        startServer(wafHtml, 202, "", 200, "{}");
        MtnProductionRail freshRail = new MtnProductionRail(json, health, serverUri, collCreds, disbCreds, client);
        PaymentRail.Result tokenWafResult = freshRail.charge(charge);
        // Token blocked before any payment request was sent: unambiguous, so UNAVAILABLE (fails cleanly).
        assertThat(tokenWafResult.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(tokenWafResult.message()).containsIgnoringCase("WAF");
    }

    // ---- regressions found by live MTN sandbox testing ----

    @Test
    void sanitizeNoteRemovesEveryCharacterTheMtnWafBlocks() {
        // Blocked by the sandbox WAF: ! # $ % & ' ( ) * ; ? [ ] ^ ` { | } ~
        String note = MtnProductionRail.sanitizeNote("Order #45 (2 items)! 50% off? [x] {y} |z| ~a^ `b` $c *d;", "EP");
        assertThat(note).doesNotContainPattern("[!#$%&'()*;?\\[\\]^`{|}~<>\"\\\\]");
        assertThat(MtnProductionRail.sanitizeNote("Order #45 - Accra, Ghana", "EP")).isEqualTo("Order no 45 - Accra, Ghana");
        assertThat(MtnProductionRail.sanitizeNote("Kɔfi Ŋmɛ paid GHS 10.00 / ref: EPC1", "EP"))
                .isEqualTo("Kɔfi Ŋmɛ paid GHS 10.00 / ref: EPC1");
    }

    @Test
    void createdStatusIsPendingAndFailureReasonIsKept() {
        var created = json.readTree("{\"externalId\":\"EPC1\",\"status\":\"CREATED\"}");
        assertThat(MtnProductionRail.parseStatus(created, "EPC1").status()).isEqualTo(PaymentRail.Status.PENDING);
        var declined = json.readTree("{\"externalId\":\"EPC1\",\"status\":\"FAILED\",\"reason\":\"APPROVAL_REJECTED\"}");
        assertThat(MtnProductionRail.parseStatus(declined, "EPC1").message()).contains("declined", "APPROVAL_REJECTED");
        var expired = json.readTree("{\"externalId\":\"EPC1\",\"status\":\"FAILED\",\"reason\":{\"code\":\"EXPIRED\"}}");
        assertThat(MtnProductionRail.parseStatus(expired, "EPC1").message()).contains("EXPIRED");
        var odd = json.readTree("{\"externalId\":\"EPC1\",\"status\":\"FAILED\",\"reason\":\"<script>\"}");
        assertThat(MtnProductionRail.parseStatus(odd, "EPC1").message()).isEqualTo("MTN payment failed");
    }

    @Test
    void refundTargetsOriginalRequestReferenceAndIsSupportedWithoutNetwork() {
        var refund = new PaymentRail.Refund("EPR1", "EPC1", "1440473424", 100, "GHS", "0241234567", null);
        assertThat(MtnProductionRail.referenceIdToRefund(refund))
                .isEqualTo(MtnProductionRail.providerId("charge", "EPC1").toString());
        var rail = new MtnProductionRail(json, health, URI.create("http://127.0.0.1:1"),
                new MtnProductionRail.Credentials("u", "k", "s"), new MtnProductionRail.Credentials("u", "k", "s"), null);
        assertThat(rail.supports("refund", "momo", null)).isTrue();
        assertThat(rail.supports("refund", "momo", "telecel")).isFalse();
    }

    @Test
    void rejectedCredentialsAndInvalidPhoneFailWithoutBecomingUnknown() throws Exception {
        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/collection/token/", exchange -> {
            byte[] bytes = "{\"error\":\"login_failed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        testServer.start();
        serverUri = URI.create("http://127.0.0.1:" + testServer.getAddress().getPort());
        var creds = new MtnProductionRail.Credentials("u", "wrong", "s");
        var rail = new MtnProductionRail(json, health, serverUri, creds, creds, null);
        var charge = new PaymentRail.Charge("EPC-BAD", 100, "GHS", "momo", "0241234567", "mtn", null, null, "x", null);
        var result = rail.charge(charge);
        assertThat(result.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(result.message()).contains("login_failed");
        var badPhone = new PaymentRail.Charge("EPC-PH", 100, "GHS", "momo", "12345", "mtn", null, null, "x", null);
        assertThat(rail.charge(badPhone).status()).isEqualTo(PaymentRail.Status.FAILED);
    }

    // ---- circuit breaker must measure MTN reachability, not MTN saying yes ----

    @Test
    void mtnAnsweredCountsOnlyRealOutages() {
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(202, "", "application/json"))).isTrue();
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(400, "", null))).isTrue();
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(404, "{}", "application/json"))).isTrue();
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(409,
                "{\"code\":\"RESOURCE_ALREADY_EXIST\"}", "application/json"))).isTrue();
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(500,
                "{\"code\":\"NOT_ALLOWED\",\"message\":\"Authorization failed.\"}", "application/json"))).isTrue();
        // real outages
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(503, "{\"code\":\"X\"}", "application/json"))).isFalse();
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(502, "", null))).isFalse();
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(429, "", null))).isFalse();
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(500, "Internal error", "text/plain"))).isFalse();
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(500, "", null))).isFalse();
        assertThat(MtnProductionRail.mtnAnswered(new MtnProductionRail.Response(200,
                "<html>Request Rejected</html>", "text/html"))).isFalse();
    }

    @Test
    void refusedOpsLookupsAndResendsDoNotOpenTheCircuitButOutagesDo() throws Exception {
        AtomicInteger outage = new AtomicInteger(0);
        testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/disbursement/token/", ex -> reply(ex, 200,
                "{\"access_token\":\"t\",\"token_type\":\"access_token\",\"expires_in\":3600}"));
        testServer.createContext("/collection/token/", ex -> reply(ex, 200,
                "{\"access_token\":\"t\",\"token_type\":\"access_token\",\"expires_in\":3600}"));
        testServer.createContext("/disbursement/v1_0/account/balance", ex ->
                reply(ex, outage.get() > 0 ? 503 : 500, outage.get() > 0 ? "" : "{\"code\":\"NOT_ALLOWED\",\"message\":\"Authorization failed.\"}"));
        testServer.createContext("/collection/v1_0/requesttopay", ex ->
                reply(ex, 409, "{\"code\":\"RESOURCE_ALREADY_EXIST\",\"message\":\"Duplicated reference id.\"}"));
        testServer.start();
        serverUri = URI.create("http://127.0.0.1:" + testServer.getAddress().getPort());
        var creds = new MtnProductionRail.Credentials("u", "k", "s");
        NetworkHealthDetector real = new NetworkHealthDetector(null);
        var rail = new MtnProductionRail(json, real, serverUri, creds, creds, null);

        for (int i = 0; i < 5; i++) {
            assertThat(rail.getAccountBalance("payout").available()).isFalse();        // refused, shown to ops
            rail.charge(new PaymentRail.Charge("EPC-DUP", 100, "GHS", "momo", "0241234567", "mtn", null, null, "x", null));
        }
        assertThat(real.isRailAvailable("mtn")).as("MTN answered every time; payments must stay available").isTrue();

        outage.set(1);
        for (int i = 0; i < 8; i++) rail.getAccountBalance("payout");                   // genuine 503 outage: 8/18 > 40%
        assertThat(real.isRailAvailable("mtn")).as("a real outage must still open the circuit").isFalse();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }
}
