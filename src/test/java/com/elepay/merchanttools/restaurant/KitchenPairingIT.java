package com.elepay.merchanttools.restaurant;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.identity.KitchenDevicePaths;
import com.elepay.identity.RequestSigningFilter;
import com.elepay.merchanttools.pos.PosOrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** A kitchen tablet pairs once with a code and can then only use the kitchen display, nothing else. */
class KitchenPairingIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired JdbcClient db;
    @Autowired KdsService kds;
    @Autowired PosOrderService orders;

    TestApi api;
    String owner, ops;
    UUID merchant;
    String merchantCode;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + s, "ops");
        owner = api.token("chef-" + s);
        merchant = UUID.fromString(api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", "Chop Bar " + s,
                "tradingName", "Chop Bar " + s, "kind", "merchant", "contactPhone", "0241234567")).body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + merchant + "/review", ops, null, Map.of("decision", "approve"));
        merchantCode = api.call("GET", "/v1/merchants/" + merchant, owner, null, null).body().get("merchantCode").asString();
    }

    private String newCode() {
        var r = api.call("POST", "/v1/restaurant/kds/devices/pairing-codes", owner, null, Map.of("merchantId", merchant.toString()));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("merchantCode").asString()).isEqualTo(merchantCode);
        return r.body().get("code").asString();
    }

    private TestApi.Response pair(String code) {
        return api.call("POST", "/v1/public/kitchen/pair", null, null,
                Map.of("merchantCode", merchantCode, "code", code, "deviceName", "Kitchen tablet"));
    }

    private TestApi.Response withKey(String method, String path, String key, Object body) {
        return switch (method) {
            case "GET" -> api.http.get().uri(path).header("X-Elepay-Api-Key", key)
                    .exchange((rq, rs) -> new TestApi.Response(rs.getStatusCode().value(), read(rs.getBody().readAllBytes())));
            default -> api.http.post().uri(path).header("X-Elepay-Api-Key", key).body(body == null ? Map.of() : body)
                    .exchange((rq, rs) -> new TestApi.Response(rs.getStatusCode().value(), read(rs.getBody().readAllBytes())));
        };
    }

    private static JsonNode read(byte[] bytes) {
        ObjectMapper json = new ObjectMapper();
        return bytes.length == 0 ? json.nullNode() : json.readTree(bytes);
    }

    private UUID ticket() {
        var o = orders.createOrder(merchant, null, null, null, null, List.of(new PosOrderService.OrderItemInput(
                null, "Red Red", BigDecimal.ONE, 2000L, 0L, 0L, "[]", null, "MAIN", null)), 0, 0, 0, "RESTAURANT");
        var item = orders.listOrderItems(o.id()).get(0);
        return kds.createTicket(merchant, o.id(), null, DiningMode.TAKEOUT, "Ama", null,
                List.of(new KdsService.KdsItemInput(item.id(), "Red Red", BigDecimal.ONE, null, null)), null, null).id();
    }

    @Test
    @DisplayName("Pairing gives a kitchen-only credential that works for the kitchen screen and nothing else")
    void pairedDeviceIsLimitedToKitchen() {
        var paired = pair(newCode());
        assertThat(paired.status()).isEqualTo(200);
        String key = paired.body().get("apiKey").asString();
        assertThat(key).startsWith("kd_live_");
        assertThat(paired.body().get("merchantId").asString()).isEqualTo(merchant.toString());

        UUID t = ticket();
        var sync = withKey("GET", "/v1/restaurant/kds/sync?merchantId=" + merchant, key, null);
        assertThat(sync.status()).isEqualTo(200);
        assertThat(sync.body().get("tickets").size()).isEqualTo(1);
        var bump = withKey("POST", "/v1/restaurant/kds/tickets/" + t + "/advance", key, Map.of("from", "NEW", "actionId", "tab-1"));
        assertThat(bump.status()).isEqualTo(200);
        assertThat(bump.body().get("applied").asBoolean()).isTrue();

        // Everything outside the kitchen display is refused at the gateway.
        assertThat(withKey("GET", "/v1/merchants", key, null).status()).isEqualTo(403);
        assertThat(withKey("GET", "/v1/merchants/" + merchant + "/transactions", key, null).status()).isEqualTo(403);
        assertThat(withKey("POST", "/v1/merchants/" + merchant + "/checkouts", key, Map.of()).status()).isEqualTo(403);
        assertThat(withKey("POST", "/v1/restaurant/kds/stations", key, Map.of("merchantId", merchant.toString(), "name", "X")).status())
                .isEqualTo(403);
        assertThat(withKey("POST", "/v1/restaurant/kds/devices/pairing-codes", key, Map.of("merchantId", merchant.toString())).status())
                .isEqualTo(403);

        // And it cannot see another merchant's kitchen.
        String otherOwner = api.token("other-" + UUID.randomUUID().toString().substring(0, 8));
        UUID other = UUID.fromString(api.call("POST", "/v1/merchants", otherOwner, null, Map.of("legalName", "Other",
                "tradingName", "Other", "kind", "merchant", "contactPhone", "0241234567")).body().get("id").asString());
        assertThat(withKey("GET", "/v1/restaurant/kds/sync?merchantId=" + other, key, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("A pairing code works once, and five wrong guesses cancel the open codes")
    void codesAreSingleUseAndGuessingIsCut() {
        String code = newCode();
        assertThat(pair(code).status()).isEqualTo(200);
        assertThat(pair(code).status()).isEqualTo(400);

        String fresh = newCode();
        String wrong = fresh.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < KitchenDeviceService.MAX_FAILED; i++) assertThat(pair(wrong).status()).isEqualTo(400);
        assertThat(pair(fresh).status()).as("right code after too many wrong guesses").isEqualTo(400);
        assertThat(pair(newCode()).status()).as("a new code from the manager works").isEqualTo(200);
    }

    @Test
    @DisplayName("Expired codes are refused")
    void expiredCodeRefused() {
        String code = newCode();
        db.sql("UPDATE restaurant_kitchen_pairing SET expires_at = now() - interval '1 minute' WHERE merchant_id = :m")
                .param("m", merchant).update();
        assertThat(pair(code).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("Revoking a device in the dashboard cuts it off")
    void revokedDeviceIsRefused() {
        var paired = pair(newCode()).body();
        String key = paired.get("apiKey").asString();
        var devices = api.call("GET", "/v1/restaurant/kds/devices/merchant/" + merchant, owner, null, null).body();
        assertThat(devices.size()).isEqualTo(1);
        assertThat(api.call("POST", "/v1/restaurant/kds/devices/" + paired.get("deviceId").asString() + "/revoke?merchantId=" + merchant,
                owner, null, null).status()).isEqualTo(200);
        assertThat(withKey("GET", "/v1/restaurant/kds/sync?merchantId=" + merchant, key, null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Kitchen keys cannot be made through the general API key endpoint")
    void genericKeyEndpointRefusesKitchenType() {
        var r = api.call("POST", "/v1/merchants/" + merchant + "/api-keys", owner, null,
                Map.of("label", "sneaky", "scopes", List.of("view"), "keyType", "kitchen"));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("Devices sign their updates with their own secret when the server requires signatures")
    void signedDeviceRequestAccepted() {
        var paired = pair(newCode()).body();
        String key = paired.get("apiKey").asString();
        String secret = paired.get("signingSecret").asString();
        UUID t = ticket();
        String path = "/v1/restaurant/kds/tickets/" + t + "/advance";
        String body = "{\"from\":\"NEW\",\"actionId\":\"signed-1\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "n-" + UUID.randomUUID();
        String sig = RequestSigningFilter.computeSignatureV2(secret, ts, "POST", path, null, nonce, null, body);
        int ok = api.http.post().uri(path).header("X-Elepay-Api-Key", key).header("X-Elepay-Enforce-Signature", "true")
                .header("X-Elepay-Signature", sig).header("X-Elepay-Signature-Version", "2")
                .header("X-Elepay-Timestamp", ts).header("X-Elepay-Nonce", nonce)
                .header("Content-Type", "application/json").body(body)
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(ok).isEqualTo(200);
        int forged = api.http.post().uri(path).header("X-Elepay-Api-Key", key).header("X-Elepay-Enforce-Signature", "true")
                .header("X-Elepay-Signature", "00".repeat(32)).header("X-Elepay-Signature-Version", "2")
                .header("X-Elepay-Timestamp", ts).header("X-Elepay-Nonce", "n-" + UUID.randomUUID())
                .header("Content-Type", "application/json").body(body)
                .exchange((rq, rs) -> rs.getStatusCode().value());
        assertThat(forged).isEqualTo(401);
    }

    @Test
    void devicePathRules() {
        String id = UUID.randomUUID().toString();
        assertThat(KitchenDevicePaths.allowed("GET", "/v1/restaurant/kds/sync")).isTrue();
        assertThat(KitchenDevicePaths.allowed("POST", "/v1/restaurant/kds/tickets/" + id + "/advance")).isTrue();
        assertThat(KitchenDevicePaths.allowed("POST", "/v1/restaurant/kds/tickets/" + id + "/cancel")).isFalse();
        assertThat(KitchenDevicePaths.allowed("POST", "/v1/restaurant/kds/print-jobs/claim")).isTrue();
        assertThat(KitchenDevicePaths.allowed("GET", "/v1/restaurant/kds/tickets/" + id + "/advance")).isFalse();
        assertThat(KitchenDevicePaths.allowed("POST", "/v1/restaurant/kds/stations")).isFalse();
        assertThat(KitchenDevicePaths.allowed("POST", "/v1/restaurant/kds/tickets")).isFalse();
        assertThat(KitchenDevicePaths.allowed("DELETE", "/v1/restaurant/kds/sync")).isFalse();
        assertThat(KitchenDevicePaths.allowed("GET", "/v1/restaurant/kds/sync/../../merchants")).isFalse();
    }
}
