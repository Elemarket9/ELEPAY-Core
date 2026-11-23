package com.elepay;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Thin HTTP client for acceptance tests that exercise the real entry points. */
public final class TestApi {
    public record Response(int status, JsonNode body) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    public final RestClient http;

    public TestApi(int port) {
        this.http = RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()).build();
    }

    public String token(String subject, String... roles) {
        return http.post().uri("/dev/token").body(Map.of("subject", subject, "roles", List.of(roles)))
                .retrieve().body(JsonNode.class).get("accessToken").asString();
    }

    public static String uniquePhone() {
        return "233" + (200000000 + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 700000000L));
    }

    public UUID enroll(String opsToken, String subject, String phone, String name) {
        return UUID.fromString(call("POST", "/v1/ops/customers", opsToken, null,
                Map.of("subject", subject, "phone", phone, "displayName", name)).body().get("customerId").asString());
    }

    public Response call(String method, String path, String token, String idempotencyKey, Object body) {
        Consumer<HttpHeaders> headers = h -> {
            if (token != null) h.setBearerAuth(token);
            if (idempotencyKey != null) h.set("Idempotency-Key", idempotencyKey);
        };
        var spec = switch (method) {
            case "GET" -> http.get().uri(path).headers(headers);
            case "PUT" -> http.put().uri(path).headers(headers).body(body == null ? Map.of() : body);
            case "DELETE" -> http.delete().uri(path).headers(headers);
            default -> http.post().uri(path).headers(headers).body(body == null ? Map.of() : body);
        };
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            JsonNode node = bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes);
            return new Response(response.getStatusCode().value(), node);
        });
    }
}
