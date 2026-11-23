package com.elepay.ussd;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** USSD through the signed generic gateway protocol (no network USSD connection is configured). */
class UssdIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;

    TestApi api;
    String ops, phone, recipientPhone;
    UUID customer, recipient;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString();
        ops = api.token("ops-" + s, "ops");
        phone = TestApi.uniquePhone();
        recipientPhone = TestApi.uniquePhone();
        customer = api.enroll(ops, "ussd-" + s, phone, "Yaa Basic Phone");
        recipient = api.enroll(ops, "ussd-r-" + s, recipientPhone, "Kojo Recipient");
        assertThat(api.call("PUT", "/v1/wallet/ussd-pin", api.token("ussd-" + s), null, Map.of("pin", "4826")).status())
                .isEqualTo(200);
        ledger.post(Ledger.Posting.of("test-funding", s, "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 10_000),
                Ledger.Leg.credit(Ledger.code("customer", customer, "available", "GHS"), 10_000))));
    }

    JsonNode step(String session, String type, String input) {
        String body = json.writeValueAsString(Map.of("sessionId", session, "msisdn", phone, "type", type, "input", input));
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        return api.http.post().uri("/v1/ussd/generic").header("Content-Type", "application/json")
                .header("X-Ussd-Timestamp", timestamp)
                .header("X-Ussd-Nonce", nonce)
                .header("X-Ussd-Signature", UssdGateways.sign("test-ussd-secret", timestamp, nonce, body)).body(body)
                .retrieve().body(JsonNode.class);
    }

    @Test
    void balanceSendWithNameConfirmationAndReplaySafety() {
        String a = UUID.randomUUID().toString();
        assertThat(step(a, "start", "").get("message").asString()).isEqualTo(UssdMenu.ROOT);
        assertThat(step(a, "continue", "1").get("message").asString()).isEqualTo("Enter PIN");
        JsonNode balance = step(a, "continue", "4826");
        assertThat(balance.get("message").asString()).isEqualTo("Available balance: GHS 100.00");
        assertThat(balance.get("end").asBoolean()).isTrue();

        String b = UUID.randomUUID().toString();
        step(b, "start", "");
        step(b, "continue", "2");
        JsonNode confirm = step(b, "continue", "0" + recipientPhone.substring(3));
        assertThat(confirm.get("message").asString()).contains("Kojo Recipient").contains("+" + recipientPhone);
        assertThat(step(b, "continue", "25.50").get("message").asString()).contains("GHS 25.50").contains("Enter PIN");
        JsonNode sent = step(b, "continue", "4826");
        assertThat(sent.get("message").asString()).startsWith("Sent GHS 25.50 to Kojo Recipient");
        assertThat(ledger.balance(Ledger.code("customer", recipient, "available", "GHS"))).isEqualTo(2_550);
        assertThat(ledger.balance(Ledger.code("customer", customer, "available", "GHS"))).isEqualTo(7_450);
    }

    @Test
    void wrongPinsLockTheChannelAndForgedRequestsAreRejected() {
        for (int i = 0; i < 2; i++) {
            String s = UUID.randomUUID().toString();
            step(s, "start", "");
            step(s, "continue", "1");
            assertThat(step(s, "continue", "0000").get("message").asString()).startsWith("Wrong PIN");
        }
        String s = UUID.randomUUID().toString();
        step(s, "start", "");
        step(s, "continue", "1");
        assertThat(step(s, "continue", "0000").get("message").asString()).startsWith("Too many wrong PINs");
        String t = UUID.randomUUID().toString();
        step(t, "start", "");
        step(t, "continue", "1");
        assertThat(step(t, "continue", "4826").get("message").asString()).startsWith("Too many wrong PINs");

        int forged = api.http.post().uri("/v1/ussd/generic").header("Content-Type", "application/json")
                .header("X-Ussd-Signature", "00").body("{\"sessionId\":\"x\",\"msisdn\":\"" + phone + "\",\"type\":\"start\"}")
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(forged).isEqualTo(401);
    }

    @Test
    void payMerchantCodeAndInsufficientFundsEndsCleanly() {
        String owner = api.token("shop-" + UUID.randomUUID());
        JsonNode m = api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", "Kiosk", "tradingName", "Auntie Kiosk",
                "kind", "merchant", "contactPhone", "0241234567")).body();
        api.call("POST", "/v1/ops/merchants/" + m.get("id").asString() + "/review", ops, null, Map.of("decision", "approve"));
        String s = UUID.randomUUID().toString();
        step(s, "start", "");
        step(s, "continue", "3");
        assertThat(step(s, "continue", m.get("merchantCode").asString()).get("message").asString()).contains("Auntie Kiosk");
        assertThat(step(s, "continue", "5").get("message").asString()).contains("GHS 5.00");
        assertThat(step(s, "continue", "4826").get("message").asString()).startsWith("Paid GHS 5.00 to Auntie Kiosk");
        assertThat(ledger.balance(Ledger.code("merchant", UUID.fromString(m.get("id").asString()), "pending", "GHS"))).isEqualTo(500);

        String t = UUID.randomUUID().toString();
        step(t, "start", "");
        step(t, "continue", "3");
        step(t, "continue", m.get("merchantCode").asString());
        step(t, "continue", "500");
        JsonNode declined = step(t, "continue", "4826");
        assertThat(declined.get("end").asBoolean()).isTrue();
        assertThat(declined.get("message").asString()).containsIgnoringCase("insufficient");
        assertThat(ledger.balance(Ledger.code("customer", customer, "available", "GHS"))).isEqualTo(9_500);
    }
}
