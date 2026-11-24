package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LIVE sandbox smoke test against the REAL MTN MoMo developer sandbox
 * ({@code https://sandbox.momodeveloper.mtn.com}). Disabled by default: it runs ONLY when
 * {@code MTN_SANDBOX_SUBSCRIPTION_KEY} is present in the environment, so CI and local runs without
 * credentials skip it. No secret is ever hardcoded — every credential comes from an env var.
 *
 * <p>Flow: provision a sandbox API user ({@code POST /v1_0/apiuser} + {@code /apikey}), obtain an
 * OAuth token, then run a {@code requesttopay} and poll its status. It exercises the production
 * adapter's real reference logic ({@link MtnProductionRail#providerId}).
 *
 * <h2>Environment variables</h2>
 * <ul>
 *   <li>{@code MTN_SANDBOX_SUBSCRIPTION_KEY} (required; gates the test) — collection Ocp-Apim subscription key</li>
 *   <li>{@code MTN_SANDBOX_BASE} (optional, default {@code https://sandbox.momodeveloper.mtn.com})</li>
 *   <li>{@code MTN_SANDBOX_CALLBACK_HOST} (optional, default {@code elepay.example})</li>
 *   <li>{@code MTN_SANDBOX_MSISDN} (optional, default {@code 46733123450})</li>
 *   <li>{@code MTN_SANDBOX_CURRENCY} (optional, default {@code EUR} — the sandbox uses EUR, not GHS)</li>
 *   <li>{@code MTN_SANDBOX_TARGET_ENV} (optional, default {@code sandbox})</li>
 * </ul>
 *
 * <h2>Why this needs NO change to main source</h2>
 * The production rail's host-lock ({@code MtnProductionRail.productionBase}) guards only the Spring
 * {@code @Value} constructor used in production; the public direct-URI constructor already lets
 * tests point the adapter anywhere, so reaching the sandbox for manual testing needs no gated
 * allowance. This test issues the HTTP calls directly because two things the sandbox requires
 * cannot be met by the production adapter without changing production code:
 * <ul>
 *   <li>{@code MtnProductionRail} hardcodes the production header {@code X-Target-Environment: mtnghana};
 *       the sandbox requires {@code sandbox}.</li>
 *   <li>{@code MtnProductionRail.submit} enforces currency {@code GHS}; the sandbox requires {@code EUR}.</li>
 * </ul>
 *
 * <h2>If a gated sandbox mode were ever wanted in main source (deliberately NOT done here)</h2>
 * It would require, all gated so they are impossible when {@code elepay.environment=production}:
 * (1) permit host {@code sandbox.momodeveloper.mtn.com} in {@code productionBase} only under a
 * non-production profile/flag; (2) make {@code X-Target-Environment} configurable; (3) allow a
 * non-GHS test currency. That is a cross-cutting change to a production money rail with real
 * downside if mis-gated, so it is intentionally left out; this disabled-by-default live IT covers
 * the manual-testing need instead.
 */
@DisplayName("LIVE MTN sandbox provisioning + requesttopay (env-gated, skipped without credentials)")
@EnabledIfEnvironmentVariable(named = "MTN_SANDBOX_SUBSCRIPTION_KEY", matches = ".+")
class MtnSandboxLiveProvisioningIT {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Test
    void provisionsSandboxUserGetsTokenAndRunsRequestToPay() throws Exception {
        URI base = URI.create(env("MTN_SANDBOX_BASE", "https://sandbox.momodeveloper.mtn.com"));
        String subscriptionKey = System.getenv("MTN_SANDBOX_SUBSCRIPTION_KEY"); // required (gates this test)
        String callbackHost = env("MTN_SANDBOX_CALLBACK_HOST", "elepay.example");
        String payerMsisdn = env("MTN_SANDBOX_MSISDN", "46733123450");
        String currency = env("MTN_SANDBOX_CURRENCY", "EUR"); // sandbox uses EUR
        String targetEnv = env("MTN_SANDBOX_TARGET_ENV", "sandbox");

        // 1. Create an API user. X-Reference-Id IS the api user id.
        String apiUser = UUID.randomUUID().toString();
        HttpResponse<String> createUser = send(HttpRequest.newBuilder(base.resolve("/v1_0/apiuser"))
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .header("X-Reference-Id", apiUser)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("providerCallbackHost", callbackHost)))).build());
        assertThat(createUser.statusCode()).as("create apiuser: %s", createUser.body()).isEqualTo(201);

        // 2. Create an API key for that user.
        HttpResponse<String> createKey = send(HttpRequest.newBuilder(base.resolve("/v1_0/apiuser/" + apiUser + "/apikey"))
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(createKey.statusCode()).as("create apikey: %s", createKey.body()).isEqualTo(201);
        String apiKey = json.readTree(createKey.body()).path("apiKey").asString();
        assertThat(apiKey).isNotBlank();

        // 3. Obtain a collection OAuth token (Basic apiUser:apiKey).
        String basic = Base64.getEncoder().encodeToString((apiUser + ":" + apiKey).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> token = send(HttpRequest.newBuilder(base.resolve("/collection/token/"))
                .header("Authorization", "Basic " + basic)
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(token.statusCode()).as("token: %s", token.body()).isEqualTo(200);
        String accessToken = json.readTree(token.body()).path("access_token").asString();
        assertThat(accessToken).isNotBlank();

        // 4. requesttopay. The transaction's X-Reference-Id uses the production adapter's real logic.
        String reference = "EPCLIVE" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String txRef = MtnProductionRail.providerId("charge", reference).toString();
        Map<String, Object> body = Map.of(
                "amount", BigDecimal.valueOf(100, 2).toPlainString(),
                "currency", currency,
                "externalId", reference,
                "payer", Map.of("partyIdType", "MSISDN", "partyId", payerMsisdn),
                "payerMessage", "ELEPAY " + reference,
                "payeeNote", "ELEPAY " + reference);
        HttpResponse<String> pay = send(HttpRequest.newBuilder(base.resolve("/collection/v1_0/requesttopay"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .header("X-Reference-Id", txRef)
                .header("X-Target-Environment", targetEnv)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
        assertThat(pay.statusCode()).as("requesttopay accepted: %s", pay.body()).isEqualTo(202);

        // 5. Poll status: must be a recognised lifecycle state.
        HttpResponse<String> status = send(HttpRequest.newBuilder(base.resolve("/collection/v1_0/requesttopay/" + txRef))
                .header("Authorization", "Bearer " + accessToken)
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .header("X-Target-Environment", targetEnv)
                .header("Accept", "application/json")
                .GET().build());
        assertThat(status.statusCode()).as("status: %s", status.body()).isEqualTo(200);
        JsonNode node = json.readTree(status.body());
        assertThat(node.path("externalId").asString()).isEqualTo(reference);
        assertThat(node.path("status").asString()).isIn("PENDING", "SUCCESSFUL", "FAILED");
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
