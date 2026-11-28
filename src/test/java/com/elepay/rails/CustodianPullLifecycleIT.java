package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end integration tests verifying that every customer rail refund and customer wallet
 * withdrawal pulls from the Custodian Bank Trust Account under strict cap/reserve-floor controls,
 * auto-releases on custodian denial, handles rail returns without orphaning funds, and supports
 * safe idempotent retries.
 */
class CustodianPullLifecycleIT extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired RailTransactions rails;
    @Autowired CustodianBankBackstopService custodianBackstop;
    @Autowired KillSwitchService killSwitch;

    private TestApi api;
    private String ops;
    private String merchantOwner;
    private UUID merchantId;

    @BeforeEach
    void setUp() {
        custodianBackstop.resetForTesting();
        killSwitch.resetForTesting();
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("cust-pull-ops-" + s, "ops");
        merchantOwner = api.token("cust-pull-m-" + s);

        var mResp = api.call("POST", "/v1/merchants", merchantOwner, null, Map.of(
                "legalName", "Custodian Flow Shop " + s,
                "tradingName", "Custodian Flow Shop " + s,
                "kind", "merchant",
                "contactPhone", TestApi.uniquePhone()));
        assertThat(mResp.status()).isEqualTo(200);
        merchantId = UUID.fromString(mResp.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + merchantId + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 100, "feeFixed", 0, "settlementDelayHours", 0)).status())
                .isEqualTo(200);
    }

    @AfterEach
    void tearDown() {
        custodianBackstop.resetForTesting();
        killSwitch.resetForTesting();
    }

    private long[] custodianOutflowToday() {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        return db.sql("""
                SELECT reserved_outflow_pesewas, settled_outflow_pesewas
                FROM custodian_daily_outflow
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("code", CustodianBankBackstopService.PRIMARY_TRUST_CODE)
                .param("d", today)
                .query((rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)})
                .optional()
                .orElse(new long[]{0L, 0L});
    }

    @Test
    @DisplayName("Customer rail refund: Custodian freeze blocks pull -> retry pulls from Custodian -> rail return restores refunds_in_flight & settled outflow -> second retry succeeds once")
    void customerRailRefundBlockedByCustodianThenRetriedAndReturnedAndRetriedCleanly() {
        long refundsInFlightBefore = ledger.balance(Ledger.REFUNDS_IN_FLIGHT);

        // 1. Customer pays a 10,000 pesewa checkout via MoMo
        var created = api.call("POST", "/v1/merchants/" + merchantId + "/checkouts",
                merchantOwner, UUID.randomUUID().toString(),
                Map.of("description", "Refundable Order", "allocations", List.of(
                        Map.of("merchantId", merchantId, "kind", "sale", "amount", 10_000L, "description", "Shoes"))));
        assertThat(created.status()).isEqualTo(200);
        String checkoutRef = created.body().get("reference").asString();

        var pay = api.call("POST", "/v1/public/checkouts/" + checkoutRef + "/pay",
                null, UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0245550005", "network", "mtn"));
        assertThat(pay.status()).isEqualTo(200);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);

        // 2. Freeze Custodian outflow -> customer refund attempt is blocked at Custodian boundary
        custodianBackstop.updateConfig(null, null, null, 100_000L, true, "ops");
        var refundResp = api.call("POST", "/v1/merchants/" + merchantId + "/checkouts/" + checkoutRef + "/allocations/0/refunds",
                merchantOwner, UUID.randomUUID().toString(),
                Map.of("amount", 10_000L, "reason", "Customer requested refund"));
        assertThat(refundResp.status()).isEqualTo(200);
        UUID refundId = UUID.fromString(refundResp.body().get("id").asString());

        // Refund status moved to 'failed', funds safely held in REFUNDS_IN_FLIGHT, custodian outflow = 0
        String statusAfterBlock = db.sql("SELECT status FROM refund WHERE id = :id")
                .param("id", refundId).query(String.class).single();
        assertThat(statusAfterBlock).isEqualTo("failed");
        assertThat(ledger.balance(Ledger.REFUNDS_IN_FLIGHT)).isEqualTo(refundsInFlightBefore + 10_000L);
        assertThat(custodianOutflowToday()).containsExactly(0L, 0L);

        // 3. Unfreeze Custodian -> retry pulls 10,000 from Custodian to customer rail and completes
        custodianBackstop.updateConfig(null, null, null, 100_000L, false, "ops");
        var retry1 = api.call("POST", "/v1/merchants/" + merchantId + "/refunds/" + refundId + "/retry",
                merchantOwner, UUID.randomUUID().toString(), Map.of());
        assertThat(retry1.status()).isEqualTo(200);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);

        assertThat(db.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId).query(String.class).single())
                .isEqualTo("completed");
        assertThat(ledger.balance(Ledger.REFUNDS_IN_FLIGHT)).isEqualTo(refundsInFlightBefore);
        assertThat(custodianOutflowToday()).containsExactly(0L, 10_000L);

        // Verify custodian pull was audited
        UUID firstRetryRailTxId = UUID.fromString(retry1.body().get("railTxId").asString());
        String firstRetryRailRef = rails.find(firstRetryRailTxId).orElseThrow().reference();
        long auditCount = db.sql("""
                SELECT count(*) FROM audit_log
                WHERE action = 'custodian.pull_authorized' AND subject = :subj""")
                .param("subj", "rail_tx:" + firstRetryRailRef).query(Long.class).single();
        assertThat(auditCount).isEqualTo(1L);

        // 4. Receiving rail RETURNS (reverses) the refund -> restores REFUNDS_IN_FLIGHT and decrements settled_outflow
        rails.apply(firstRetryRailTxId, new PaymentRail.Result(
                PaymentRail.Status.RETURNED, "RET-REF-1", 10_000L, "GHS", 0L, null, "Customer wallet full"), "callback");

        assertThat(db.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId).query(String.class).single())
                .isEqualTo("failed");
        assertThat(ledger.balance(Ledger.REFUNDS_IN_FLIGHT)).isEqualTo(refundsInFlightBefore + 10_000L);
        assertThat(custodianOutflowToday()).containsExactly(0L, 0L);

        // 5. Second retry pulls from Custodian again and completes; a third retry on completed refund is rejected (409)
        var retry2 = api.call("POST", "/v1/merchants/" + merchantId + "/refunds/" + refundId + "/retry",
                merchantOwner, UUID.randomUUID().toString(), Map.of());
        assertThat(retry2.status()).isEqualTo(200);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);

        assertThat(db.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId).query(String.class).single())
                .isEqualTo("completed");
        assertThat(ledger.balance(Ledger.REFUNDS_IN_FLIGHT)).isEqualTo(refundsInFlightBefore);
        assertThat(custodianOutflowToday()).containsExactly(0L, 10_000L);

        var retryOnCompleted = api.call("POST", "/v1/merchants/" + merchantId + "/refunds/" + refundId + "/retry",
                merchantOwner, UUID.randomUUID().toString(), Map.of());
        assertThat(retryOnCompleted.status()).isEqualTo(409);
        assertThat(retryOnCompleted.body().get("code").asString()).isEqualTo("refund_not_retryable");
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("Customer wallet withdrawal: blocked when exceeding Custodian reserve floor (auto-releases balance), succeeds within trust liquidity")
    void customerWalletWithdrawalPullsFromCustodianAndAutoReleasesWhenCustodianBlocks() {
        String subject = "cust-pull-" + UUID.randomUUID();
        UUID customerId = api.enroll(ops, subject, TestApi.uniquePhone(), "Ama Mensah");
        String customerToken = api.token(subject);

        ledger.post(Ledger.Posting.of("cust-pull-fund", subject, "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 15_000L),
                Ledger.Leg.credit(Ledger.code("customer", customerId, "available", Ledger.GHS), 15_000L))));

        // Custodian trust has 12,000 reconciled with 5,000 reserve floor -> max 7,000 pullable liquidity
        custodianBackstop.updateConfig(50_000L, 50_000L, 5_000L, 12_000L, false, "ops");

        // 1. Customer attempts 8,000 MoMo withdrawal (> 7,000 pullable) -> blocked by Custodian, auto-released back to wallet
        JsonNode lookup1 = api.call("POST", "/v1/wallet/recipients/lookup", customerToken, null,
                Map.of("rail", "momo", "phone", "0245550005", "network", "mtn")).body();
        var blockedWithdrawal = api.call("POST", "/v1/wallet/transfers/confirmed", customerToken,
                UUID.randomUUID().toString(),
                Map.of("confirmationId", lookup1.get("confirmationId").asString(), "amount", 8_000L));
        assertThat(blockedWithdrawal.status()).isEqualTo(200);
        assertThat(blockedWithdrawal.body().get("status").asString()).isEqualTo("failed");
        assertThat(ledger.balance(Ledger.code("customer", customerId, "available", Ledger.GHS))).isEqualTo(15_000L);
        assertThat(ledger.balance(Ledger.code("customer", customerId, "reserved", Ledger.GHS))).isZero();
        assertThat(custodianOutflowToday()).containsExactly(0L, 0L);

        // 2. Customer withdraws 6,000 (<= 7,000 pullable) -> Custodian authorizes pull and settles to MoMo
        JsonNode lookup2 = api.call("POST", "/v1/wallet/recipients/lookup", customerToken, null,
                Map.of("rail", "momo", "phone", "0245550005", "network", "mtn")).body();
        var okWithdrawal = api.call("POST", "/v1/wallet/transfers/confirmed", customerToken,
                UUID.randomUUID().toString(),
                Map.of("confirmationId", lookup2.get("confirmationId").asString(), "amount", 6_000L));
        assertThat(okWithdrawal.status()).isEqualTo(200);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);

        assertThat(ledger.balance(Ledger.code("customer", customerId, "available", Ledger.GHS))).isEqualTo(9_000L);
        assertThat(ledger.balance(Ledger.code("customer", customerId, "reserved", Ledger.GHS))).isZero();
        assertThat(custodianOutflowToday()).containsExactly(0L, 6_000L);
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
