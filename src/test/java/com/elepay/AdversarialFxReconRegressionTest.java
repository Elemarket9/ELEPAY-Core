package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.ledger.Ledger;
import com.elepay.rails.PaymentRail;
import com.elepay.rails.RailTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

@DisplayName("Adversarial Regression Tests: FX Dual Recon, FX Pricing, Non-GHS Rail Fees & Reconciliation Suspense")
class AdversarialFxReconRegressionTest extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired JdbcClient db;
    @Autowired Ledger ledger;
    @Autowired RailTransactions railTransactions;

    TestApi api;
    String opsToken;
    String opsAdminToken;
    String customerToken;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        opsToken = api.token("ops-fxrecon-" + s, "ops");
        opsAdminToken = api.token("admin-fxrecon-" + s, "ops_admin");
        customerToken = api.token("cust-fxrecon-" + s);
    }

    @Test
    @DisplayName("CRIT-04: FX dual reconciliation rejects Long.MIN_VALUE statedClosingBalance with 400 and persists nothing")
    void fxDualReconRejectsLongMinValueStatedBalance() {
        String partner = "adv-minval-" + UUID.randomUUID().toString().substring(0, 8);
        String period = LocalDate.now(ZoneOffset.UTC).toString();

        var res = api.call("POST", "/v1/ops/fx/reconcile-dual/" + partner, opsToken, null, Map.of(
                "period", period,
                "sourceCurrency", "GHS",
                "destinationCurrency", "USD",
                "statedClosingBalance", Long.MIN_VALUE,
                "lines", List.of()
        ));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body().get("code").asString()).isEqualTo("invalid_statement_line");

        long count = db.sql("SELECT COUNT(*) FROM fx_dual_currency_statement WHERE partner = :p")
                .param("p", partner).query(Long.class).single();
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("CRIT-04: FX dual reconciliation catches clearing balanceDifference subtraction overflow with 400")
    void fxDualReconRejectsBalanceDifferenceOverflow() {
        String partner = "adv-ovf-" + UUID.randomUUID().toString().substring(0, 8);
        String period = LocalDate.now(ZoneOffset.UTC).toString();
        String clearingCode = ledger.openSystem("system:clearing:fx-" + partner + ":USD", "clearing", "USD", true);
        ledger.post(Ledger.Posting.of("adv-test-seed", partner, "adjustment", List.of(
                Ledger.Leg.debit(clearingCode, 100L),
                Ledger.Leg.credit("system:fx_clearing:USD", 100L)
        )));

        // Long.MAX_VALUE - (-100) overflows signed 64-bit long
        var res = api.call("POST", "/v1/ops/fx/reconcile-dual/" + partner, opsToken, null, Map.of(
                "period", period,
                "sourceCurrency", "GHS",
                "destinationCurrency", "USD",
                "statedClosingBalance", Long.MAX_VALUE,
                "lines", List.of()
        ));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body().get("code").asString()).isEqualTo("statement_amount_overflow");

        long count = db.sql("SELECT COUNT(*) FROM fx_dual_currency_statement WHERE partner = :p")
                .param("p", partner).query(Long.class).single();
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("HIGH: FX pricing applies marginBps once in spread without double-charging variableFee and guards overflow")
    void fxPricingAppliesMarginOnceAndGuardsOverflow() {
        String corridor = "ADV-GHS-USD-" + UUID.randomUUID().toString().substring(0, 8);
        var upsert = api.call("PUT", "/v1/ops/fx/corridors/" + corridor, opsToken, null, Map.ofEntries(
                Map.entry("direction", "outbound"),
                Map.entry("sendingCountry", "GH"),
                Map.entry("receivingCountry", "US"),
                Map.entry("sourceCurrency", "GHS"),
                Map.entry("destinationCurrency", "USD"),
                Map.entry("fundingMethod", "wallet"),
                Map.entry("deliveryMethod", "bank"),
                Map.entry("partner", "sim-remit"),
                Map.entry("feeFixed", 100),
                Map.entry("feeBps", 200),
                Map.entry("minAmount", 100),
                Map.entry("maxAmount", 10_000_000),
                Map.entry("quoteTtlSeconds", 600),
                Map.entry("estDeliveryMinutes", 1440),
                Map.entry("active", true)
        ));
        assertThat(upsert.status()).isEqualTo(200);

        // Normal pricing: 200 bps applied once in effectiveRate (0.0980), variableFee == 0, totalFee == fixedFee (100)
        var normal = api.call("POST", "/v1/fx/pricing", customerToken, null, Map.of(
                "corridor", corridor,
                "customerTier", "standard",
                "sourceAmount", 10_000,
                "baseRate", new BigDecimal("0.10")
        ));
        assertThat(normal.status()).isEqualTo(200);
        JsonNode p = normal.body();
        assertThat(p.get("marginBps").asInt()).isEqualTo(200);
        assertThat(p.get("effectiveRate").decimalValue()).isEqualByComparingTo("0.0980");
        assertThat(p.get("fixedFee").asLong()).isEqualTo(100L);
        assertThat(p.get("variableFee").asLong()).isZero();
        assertThat(p.get("totalFee").asLong()).isEqualTo(100L);
        assertThat(p.get("destinationAmount").asLong()).isEqualTo(980L);

        // Previously threw 500 on Math.multiplyExact(sourceAmount, marginBps)
        var largeSource = api.call("POST", "/v1/fx/pricing", customerToken, null, Map.of(
                "corridor", corridor,
                "customerTier", "standard",
                "sourceAmount", 92233720368547758L,
                "baseRate", new BigDecimal("0.10")
        ));
        assertThat(largeSource.status()).isEqualTo(200);
        assertThat(largeSource.body().get("destinationAmount").asLong()).isPositive();

        // Previously threw 500 on longValueExact() overflow; must return 400 amount_overflow
        var overflow = api.call("POST", "/v1/fx/pricing", customerToken, null, Map.of(
                "corridor", corridor,
                "customerTier", "standard",
                "sourceAmount", 1_000_000_000_000_000L,
                "baseRate", new BigDecimal("1000000000")
        ));
        assertThat(overflow.status()).isEqualTo(400);
        assertThat(overflow.body().get("code").asString()).isEqualTo("amount_overflow");
    }

    @Test
    @DisplayName("HIGH: Non-GHS (USD) rail settlement with positive fee opens system:rail_fees:USD with allowNegative=true")
    void nonGhsRailSettlementWithPositiveFeeSucceeds() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        UUID txId = UUID.randomUUID();
        String ref = "USD-FEE-TEST-" + s;

        db.sql("""
                INSERT INTO rail_transaction (id, rail, direction, reference, purpose, purpose_id, amount, currency, status)
                VALUES (:id, 'sandbox', 'charge', :ref, 'settlement', :pid, 10000, 'USD', 'pending')""")
                .param("id", txId).param("ref", ref).param("pid", UUID.randomUUID()).update();

        long feeBefore = ledger.balance("system:rail_fees:USD");

        railTransactions.apply(txId,
                new PaymentRail.Result(PaymentRail.Status.SUCCEEDED, "USD-RAIL-" + s, 10_000L, "USD", 250L, null, "ok"),
                "callback");

        String status = db.sql("SELECT status FROM rail_transaction WHERE id = :id")
                .param("id", txId).query(String.class).single();
        long recordedFee = db.sql("SELECT rail_fee FROM rail_transaction WHERE id = :id")
                .param("id", txId).query(Long.class).single();
        boolean allowNegative = db.sql("SELECT allow_negative FROM ledger_account WHERE code = 'system:rail_fees:USD'")
                .query(Boolean.class).single();

        assertThat(status).isEqualTo("succeeded");
        assertThat(recordedFee).isEqualTo(250L);
        assertThat(allowNegative).isTrue();
        assertThat(ledger.balance("system:rail_fees:USD")).isEqualTo(feeBefore - 250L);
    }

    @Test
    @DisplayName("HIGH: Reconciliation rejects unsupported currency and negative/zero statement lines with 400")
    void reconciliationRejectsUnsupportedCurrencyAndNegativeLines() {
        String s = UUID.randomUUID().toString().substring(0, 8);

        var bogusCurrency = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, Map.of(
                "rail", "sandbox",
                "filename", "bogus-cur-" + s + ".csv",
                "lines", List.of(Map.of(
                        "externalReference", "BOGUS-CUR-" + s,
                        "amountPesewas", 1000,
                        "currency", "XYZ",
                        "feePesewas", 0
                ))
        ));
        assertThat(bogusCurrency.status()).isEqualTo(400);
        assertThat(bogusCurrency.body().get("code").asString()).isEqualTo("unsupported_currency");

        var negativeAmount = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, Map.of(
                "rail", "sandbox",
                "filename", "neg-amt-" + s + ".csv",
                "lines", List.of(Map.of(
                        "externalReference", "NEG-AMT-" + s,
                        "amountPesewas", -5000,
                        "currency", "GHS",
                        "feePesewas", 0
                ))
        ));
        assertThat(negativeAmount.status()).isEqualTo(400);
        assertThat(negativeAmount.body().get("code").asString()).isEqualTo("invalid_statement_line");

        var zeroAmount = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, Map.of(
                "rail", "sandbox",
                "filename", "zero-amt-" + s + ".csv",
                "lines", List.of(Map.of(
                        "externalReference", "ZERO-AMT-" + s,
                        "amountPesewas", 0,
                        "currency", "GHS",
                        "feePesewas", 0
                ))
        ));
        assertThat(zeroAmount.status()).isEqualTo(400);
        assertThat(zeroAmount.body().get("code").asString()).isEqualTo("invalid_statement_line");

        var negativeFee = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, Map.of(
                "rail", "sandbox",
                "filename", "neg-fee-" + s + ".csv",
                "lines", List.of(Map.of(
                        "externalReference", "NEG-FEE-" + s,
                        "amountPesewas", 1000,
                        "currency", "GHS",
                        "feePesewas", -10
                ))
        ));
        assertThat(negativeFee.status()).isEqualTo(400);
        assertThat(negativeFee.body().get("code").asString()).isEqualTo("invalid_statement_line");
    }

    @Test
    @DisplayName("HIGH: Reconciliation unifies suspense backing and clearing on system:suspense:<CUR>")
    void reconciliationUnifiesSuspenseAccountNaming() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String extRef = "UNIFIED-SUSP-" + s;
        long suspenseBefore = ledger.balance(Ledger.SUSPENSE);

        var upload = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, Map.of(
                "rail", "sandbox",
                "filename", "unified-susp-" + s + ".csv",
                "lines", List.of(Map.of(
                        "externalReference", extRef,
                        "amountPesewas", 2500,
                        "currency", "GHS",
                        "feePesewas", 0
                ))
        ));
        assertThat(upload.status()).isEqualTo(200);
        assertThat(upload.body().get("unmatched").asInt()).isEqualTo(1);
        assertThat(ledger.balance(Ledger.SUSPENSE)).isEqualTo(suspenseBefore + 2500L);

        UUID suspenseId = db.sql("SELECT id FROM suspense_item WHERE reason LIKE :r ORDER BY created_at DESC LIMIT 1")
                .param("r", "%" + extRef + "%").query(UUID.class).single();

        var propose = api.call("POST", "/v1/ops/suspense/propose", opsToken, null, Map.of(
                "id", suspenseId.toString(),
                "action", "CLEAR_TO_CLEARING",
                "targetAccount", Ledger.clearing("sandbox")
        ));
        assertThat(propose.status()).isEqualTo(200);

        var approve = api.call("POST", "/v1/ops/suspense/" + suspenseId + "/approve", opsAdminToken, null, null);
        assertThat(approve.status()).isEqualTo(200);
        assertThat(approve.body().get("status").asString()).isEqualTo("CLEARED");
        assertThat(ledger.balance(Ledger.SUSPENSE)).isEqualTo(suspenseBefore);

        String debitAccount = db.sql("""
                SELECT la.code FROM journal_entry je
                JOIN ledger_account la ON je.account_id = la.id
                WHERE je.transaction_id = (SELECT id FROM journal_transaction WHERE idempotency_key = :key)
                  AND je.amount < 0""")
                .param("key", suspenseId.toString()).query(String.class).single();
        assertThat(debitAccount).isEqualTo("system:suspense:GHS");
    }
}
