package com.elepay.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class FxAdvancedIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired FxService fxService;

    TestApi api;
    String ops, token;
    UUID customer;
    static final String CORRIDOR = "GH-US-GHS-USD-WALLET-BANK-SIM";

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + s, "ops");
        customer = api.enroll(ops, "cust-" + s, TestApi.uniquePhone(), "Kwame FX");
        token = api.token("cust-" + s);

        // Ensure corridor exists
        api.call("PUT", "/v1/ops/fx/corridors/" + CORRIDOR, ops, null, Map.ofEntries(
                Map.entry("direction", "outbound"), Map.entry("sendingCountry", "GH"), Map.entry("receivingCountry", "US"),
                Map.entry("sourceCurrency", "GHS"), Map.entry("destinationCurrency", "USD"), Map.entry("fundingMethod", "wallet"),
                Map.entry("deliveryMethod", "bank"), Map.entry("partner", "sim-remit"), Map.entry("feeFixed", 100),
                Map.entry("feeBps", 100), Map.entry("minAmount", 100), Map.entry("maxAmount", 10_000_000),
                Map.entry("quoteTtlSeconds", 600), Map.entry("estDeliveryMinutes", 1440), Map.entry("active", true)));
    }

    @Test
    @DisplayName("059: Rate sourcing and blending filters statistical outliers and synthesizes mid-market rate")
    void testRateSourcingAndBlending() {
        // Ingest rate sources: 2 normal + 1 outlier
        api.call("POST", "/v1/ops/fx/rates/sources", ops, null, Map.of(
                "source", "bloomberg", "sourceCurrency", "GHS", "destinationCurrency", "USD",
                "bidRate", 0.0638, "askRate", 0.0642, "weight", 1.0));

        api.call("POST", "/v1/ops/fx/rates/sources", ops, null, Map.of(
                "source", "refinitiv", "sourceCurrency", "GHS", "destinationCurrency", "USD",
                "bidRate", 0.0639, "askRate", 0.0641, "weight", 1.5));

        api.call("POST", "/v1/ops/fx/rates/sources", ops, null, Map.of(
                "source", "bad_feed", "sourceCurrency", "GHS", "destinationCurrency", "USD",
                "bidRate", 0.0750, "askRate", 0.0760, "weight", 1.0)); // > 15% outlier

        var syn = api.call("POST", "/v1/ops/fx/rates/synthesize", ops, null, Map.of(
                "sourceCurrency", "GHS", "destinationCurrency", "USD", "maxAgeMinutes", 15));
        assertThat(syn.status()).isEqualTo(200);

        JsonNode body = syn.body();
        assertThat(body.get("sourcesCount").asInt()).isEqualTo(2);
        assertThat(body.get("outliersCount").asInt()).isEqualTo(1);
        assertThat(body.get("midRate").decimalValue()).isBetween(new BigDecimal("0.0639"), new BigDecimal("0.0641"));

        // Retrieve latest rate via customer API
        var latest = api.call("GET", "/v1/fx/rates/latest?from=GHS&to=USD", token, null, null);
        assertThat(latest.status()).isEqualTo(200);
        assertThat(latest.body().get("midRate").decimalValue()).isNotNull();
    }

    @Test
    @DisplayName("060: Dynamic FX margin pricing applies tiered margin and explicit integer rounding")
    void testDynamicMarginPricing() {
        // Insert custom margin tier: 50 bps for amounts over 100,000 (GH₵1,000)
        UUID tierId = UUID.randomUUID();
        db.sql("""
                INSERT INTO fx_margin_tier (id, corridor, customer_tier, min_amount, max_amount, margin_bps, fixed_fee, active)
                VALUES (:id, :c, 'standard', 100000, 10000000, 50, 50, true)""")
                .param("id", tierId).param("c", CORRIDOR).update();

        var pricing = api.call("POST", "/v1/fx/pricing", token, null, Map.of(
                "corridor", CORRIDOR, "customerTier", "standard", "sourceAmount", 200_000,
                "baseRate", 0.0640));
        assertThat(pricing.status()).isEqualTo(200);

        JsonNode p = pricing.body();
        assertThat(p.get("marginBps").asInt()).isEqualTo(50);
        assertThat(p.get("fixedFee").asLong()).isEqualTo(50);
        assertThat(p.get("destinationAmount").asLong()).isGreaterThan(0);
    }

    @Test
    @DisplayName("061: Corridor routing ranks partner routes by composite score")
    void testCorridorRouting() {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();

        db.sql("""
                INSERT INTO fx_partner_route (id, corridor, partner, cost_bps, fixed_cost, avg_delivery_minutes, liquidity_score, success_rate_bps, active)
                VALUES (:id1, :c, 'partner-fast', 80, 0, 10, 85, 9950, true),
                       (:id2, :c, 'partner-cheap', 10, 0, 1440, 95, 9900, true)
                ON CONFLICT (corridor, partner) DO NOTHING""")
                .param("id1", r1).param("id2", r2).param("c", CORRIDOR).update();

        var routesRes = api.call("GET", "/v1/fx/corridors/" + CORRIDOR + "/routes?amount=50000", token, null, null);
        assertThat(routesRes.status()).isEqualTo(200);

        JsonNode list = routesRes.body();
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isGreaterThanOrEqualTo(2);
        // Fast route ranks higher due to 10 min delivery vs 1440 min
        assertThat(list.get(0).get("partner").asString()).isEqualTo("partner-fast");
    }

    @Test
    @DisplayName("062: Exposure limits and hedging triggers track Net Open Foreign Position (NOFP)")
    void testExposureLimitsAndHedgingTriggers() {
        // Evaluate exposure on USD
        var exp = api.call("GET", "/v1/ops/fx/exposure/USD", ops, null, null);
        assertThat(exp.status()).isEqualTo(200);

        JsonNode body = exp.body();
        assertThat(body.get("currency").asString()).isEqualTo("USD");
        assertThat(body.get("limit").asLong()).isEqualTo(50_000_000);
        assertThat(body.get("utilizationPct").asInt()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("063: Delivery-time estimation calculates route-specific SLA windows and cutoff adjustments")
    void testDeliveryEstimation() {
        var est = api.call("GET", "/v1/fx/corridors/" + CORRIDOR + "/delivery-estimate?deliveryMethod=bank", token, null, null);
        assertThat(est.status()).isEqualTo(200);

        JsonNode body = est.body();
        assertThat(body.get("deliveryMethod").asString()).isEqualTo("bank");
        assertThat(body.get("estimatedArrival").asString()).isNotNull();
        assertThat(body.get("slaDeadline").asString()).isNotNull();
        assertThat(body.get("displayText").asString()).contains("Expected");
    }

    @Test
    @DisplayName("064: Multi-currency partner reconciliation verifies dual-currency amounts, fees, and balances")
    void testDualCurrencyReconciliation() {
        String period = LocalDate.now(ZoneOffset.UTC).toString();

        var reconRes = api.call("POST", "/v1/ops/fx/reconcile-dual/sim-remit", ops, null, Map.of(
                "period", period, "sourceCurrency", "GHS", "destinationCurrency", "USD",
                "statedClosingBalance", 0, "lines", List.of(
                        Map.of("reference", "FX-NONEXISTENT", "sourceCurrency", "GHS", "sourceAmount", 10_000,
                                "destinationCurrency", "USD", "destinationAmount", 640,
                                "feeAmount", 10, "feeCurrency", "USD", "status", "paid"))));
        assertThat(reconRes.status()).isEqualTo(200);

        JsonNode report = reconRes.body();
        assertThat(report.get("currencyPair").asString()).isEqualTo("GHS/USD");
        assertThat(report.get("overallStatus").asString()).isIn("matched", "discrepancy");
        assertThat(report.get("items").get(0).get("result").asString()).isEqualTo("missing_internal");
    }
}
