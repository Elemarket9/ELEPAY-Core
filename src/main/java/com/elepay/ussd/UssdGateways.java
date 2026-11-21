package com.elepay.ussd;

import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * USSD gateway entry point. A mobile-network or aggregator USSD connection
 * forwards each session step here. The {@code generic} protocol is JSON
 * ({@code sessionId}, {@code msisdn}, {@code input}, {@code type}: start,
 * continue or end) signed with {@code X-Ussd-Signature} = hex(HMAC-SHA256(secret,
 * body)), and returns {@code message} and {@code end}. Network-specific
 * gateway formats are added as further adapters when a connection is
 * contracted. The gateway is disabled unless its secret is configured.
 */
@RestController
class UssdGateways {
    private final UssdMenu menu;
    private final ObjectMapper json;
    private final String genericSecret;

    UssdGateways(UssdMenu menu, ObjectMapper json, @Value("${elepay.ussd.generic.secret:}") String genericSecret) {
        this.menu = menu;
        this.json = json;
        this.genericSecret = genericSecret;
    }

    @PostMapping("/v1/ussd/generic")
    public Map<String, Object> generic(@RequestBody String body,
                                       @RequestHeader(value = "X-Ussd-Signature", required = false) String signature) {
        if (genericSecret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ussd_not_connected", "No USSD gateway is connected");
        }
        if (signature == null || !MessageDigest.isEqual(signature.toLowerCase().getBytes(StandardCharsets.UTF_8),
                sign(genericSecret, body).getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_signature", "Invalid gateway signature");
        }
        JsonNode node = json.readTree(body);
        String type = node.path("type").asString("continue");
        if (type.equals("end")) return Map.of("message", "", "end", true);
        var response = menu.handle(new UssdMenu.Request("generic", node.path("sessionId").asString(),
                node.path("msisdn").asString(), node.path("input").asString(""), type.equals("start")));
        return Map.of("message", response.message(), "end", response.end());
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
