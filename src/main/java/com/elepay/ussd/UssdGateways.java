package com.elepay.ussd;

import com.elepay.common.ApiException;
import com.elepay.common.CallbackAuthenticity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private final JdbcClient db;
    private final String genericSecret;

    UssdGateways(UssdMenu menu, ObjectMapper json, JdbcClient db,
                 @Value("${elepay.ussd.generic.secret:}") String genericSecret) {
        this.menu = menu;
        this.json = json;
        this.db = db;
        this.genericSecret = genericSecret;
    }

    @PostMapping("/v1/ussd/generic")
    public Map<String, Object> generic(@RequestBody String body,
                                       @RequestHeader(value = "X-Ussd-Signature", required = false) String signature,
                                       @RequestHeader(value = "X-Ussd-Timestamp", required = false) String timestamp,
                                       @RequestHeader(value = "X-Ussd-Nonce", required = false) String nonce) {
        if (genericSecret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ussd_not_connected", "No USSD gateway is connected");
        }
        try {
            CallbackAuthenticity.verify(genericSecret, Map.of(
                    "x-callback-timestamp", timestamp == null ? "" : timestamp,
                    "x-callback-nonce", nonce == null ? "" : nonce,
                    "x-ussd-signature", signature == null ? "" : signature
            ), body, "x-ussd-signature");
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_signature", "Invalid gateway signature");
        }
        int stored = db.sql("INSERT INTO callback_replay_nonce (nonce) VALUES (:n) ON CONFLICT DO NOTHING")
                .param("n", "ussd:" + nonce).update();
        if (stored == 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "replayed_callback", "This gateway request was already used");
        }
        JsonNode node = json.readTree(body);
        String type = node.path("type").asString("continue");
        if (type.equals("end")) return Map.of("message", "", "end", true);
        var response = menu.handle(new UssdMenu.Request("generic", node.path("sessionId").asString(),
                node.path("msisdn").asString(), node.path("input").asString(""), type.equals("start")));
        return Map.of("message", response.message(), "end", response.end());
    }

    static String sign(String secret, String timestamp, String nonce, String body) {
        return com.elepay.common.HmacVerification.computeHex(secret, timestamp + "." + nonce + "." + body);
    }
}
