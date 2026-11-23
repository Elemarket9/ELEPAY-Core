package com.elepay.emtech;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.merchanttools.pos.PosTerminalService;
import com.elepay.operations.EmtechCapture;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class EmtechReportingIT extends PostgresTestBase {
    private static final WireMockServer EMTECH = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        EMTECH.start();
    }

    @DynamicPropertySource
    static void emtech(DynamicPropertyRegistry registry) {
        registry.add("elepay.emtech.enabled", () -> "true");
        registry.add("elepay.emtech.base-url", () -> "http://127.0.0.1:" + EMTECH.port());
        registry.add("elepay.emtech.client-id", () -> "elepay-test-client");
        registry.add("elepay.emtech.client-secret", () -> "elepay-test-secret");
        registry.add("elepay.emtech.max-retries", () -> "1");
        registry.add("elepay.emtech.retry-base-ms", () -> "0");
        registry.add("elepay.emtech.max-attempts", () -> "1");
        registry.add("elepay.emtech.poll-ms", () -> "3600000");
        registry.add("elepay.emtech.emoney-account-type", () -> "BANK");
        registry.add("elepay.emtech.coarse-ip-cities", () -> "192.0.2.10=Kumasi|GH-AH|GH");
    }

    @Autowired EmtechOutbox outbox;
    @Autowired EmtechDispatcher dispatcher;
    @Autowired EmtechCapture capture;
    @Autowired PosTerminalService terminals;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    private final EmtechSchemaValidator schemas = new EmtechSchemaValidator(new ObjectMapper());
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void stubHappyPath() {
        EMTECH.resetAll();
        EMTECH.stubFor(post(urlPathEqualTo("/v1/auth/token"))
                .willReturn(okJson("{\"accessToken\":\"sandbox-token\",\"expiryMS\":3600000}")));
        acceptAll();
    }

    @Test
    void derivesCityFromTerminalProfileIpMnoOrBranchAndDoesNotStoreCoordinates() {
        UUID merchant = merchant("Osu Market", "Accra", "GH-AA");
        UUID branch = branch(merchant, "Osu", "Accra", "GH-AA");
        UUID customer = customer("233240000001", "Ama", 2, "Kumasi", "GH-AH", "PROFILE", null, null);
        UUID terminal = terminals.registerTerminal(merchant, branch, "SN-" + UUID.randomUUID(), "T1",
                new PosTerminalService.RegulatoryProfile("Accra", "GH-AA", "GH", "Oxford Street", "Counter",
                        "5.603717", "-0.186964", "NETWORK", "ELEPAY", 2025, "Osu counter", "Shop")).id();
        UUID checkout = checkout(merchant, branch, customer, "momo", "POS", 15000, 150);
        linkPos(merchant, branch, terminal, checkout);
        capture.paidCheckout(checkout);
        JsonNode fact = factForSource("checkout", referenceOf(checkout));
        assertThat(fact.get("originCity").asString()).isEqualTo("Accra");
        assertThat(fact.get("originLocalitySource").asString()).isEqualTo("TERMINAL");
        assertThat(fact.has("latitude")).isFalse();
        assertThat(fact.has("originLatitude")).isFalse();

        UUID mnoCustomer = customer("233240000002", "Kofi", 1, null, null, null, "Tema", "GH-AA");
        UUID ussd = checkout(merchant, branch, mnoCustomer, "momo", "MOBILE_USSD", 1000, 0);
        capture.rememberCheckout(ussd, "192.0.2.10");
        capture.paidCheckout(ussd);
        JsonNode ussdFact = factForSource("checkout", referenceOf(ussd));
        assertThat(ussdFact.get("originCity").asString()).isEqualTo("Tema");
        assertThat(ussdFact.get("originLocalitySource").asString()).isEqualTo("MNO");

        UUID blank = customer("233240000003", "Yaw", 2, null, null, null, null, null);
        UUID web = checkout(merchant, branch, blank, "card", "WEB", 800, 0);
        capture.rememberCheckout(web, "192.0.2.10");
        capture.paidCheckout(web);
        JsonNode webFact = factForSource("checkout", referenceOf(web));
        assertThat(webFact.get("originCity").asString()).isEqualTo("Kumasi");
        assertThat(webFact.get("originLocalitySource").asString()).isEqualTo("COARSE_IP");

        UUID nowhere = customer("233240000004", "Esi", 1, null, null, null, null, null);
        UUID fallback = checkout(merchant, branch, nowhere, "wallet", "WEB", 500, 0);
        capture.rememberCheckout(fallback, null);
        capture.paidCheckout(fallback);
        JsonNode branchFact = factForSource("checkout", referenceOf(fallback));
        assertThat(branchFact.get("originCity").asString()).isEqualTo("Accra");
        assertThat(branchFact.get("originLocalitySource").asString()).isEqualTo("BRANCH");
        dispatcher.dispatchAvailable(50);
    }

    @Test
    void accraTemaKumasiDayIsSchemaValidAndComplete() {
        String run = "day" + UUID.randomUUID().toString().substring(0, 8);
        for (AccraDay.Item item : AccraDay.day(run, "BANK")) {
            outbox.enqueue(item.resource(), item.idempotencyKey(), item.sourceType(), item.sourceId(), item.fact());
        }
        long first = db.sql("SELECT count(*) FROM emtech_submission WHERE idempotency_key LIKE :k")
                .param("k", "%" + run + "%").query(Long.class).single();
        outbox.enqueue("DEVICE", "device:pos-accra-" + run, "device", "pos-accra-" + run,
                AccraDay.day(run, "BANK").get(0).fact());
        long second = db.sql("SELECT count(*) FROM emtech_submission WHERE idempotency_key = :k")
                .param("k", "device:pos-accra-" + run).query(Long.class).single();
        assertThat(second).isEqualTo(1);
        dispatcher.dispatchAvailable(200);
        long submitted = db.sql("""
                SELECT count(*) FROM emtech_submission
                WHERE idempotency_key LIKE :k AND status = 'SUBMITTED'""")
                .param("k", "%" + run + "%").query(Long.class).single();
        assertThat(submitted).isEqualTo(first);

        List<JsonNode> bodies = EMTECH.getAllServeEvents().stream()
                .filter(event -> !event.getRequest().getUrl().endsWith("/v1/auth/token"))
                .map(event -> event.getRequest().getBodyAsString())
                .filter(body -> body.contains(run))
                .map(json::readTree)
                .toList();
        assertThat(bodies).hasSize((int) first);
        Set<String> complaintTypes = new HashSet<>();
        Set<String> complaintStatuses = new HashSet<>();
        Set<String> deviceStatuses = new HashSet<>();
        Set<String> channels = new HashSet<>();
        Set<String> accountTypes = new HashSet<>();
        boolean sawEmoney = false;
        boolean sawTerminalOrigin = false;
        for (JsonNode body : bodies) {
            if (body.has("deviceType")) schemas.validate("deviceV2", body);
            else if (body.has("transactionId")) {
                schemas.validate("cryptoPSPTransactionV2", body);
                assertThat(body.has("transactionLatitude")).isFalse();
                assertThat(body.has("transactionLongitude")).isFalse();
                assertThat(body.has("transactionClientIp")).isFalse();
                assertThat(body.get("originAmount").isNumber()).isTrue();
                assertThat(body.get("originAmount").decimalValue().scale()).isLessThanOrEqualTo(2);
                channels.add(body.get("transactionChannel").asString());
                accountTypes.add(body.get("originAccountType").asString());
                accountTypes.add(body.get("destinationAccountType").asString());
                if ("emoney_wallet".equals(body.path("meta").path("originAccountInstrument").asString())) {
                    sawEmoney = true;
                    assertThat(body.get("originAccountProvider").asString()).isEqualTo("ELEPAY");
                }
                if ("TERMINAL".equals(body.path("meta").path("originLocalitySource").asString())) {
                    sawTerminalOrigin = true;
                    assertThat(body.get("originAccountCity").asString()).isIn("Accra", "Tema", "Kumasi");
                }
            } else if (body.has("deviceHealth")) {
                schemas.validate("deviceEvent", body);
                deviceStatuses.add(body.get("deviceStatus").asString());
            } else {
                schemas.validate("consumerComplaints", body);
                assertThat(body.get("userId").isTextual()).isTrue();
                complaintTypes.add(body.get("complaintType").asString());
                complaintStatuses.add(body.get("complaintStatus").asString());
            }
        }
        assertThat(channels).contains("POS", "WEB", "MOBILE_USSD", "MOBILE_APP");
        assertThat(accountTypes).contains("MOBILE_MONEY", "CREDIT", "CASH", "AIRTIME", "BANK");
        assertThat(sawEmoney).isTrue();
        assertThat(sawTerminalOrigin).isTrue();
        assertThat(deviceStatuses).contains("ONLINE", "NETWORK_FAILURE", "MAINTENANCE");
        assertThat(complaintTypes).contains("FRAUD", "DISPUTE", "SERVICE_ERROR", "SYSTEM_DOWNTIME", "MISCONDUCT OTHER");
        assertThat(complaintStatuses).contains("OPENED", "PENDING_RESOLUTION", "REOPENED", "CLOSED", "RESOLVED");
        JsonNode momo = bodies.stream().filter(b -> "momo-accra-".concat(run).equals(text(b, "transactionId"))).findFirst().orElseThrow();
        assertThat(momo.get("originAmount").decimalValue()).isEqualByComparingTo("150.50");
        assertThat(momo.get("transactionFeeAmount").decimalValue()).isEqualByComparingTo("1.50");
        assertThat(momo.get("destinationAmount").decimalValue()).isEqualByComparingTo("149.00");
        EMTECH.verify(postRequestedFor(urlPathEqualTo("/compliance/payment-service/v2/transactions"))
                .withHeader(EmtechClient.AUTH_HEADER, matching("Bearer .+"))
                .withoutHeader("Authorization"));
    }

    @Test
    void badRequestIsDeadLetteredWithoutRetry() {
        String source = "bad-" + UUID.randomUUID().toString().substring(0, 8);
        enqueueDevice(source);
        EMTECH.resetAll();
        EMTECH.stubFor(post(urlPathEqualTo("/v1/auth/token"))
                .willReturn(okJson("{\"accessToken\":\"sandbox-token\",\"expiryMS\":3600000}")));
        EMTECH.stubFor(post(urlPathEqualTo("/compliance/payment-service/v2/devices"))
                .withRequestBody(containing(source))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"039\",\"message\":\"Your data is invalid. Please check and try again.\","
                                + "\"errorDetails\":[{\"field\":\"deviceId\",\"message\":\"must not be null\"}]}")));
        dispatcher.dispatchAvailable(20);
        assertThat(statusOf(source)).isEqualTo("DEAD");
        assertThat(codeOf(source)).isEqualTo("039");
        EMTECH.verify(1, postRequestedFor(urlPathEqualTo("/compliance/payment-service/v2/devices"))
                .withRequestBody(containing(source)));
    }

    @Test
    void expiredTokenIsRefreshedOnce() {
        String source = "refresh-" + UUID.randomUUID().toString().substring(0, 8);
        enqueueDevice(source);
        EMTECH.resetAll();
        EMTECH.stubFor(post(urlPathEqualTo("/v1/auth/token")).willReturn(okJson("{\"accessToken\":\"fresh-token\",\"expiryMS\":3600000}")));
        EMTECH.stubFor(post(urlPathEqualTo("/compliance/payment-service/v2/devices")).inScenario("auth")
                .withRequestBody(containing(source))
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"002\",\"message\":\"Invalid or expired token received, please create new token and try again.\",\"errorDetails\":[]}"))
                .willSetStateTo("ok"));
        EMTECH.stubFor(post(urlPathEqualTo("/compliance/payment-service/v2/devices")).inScenario("auth")
                .withRequestBody(containing(source))
                .whenScenarioStateIs("ok")
                .withHeader(EmtechClient.AUTH_HEADER, equalTo("Bearer fresh-token"))
                .willReturn(aResponse().withStatus(200)));
        dispatcher.dispatchAvailable(20);
        assertThat(statusOf(source)).isEqualTo("SUBMITTED");
    }

    @Test
    void serverErrorIsRetriedThenDeadLettered() {
        String source = "retry-" + UUID.randomUUID().toString().substring(0, 8);
        enqueueDevice(source);
        EMTECH.resetAll();
        EMTECH.stubFor(post(urlPathEqualTo("/v1/auth/token"))
                .willReturn(okJson("{\"accessToken\":\"sandbox-token\",\"expiryMS\":3600000}")));
        EMTECH.stubFor(post(urlPathEqualTo("/compliance/payment-service/v2/devices")).inScenario("flake")
                .withRequestBody(containing(source))
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("still"));
        EMTECH.stubFor(post(urlPathEqualTo("/compliance/payment-service/v2/devices")).inScenario("flake")
                .withRequestBody(containing(source))
                .whenScenarioStateIs("still")
                .willReturn(aResponse().withStatus(503)));
        dispatcher.dispatchAvailable(20);
        assertThat(statusOf(source)).isEqualTo("DEAD");
        EMTECH.verify(2, postRequestedFor(urlPathEqualTo("/compliance/payment-service/v2/devices"))
                .withRequestBody(containing(source)));
    }

    @Test
    void invalidPayloadIsRejectedLocally() {
        String source = "incomplete-" + UUID.randomUUID().toString().substring(0, 8);
        outbox.enqueue("DEVICE", "device:" + source, "device", source, new RegulatoryFacts.Device(
                source, "POS", null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        dispatcher.dispatchAvailable(10);
        assertThat(statusOf(source)).isEqualTo("DEAD");
        assertThat(codeOf(source)).isEqualTo("validation");
        EMTECH.verify(0, postRequestedFor(urlPathEqualTo("/compliance/payment-service/v2/devices"))
                .withRequestBody(containing(source)));
    }

    @Test
    void onlyOpsCanReplayADeadLetter() throws Exception {
        String source = "replay-" + UUID.randomUUID().toString().substring(0, 8);
        enqueueDevice(source);
        EMTECH.resetAll();
        EMTECH.stubFor(post(urlPathEqualTo("/v1/auth/token"))
                .willReturn(okJson("{\"accessToken\":\"sandbox-token\",\"expiryMS\":3600000}")));
        acceptAll();
        EMTECH.stubFor(post(urlPathEqualTo("/compliance/payment-service/v2/devices"))
                .withRequestBody(containing(source))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"039\",\"message\":\"Your data is invalid. Please check and try again.\",\"errorDetails\":[]}")));
        dispatcher.dispatchAvailable(10);
        UUID id = db.sql("SELECT id FROM emtech_submission WHERE source_id = :id").param("id", source).query(UUID.class).single();
        HttpResponse<String> denied = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/v1/ops/emtech/submissions/" + id + "/replay"))
                .header("Authorization", "Bearer " + token("customer-1", List.of()))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
        acceptAll();
        HttpResponse<String> allowed = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/v1/ops/emtech/submissions/" + id + "/replay?sendNow=true"))
                .header("Authorization", "Bearer " + token("ops-1", List.of("ops")))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(statusOf(source)).isEqualTo("SUBMITTED");
    }

    private void acceptAll() {
        for (String path : List.of(
                "/compliance/payment-service/v2/devices",
                "/compliance/payment-service/v2/transactions",
                "/compliance/payment-service/v1/device-events",
                "/compliance/payment-service/v1/consumer-complaints")) {
            EMTECH.stubFor(post(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200)));
        }
    }

    private void enqueueDevice(String id) {
        outbox.enqueue("DEVICE", "device:" + id, "device", id, new RegulatoryFacts.Device(
                id, "POS", id, "Accra", "GH-AA", "GH", "Oxford Street", "Counter",
                "5.603717", "-0.186964", "T1", "ELEPAY", "Test", "2026-01-15T09:00:00Z", "NETWORK", 2025));
    }

    private String statusOf(String sourceId) {
        return db.sql("SELECT status FROM emtech_submission WHERE source_id = :id")
                .param("id", sourceId).query(String.class).single();
    }

    private String codeOf(String sourceId) {
        return db.sql("SELECT last_code FROM emtech_submission WHERE source_id = :id")
                .param("id", sourceId).query(String.class).single();
    }

    private JsonNode factForSource(String type, String sourceId) {
        String raw = db.sql("SELECT fact::text FROM emtech_submission WHERE source_type = :t AND source_id = :id")
                .param("t", type).param("id", sourceId).query(String.class).single();
        return json.readTree(raw);
    }

    private String referenceOf(UUID checkoutId) {
        return db.sql("SELECT reference FROM checkout WHERE id = :id").param("id", checkoutId).query(String.class).single();
    }

    private UUID merchant(String name, String city, String region) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO merchant (id, legal_name, trading_name, kind, status, merchant_code, contact_phone, created_by, city, region, country)
                VALUES (:id, :name, :name, 'merchant', 'active', :code, '0240000000', 'test', :city, :region, 'GH')""")
                .param("id", id).param("name", name)
                .param("code", String.format("%06d", 100000 + (int) (Math.random() * 899999)))
                .param("city", city).param("region", region).update();
        return id;
    }

    private UUID branch(UUID merchant, String name, String city, String region) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO merchant_branch (id, merchant_id, name, city, region, country)
                VALUES (:id, :m, :name, :city, :region, 'GH')""")
                .param("id", id).param("m", merchant).param("name", name).param("city", city).param("region", region).update();
        return id;
    }

    private UUID customer(String phone, String name, int tier, String city, String region, String source,
                          String mnoCity, String mnoRegion) {
        UUID id = UUID.randomUUID();
        long suffix = Math.floorMod(id.getLeastSignificantBits(), 1_000_000_000L);
        db.sql("""
                INSERT INTO customer (id, subject, phone, display_name, kyc_tier, city, region, country, locality_source, mno_city, mno_region)
                VALUES (:id, :subject, :phone, :name, :tier, :city, :region, 'GH', :source, :mnoCity, :mnoRegion)""")
                .param("id", id).param("subject", "sub-" + id).param("phone", "233" + String.format("%09d", suffix)).param("name", name).param("tier", tier)
                .param("city", city).param("region", region).param("source", source)
                .param("mnoCity", mnoCity).param("mnoRegion", mnoRegion).update();
        return id;
    }

    private UUID checkout(UUID merchant, UUID branch, UUID customer, String method, String channel, long subtotal, long fee) {
        UUID id = UUID.randomUUID();
        String reference = "EP" + id.toString().replace("-", "").substring(0, 12);
        db.sql("""
                INSERT INTO checkout (id, reference, collector_id, branch_id, customer_id, status, subtotal,
                    payment_method, customer_fee, total_paid, paid_at, created_by, channel, expires_at)
                VALUES (:id, :ref, :m, :b, :c, 'paid', :sub, :method, :fee, :total, now(), 'test', :channel, now() + interval '1 day')""")
                .param("id", id).param("ref", reference).param("m", merchant).param("b", branch).param("c", customer)
                .param("sub", subtotal).param("method", method).param("fee", fee).param("total", subtotal + fee)
                .param("channel", channel).update();
        return id;
    }

    private void linkPos(UUID merchant, UUID branch, UUID terminal, UUID checkout) {
        UUID order = UUID.randomUUID();
        db.sql("""
                INSERT INTO pos_order (id, order_number, merchant_id, branch_id, terminal_id, subtotal_pesewas, total_pesewas)
                VALUES (:id, :num, :m, :b, :t, 15000, 15000)""")
                .param("id", order).param("num", "ORD-" + order).param("m", merchant).param("b", branch).param("t", terminal).update();
        db.sql("""
                INSERT INTO pos_checkout_link (id, order_id, checkout_ref, amount, status)
                VALUES (:id, :order, :ref, 15000, 'open')""")
                .param("id", UUID.randomUUID()).param("order", order).param("ref", referenceOf(checkout)).update();
    }

    private String token(String subject, List<String> roles) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("subject", subject, "roles", roles));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dev/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body()).get("accessToken").asString();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString("");
    }
}
