package com.elepay.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Acceptance test 5 against the SIMULATED partner {@code sim-remit}. No
 * authorized remittance-partner test environment is available, so these are
 * simulated results, not partner-test or live results.
 */
class FxIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired SimulatedFxPartner partner;

    TestApi api;
    String ops, token, phone;
    UUID customer;
    static final String OUT = "GH-US-GHS-USD-WALLET-BANK-SIM";
    static final String IN = "US-GH-USD-GHS-PARTNER-WALLET-SIM";

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString();
        ops = api.token("ops-" + s, "ops");
        corridor(OUT, "outbound", "GH", "US", "GHS", "USD", "wallet", "bank", 100, 100);
        corridor(IN, "inbound", "US", "GH", "USD", "GHS", "partner_collected", "wallet", 0, 0);
        phone = TestApi.uniquePhone();
        customer = api.enroll(ops, "fx-" + s, phone, "Kwame Fx");
        token = api.token("fx-" + s);
        ledger.post(Ledger.Posting.of("test-funding", s, "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 100_000),
                Ledger.Leg.credit(Ledger.code("customer", customer, "available", "GHS"), 100_000))));
    }

    void corridor(String code, String direction, String from, String to, String src, String dst, String funding,
                  String delivery, long feeFixed, int feeBps) {
        var r = api.call("PUT", "/v1/ops/fx/corridors/" + code, ops, null, Map.ofEntries(
                Map.entry("direction", direction), Map.entry("sendingCountry", from), Map.entry("receivingCountry", to),
                Map.entry("sourceCurrency", src), Map.entry("destinationCurrency", dst), Map.entry("fundingMethod", funding),
                Map.entry("deliveryMethod", delivery), Map.entry("partner", "sim-remit"), Map.entry("feeFixed", feeFixed),
                Map.entry("feeBps", feeBps), Map.entry("minAmount", 100), Map.entry("maxAmount", 10_000_000),
                Map.entry("quoteTtlSeconds", 600), Map.entry("estDeliveryMinutes", 1440), Map.entry("active", true)));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    }

    long ghs() { return ledger.balance(Ledger.code("customer", customer, "available", "GHS")); }
    long usdInFlight() { return ledger.balance("system:fx_in_flight:USD"); }
    long partnerUsd() { return ledger.balance("system:clearing:fx-sim-remit:USD"); }

    JsonNode quote(long amount) {
        var q = api.call("POST", "/v1/fx/quotes", token, null, Map.of("corridor", OUT, "sourceAmount", amount));
        assertThat(q.status()).as(q.body().toString()).isEqualTo(200);
        return q.body();
    }

    TestApi.Response send(JsonNode quote, String account) {
        return api.call("POST", "/v1/fx/transfers", token, UUID.randomUUID().toString(), Map.of(
                "quoteId", quote.get("id").asString(),
                "recipient", Map.of("name", "Jane Doe", "accountNumber", account, "bankCode", "021000021")));
    }

    void notify(String reference, String status) {
        var signed = partner.signed(Map.of("reference", reference, "status", status));
        api.http.post().uri("/v1/fx/partners/sim-remit/callbacks").header("x-partner-signature", signed.getKey())
                .header("Content-Type", "application/json").body(signed.getValue())
                .exchange((req, res) -> res.getStatusCode().value());
    }

    @Test
    void quoteShowsTermsAndExecutionIsBoundToTheAcceptedQuote() {
        long inFlight0 = usdInFlight();
        long partner0 = partnerUsd();
        JsonNode q = quote(64_000);  // GH₵640.00
        assertThat(q.get("rate").decimalValue()).isEqualByComparingTo("0.0640");
        assertThat(q.get("fee").asLong()).isEqualTo(100 + 640);          // fixed + 1%
        assertThat(q.get("totalDebit").asLong()).isEqualTo(64_740);
        assertThat(q.get("destinationAmount").asLong()).isEqualTo(4_096); // US$40.96
        assertThat(q.get("expiresAt").isNull()).isFalse();
        assertThat(q.get("estimatedDeliveryMinutes").asInt()).isEqualTo(1440);

        var sent = send(q, "123456789");
        assertThat(sent.status()).as(sent.body().toString()).isEqualTo(200);
        String reference = sent.body().get("reference").asString();
        assertThat(sent.body().get("status").asString()).isEqualTo("pending");
        assertThat(ghs()).isEqualTo(100_000 - 64_740);
        assertThat(usdInFlight() - inFlight0).isEqualTo(4_096);
        // The same quote cannot be executed twice.
        assertThat(send(q, "123456789").body().get("code").asString()).isEqualTo("quote_accepted");

        notify(reference, "paid");
        notify(reference, "paid"); // duplicate
        assertThat(api.call("GET", "/v1/fx/transfers/" + reference, token, null, null).body().get("status").asString())
                .isEqualTo("paid");
        assertThat(usdInFlight() - inFlight0).isZero();
        assertThat(partnerUsd() - partner0).isEqualTo(4_096);
        assertThat(ledger.integrity().ok()).isTrue(); // every currency nets to zero

        // Expired and changed quotes require fresh approval.
        JsonNode expired = quote(10_000);
        db.sql("UPDATE fx_quote SET expires_at = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
                .param("id", expired.get("id").asString()).update();
        assertThat(send(expired, "123456789").body().get("code").asString()).isEqualTo("quote_expired");
        JsonNode changed = quote(10_000);
        partner.withdraw(db.sql("SELECT partner_quote_ref FROM fx_quote WHERE id = CAST(:id AS uuid)")
                .param("id", changed.get("id").asString()).query(String.class).single());
        assertThat(send(changed, "123456789").body().get("code").asString()).isEqualTo("quote_changed");
        assertThat(ghs()).isEqualTo(100_000 - 64_740);
    }

    @Test
    void failedAndReturnedTransfersRefundTheSourceCurrencyAndReconcile() {
        long inFlight0 = usdInFlight();
        long partner0 = partnerUsd();
        String failed = send(quote(20_000), "555555551").body().get("reference").asString();
        String returned = send(quote(30_000), "555555557").body().get("reference").asString();
        String paid = send(quote(10_000), "555555558").body().get("reference").asString();
        api.call("POST", "/v1/ops/fx/recover", ops, null, null);
        assertThat(status(failed)).isEqualTo("failed");
        assertThat(status(returned)).isEqualTo("paid");
        assertThat(status(paid)).isEqualTo("paid");

        partner.markReturned(returned);
        notify(returned, "returned");
        notify(returned, "paid"); // stale, out of order
        assertThat(status(returned)).isEqualTo("returned");

        long paidFee = 100 + 100;
        assertThat(ghs()).isEqualTo(100_000 - 10_000 - paidFee);
        assertThat(usdInFlight() - inFlight0).isZero();
        assertThat(partnerUsd() - partner0).isEqualTo(640); // only the paid transfer: 10,000 × 0.064
        assertThat(db.sql("SELECT count(*) FROM ops_item WHERE kind = 'fx_status_conflict' AND subject = :s")
                .param("s", "fx:" + returned).query(Long.class).single()).isEqualTo(1);

        // Inbound remittance: US$100.00 at 15.20 credits GH₵1,520.00 to the wallet.
        long before = ghs();
        Map<String, Object> instruction = Map.of("partnerReference", "MTCN-" + UUID.randomUUID(), "corridor", IN,
                "sourceAmount", 10_000, "sourceCurrency", "USD", "rate", "15.20", "destinationAmount", 152_000,
                "destinationCurrency", "GHS", "recipientPhone", "0" + phone.substring(3), "senderName", "Ama in Houston");
        var signed = partner.signed(instruction);
        int code1 = postInbound(signed);
        int code2 = postInbound(signed);
        assertThat(code1).isEqualTo(200);
        assertThat(code2).isEqualTo(200);
        assertThat(ghs() - before).isEqualTo(152_000);
        var bad = new HashMap<>(instruction);
        bad.put("partnerReference", "MTCN-" + UUID.randomUUID());
        bad.put("destinationAmount", 152_001);
        assertThat(postInbound(partner.signed(bad))).isEqualTo(422);

        // Reconcile against the partner statement in both currencies.
        String period = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        JsonNode recon = api.call("POST", "/v1/ops/fx/reconcile/sim-remit", ops, null, Map.of("period", period,
                "lines", List.of(
                        Map.of("reference", paid, "sourceCurrency", "GHS", "sourceAmount", 10_000,
                                "destinationCurrency", "USD", "destinationAmount", 640, "status", "paid"),
                        Map.of("reference", returned, "sourceCurrency", "GHS", "sourceAmount", 30_000,
                                "destinationCurrency", "USD", "destinationAmount", 1_920, "status", "returned"),
                        Map.of("reference", instruction.get("partnerReference"), "sourceCurrency", "USD", "sourceAmount", 10_000,
                                "destinationCurrency", "GHS", "destinationAmount", 152_000, "status", "paid"),
                        Map.of("reference", "UNKNOWN-1", "sourceCurrency", "USD", "sourceAmount", 1,
                                "destinationCurrency", "GHS", "destinationAmount", 15, "status", "paid")))).body();
        Map<String, String> results = new HashMap<>();
        recon.forEach(l -> results.put(l.get("reference").asString(), l.get("result").asString()));
        assertThat(results.get(paid)).isEqualTo("matched");
        assertThat(results.get(returned)).isEqualTo("matched");
        assertThat(results.get((String) instruction.get("partnerReference"))).isEqualTo("matched");
        assertThat(results.get("UNKNOWN-1")).isEqualTo("missing_internal");
        assertThat(ledger.integrity().ok()).isTrue();
    }

    int postInbound(Map.Entry<String, String> signed) {
        return api.http.post().uri("/v1/fx/partners/sim-remit/inbound").header("x-partner-signature", signed.getKey())
                .header("Content-Type", "application/json").body(signed.getValue())
                .exchange((req, res) -> res.getStatusCode().value());
    }

    String status(String reference) {
        return api.call("GET", "/v1/fx/transfers/" + reference, token, null, null).body().get("status").asString();
    }
}
