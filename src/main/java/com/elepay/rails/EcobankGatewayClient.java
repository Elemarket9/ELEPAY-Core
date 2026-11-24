package com.elepay.rails;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A product-scoped Ecobank client. Callers supply bank-signed request envelopes. */
public final class EcobankGatewayClient {
    public enum Product { LOCAL_PAYMENT, ACCOUNT_ENQUIRY }

    public enum Operation {
        ACCOUNT_TRANSFER(Product.LOCAL_PAYMENT, "/corp-payment/api/v2/payment/account/transfer"),
        DOMESTIC_TRANSFER(Product.LOCAL_PAYMENT, "/corp-payment/api/v2/payment/domestic"),
        TRANSACTION_STATUS(Product.LOCAL_PAYMENT, "/corp-payment/api/v2/integration/payment/status"),
        BALANCE(Product.ACCOUNT_ENQUIRY, "/corp-account-inquiry/api/v2/integration/account/inquiry/balance"),
        ACCOUNT_INFO(Product.ACCOUNT_ENQUIRY, "/corp-account-inquiry/api/v2/integration/account/inquiry/info");

        private final Product product;
        private final String path;

        Operation(Product product, String path) {
            this.product = product;
            this.path = path;
        }
    }

    private static final String AUTH_PATH = "/corp-auth/api/v2/integration/auth/app/token";
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final URI origin;
    private final Product product;
    private final String subscriptionKey;

    public EcobankGatewayClient(ObjectMapper json, URI origin, Product product, String subscriptionKey) {
        this.json = Objects.requireNonNull(json);
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null
                || !origin.getHost().endsWith(".ecobank.com") || origin.getRawUserInfo() != null
                || (origin.getPort() != -1 && origin.getPort() != 443)
                || !java.util.Set.of("", "/").contains(origin.getPath())
                || origin.getRawQuery() != null || origin.getRawFragment() != null) {
            throw new IllegalArgumentException("Expected an Ecobank HTTPS gateway origin");
        }
        if (subscriptionKey == null || subscriptionKey.isBlank()) {
            throw new IllegalArgumentException("A product subscription key is required");
        }
        this.origin = origin;
        this.product = Objects.requireNonNull(product);
        this.subscriptionKey = subscriptionKey;
    }

    /** Obtains a fresh service token and submits once. The caller must reconcile uncertain submissions. */
    public JsonNode execute(Operation operation, JsonNode signedTokenRequest, JsonNode signedRequest)
            throws IOException, InterruptedException {
        if (operation.product != product || signedTokenRequest == null || !signedTokenRequest.isObject()
                || signedRequest == null || !signedRequest.isObject()) {
            throw new IllegalArgumentException("Expected signed envelopes for the configured product");
        }
        JsonNode auth = post(AUTH_PATH, signedTokenRequest, null);
        String token = auth.path("data").path("access_token").asText("");
        if (!"000".equals(auth.path("headerResponse").path("responseCode").asText(""))
                || token.isBlank() || !token.matches("[A-Za-z0-9._~+/=-]+")) {
            throw new IOException("Ecobank did not issue a usable service token");
        }
        return post(operation.path, signedRequest, token);
    }

    /** Signs a single domestic GHS transfer using the bank's documented field order. */
    public JsonNode domesticTransfer(JsonNode signedTokenRequest, String clientId, String affiliateCode,
            String sourceCode, String requestId, String ipAddress, String signingSecret,
            String receiverAccountNo, long amountPesewas, String description)
            throws IOException, InterruptedException {
        if (amountPesewas <= 0) throw new IllegalArgumentException("Expected a positive payout amount");
        for (String value : new String[]{clientId, affiliateCode, sourceCode, requestId, ipAddress,
                signingSecret, receiverAccountNo, description}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing Ecobank payout field");
        }
        String requestType = "DOMESTIC_TRANSFER";
        String prefix = clientId + affiliateCode + sourceCode + requestId + requestType + ipAddress;
        String requestToken = sha512(prefix + signingSecret);
        java.math.BigDecimal amount = java.math.BigDecimal.valueOf(amountPesewas, 2);
        var body = json.createObjectNode();
        var header = body.putObject("headerRequest");
        header.put("clientId", clientId);
        header.put("affiliateCode", affiliateCode);
        header.put("sourceCode", sourceCode);
        header.put("requestId", requestId);
        header.put("requestType", requestType);
        header.put("ipAddress", ipAddress);
        header.put("requestToken", requestToken);
        body.put("receiverAccountNo", receiverAccountNo);
        body.put("amount", amount);
        body.put("currency", "GHS");
        body.put("description", description);
        body.put("secureHash", sha512(prefix + requestToken + receiverAccountNo + amount.toPlainString()
                + "GHS" + description + signingSecret));
        return execute(Operation.DOMESTIC_TRANSFER, signedTokenRequest, body);
    }

    private static String sha512(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-512");
            return java.util.HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    private JsonNode post(String path, JsonNode envelope, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(envelope)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Ecobank returned HTTP " + response.statusCode());
        }
        try {
            JsonNode body = json.readTree(response.body());
            if (body != null && body.isObject()) return body;
        } catch (tools.jackson.core.JacksonException ignored) {
            // Parsing exceptions may contain sensitive fragments of the response.
        }
        throw new IOException("Ecobank returned an invalid JSON response");
    }
}
