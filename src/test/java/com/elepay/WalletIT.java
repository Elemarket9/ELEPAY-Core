package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

class WalletIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;

    @Test
    void transfersPostOnceAndWalletReadsAreScopedToTheCaller() {
        var http = RestClient.create("http://127.0.0.1:" + port);
        String s = UUID.randomUUID().toString();
        String senderToken = token(http, s, List.of());
        String recipientToken = token(http, s + "-recipient", List.of());
        String ops = token(http, s + "-ops", List.of("ops"));
        UUID sender = enroll(http, ops, s);
        UUID recipient = enroll(http, ops, s + "-recipient");
        String source = Ledger.code("customer", sender, "available", "GHS");
        ledger.post(Ledger.Posting.of("wallet-test", s, "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 15000),
                Ledger.Leg.credit(source, 15000))));
        Map<String, Object> transfer = Map.of("recipient", recipient, "amount", 5000);
        JsonNode first = send(http, senderToken, s, transfer);
        assertThat(send(http, senderToken, s, transfer)).isEqualTo(first);
        int conflict = http.post().uri("/v1/wallet/transfers")
                .headers(h -> h.setBearerAuth(senderToken)).header("Idempotency-Key", s)
                .body(Map.of("recipient", recipient, "amount", 1))
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(conflict).isEqualTo(409);
        assertThat(ledger.balance(source)).isEqualTo(10000);
        assertThat(ledger.balance(Ledger.code("customer", recipient, "available", "GHS"))).isEqualTo(5000);
        JsonNode recipientWallet = http.get().uri("/v1/wallet").headers(h -> h.setBearerAuth(recipientToken))
                .retrieve().body(JsonNode.class);
        assertThat(recipientWallet.get("customerId").asString()).isEqualTo(recipient.toString());
        assertThat(ledger.integrity().ok()).isTrue();
    }

    private JsonNode send(RestClient http, String token, String key, Map<String, Object> transfer) {
        return http.post().uri("/v1/wallet/transfers").headers(h -> h.setBearerAuth(token))
                .header("Idempotency-Key", key).body(transfer).retrieve().body(JsonNode.class);
    }

    private UUID enroll(RestClient http, String token, String subject) {
        String phone = "233" + String.format("%09d", Math.floorMod(UUID.randomUUID().hashCode(), 1000000000));
        JsonNode body = http.post().uri("/v1/ops/customers").headers(h -> h.setBearerAuth(token))
                .body(Map.of("subject", subject, "phone", phone, "displayName", "Wallet test"))
                .retrieve().body(JsonNode.class);
        return UUID.fromString(body.get("customerId").asString());
    }

    private String token(RestClient http, String subject, List<String> roles) {
        return http.post().uri("/dev/token").body(Map.of("subject", subject, "roles", roles))
                .retrieve().body(JsonNode.class).get("accessToken").asString();
    }
}
