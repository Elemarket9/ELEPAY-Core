package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end PostgreSQL integration tests for the Dual-Controlled ELEPAY Platform Revenue Sweep.
 */
@TestPropertySource(properties = "elepay.security.dual-control.enforced=true")
class PlatformRevenueSweepIT extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired RailTransactions rails;
    @Autowired CustodianBankBackstopService custodianBackstop;
    @Autowired PlatformRevenueSweepService sweepService;
    @Autowired KillSwitchService killSwitch;

    private TestApi api;
    private String makerToken;
    private String checkerToken;
    private long originalBuffer;

    @BeforeEach
    void setUp() {
        db.sql("UPDATE rail_transaction SET status = 'failed' WHERE status IN ('created', 'pending', 'unknown')").update();
        custodianBackstop.resetForTesting();
        killSwitch.resetForTesting();
        originalBuffer = sweepService.getClawbackBufferPesewas();
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        makerToken = api.token("rev-maker-" + s, "ops", "ops_admin", "finance");
        checkerToken = api.token("rev-checker-" + s, "ops", "ops_admin", "finance");
    }

    @AfterEach
    void tearDown() {
        sweepService.setClawbackBufferPesewas(originalBuffer);
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

    private UUID registerOperatingAccount() {
        String acctNum = "102030" + (10000 + new Random().nextInt(89999)) + "5";
        var res = api.call("POST", "/v1/ops/revenue-sweeps/operating-accounts", makerToken, null, Map.of(
                "bankCode", "GCB",
                "accountNumber", acctNum,
                "accountName", "ELEPAY Operations Ltd",
                "currency", "GHS"));
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return UUID.fromString(res.body().get("id").asString());
    }

    @Test
    @DisplayName("Dual-control revenue sweep enforces safeguarding, clawback buffer, maker-checker, and isolates merchant/deferred funds")
    void dualControlRevenueSweepEnforcesSafeguardingClawbackBufferAndIsolatesFunds() {
        UUID operatingAccountId = registerOperatingAccount();

        // Seed merchant balance (50,000), deferred revenue (5,000), and earned revenue across two stripes (6,000 + 4,000 = 10,000)
        UUID merchantId = UUID.randomUUID();
        String mAvail = ledger.open("merchant", merchantId, "available", Ledger.GHS);
        String deferredStripe = ledger.deferredRevenueStripe(Ledger.GHS, UUID.randomUUID());
        String revStripe1 = ledger.revenueStripe(Ledger.GHS, "order-1-" + UUID.randomUUID());
        String revStripe2 = ledger.revenueStripe(Ledger.GHS, "order-2-" + UUID.randomUUID());
        ledger.post(Ledger.Posting.of("rev-sweep-seed", UUID.randomUUID().toString(), "seed", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 65_000L),
                Ledger.Leg.credit(mAvail, 50_000L),
                Ledger.Leg.credit(deferredStripe, 5_000L),
                Ledger.Leg.credit(revStripe1, 6_000L),
                Ledger.Leg.credit(revStripe2, 4_000L)
        )));

        long totalRevenue = ledger.balance(Ledger.REVENUE);
        long deferredBefore = ledger.balance(Ledger.DEFERRED_REVENUE);
        long buffer = totalRevenue - 8_000L; // Leave exactly 8,000 sweepable by revenue
        sweepService.setClawbackBufferPesewas(buffer);

        // 1. Fail-closed while Custodian bank balance is unreconciled
        var previewUnreconciled = api.call("GET", "/v1/ops/revenue-sweeps/preview?currency=GHS", makerToken, null, null);
        assertThat(previewUnreconciled.status()).isEqualTo(200);
        assertThat(previewUnreconciled.body().get("safeguardingSatisfied").asBoolean()).isFalse();
        assertThat(previewUnreconciled.body().get("maxSweepablePesewas").asLong()).isZero();

        assertThatThrownBy(() -> sweepService.executeSweep(
                operatingAccountId, 1_000L, "GHS", "unrec-" + UUID.randomUUID(), "maker", "checker", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reconciled");

        // 2. Reconcile Custodian trust balance with exact headroom of 10,000 above all safeguarded liabilities
        long liabilities = custodianBackstop.verifyTrustSafeguarding("GHS").totalSafeguardedLiabilitiesPesewas();
        custodianBackstop.updateConfig(null, null, 0L, liabilities + 10_000L, false, "ops");

        var preview = api.call("GET", "/v1/ops/revenue-sweeps/preview?currency=GHS", makerToken, null, null);
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().get("safeguardingSatisfied").asBoolean()).isTrue();
        assertThat(preview.body().get("maxSweepablePesewas").asLong()).isEqualTo(8_000L);

        // 3. Attempting to breach the clawback buffer (8,001 > 8,000) fails closed
        assertThatThrownBy(() -> sweepService.executeSweep(
                operatingAccountId, 8_001L, "GHS", "buf-" + UUID.randomUUID(), "maker", "checker", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("clawback buffer");

        // 4. Dual-control Maker proposes 6,000 sweep -> 202 pending_approval (nothing moved yet)
        String idemKey = "sweep-dc-" + UUID.randomUUID();
        var proposed = api.call("POST", "/v1/ops/revenue-sweeps", makerToken, idemKey, Map.of(
                "operatingAccountId", operatingAccountId.toString(),
                "amountPesewas", 6_000L,
                "currency", "GHS",
                "reason", "Weekly earned revenue sweep"));
        assertThat(proposed.status()).isEqualTo(202);
        assertThat(proposed.body().get("code").asString()).isEqualTo("pending_approval");
        String detail = proposed.body().get("detail").asString();
        UUID proposalId = UUID.fromString(detail.substring(detail.lastIndexOf(' ') + 1));

        assertThat(ledger.balance(Ledger.REVENUE)).isEqualTo(totalRevenue);

        // 5. Maker self-approval is rejected (403)
        var selfApprove = api.call("POST", "/v1/ops/maker-checker/proposals/" + proposalId + "/approve", makerToken, null,
                Map.of("note", "self-approve attempt"));
        assertThat(selfApprove.status()).isEqualTo(403);

        // 6. Checker approves -> executes sweep through Custodian Backstop & rail
        var approved = api.call("POST", "/v1/ops/maker-checker/proposals/" + proposalId + "/approve", checkerToken, null,
                Map.of("note", "verified against daily reconciliation"));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);

        api.call("POST", "/v1/ops/rails/recover", checkerToken, null, null);

        // Earned revenue reduced by 6,000; reserved == 0; deferred & merchant 100% untouched
        assertThat(ledger.balance(Ledger.REVENUE)).isEqualTo(totalRevenue - 6_000L);
        assertThat(ledger.balance(Ledger.REVENUE_SWEEP_RESERVED)).isZero();
        assertThat(ledger.balance(Ledger.DEFERRED_REVENUE)).isEqualTo(deferredBefore);
        assertThat(ledger.balance(mAvail)).isEqualTo(50_000L);
        assertThat(custodianOutflowToday()).containsExactly(0L, 6_000L);

        var sweeps = sweepService.listSweeps(10);
        assertThat(sweeps).isNotEmpty();
        assertThat(sweeps.get(0).status()).isEqualTo("succeeded");
        assertThat(sweeps.get(0).proposalId()).isEqualTo(proposalId);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("Rail return restores earned revenue & custodian outflow; concurrent sweeps serialize without breaching clawback buffer")
    void revenueSweepReturnAndConcurrentSerialization() throws Exception {
        UUID operatingAccountId = registerOperatingAccount();

        String revStripe = ledger.revenueStripe(Ledger.GHS, "conc-rev-" + UUID.randomUUID());
        ledger.post(Ledger.Posting.of("rev-conc-seed", UUID.randomUUID().toString(), "seed", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 20_000L),
                Ledger.Leg.credit(revStripe, 20_000L)
        )));

        long totalRevenue = ledger.balance(Ledger.REVENUE);
        long buffer = totalRevenue - 8_000L; // Exactly 8,000 sweepable -> only ONE 5,000 sweep can fit
        sweepService.setClawbackBufferPesewas(buffer);

        long liabilities = custodianBackstop.verifyTrustSafeguarding("GHS").totalSafeguardedLiabilitiesPesewas();
        custodianBackstop.updateConfig(null, null, 0L, liabilities + 50_000L, false, "ops");

        // 1. 6 concurrent sweeps of 5,000 -> exactly 1 succeeds, 5 fail with revenue_clawback_buffer_breached
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger bufferRejected = new AtomicInteger();
        List<PlatformRevenueSweepService.RevenueSweep> createdSweeps = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        var sw = sweepService.executeSweep(
                                operatingAccountId, 5_000L, "GHS",
                                "conc-sw-" + UUID.randomUUID(), "maker", "checker", null);
                        createdSweeps.add(sw);
                        succeeded.incrementAndGet();
                    } catch (ApiException e) {
                        if ("revenue_clawback_buffer_breached".equals(e.code())) {
                            bufferRejected.incrementAndGet();
                        } else {
                            unexpected.add(e);
                        }
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).isEmpty();
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(bufferRejected.get()).isEqualTo(threads - 1);

        api.call("POST", "/v1/ops/rails/recover", checkerToken, null, null);
        assertThat(ledger.balance(Ledger.REVENUE)).isEqualTo(totalRevenue - 5_000L);
        assertThat(ledger.balance(Ledger.REVENUE_SWEEP_RESERVED)).isZero();
        assertThat(custodianOutflowToday()).containsExactly(0L, 5_000L);

        // 2. Receiving bank returns the 5,000 sweep -> earned revenue restored & custodian settled outflow drops to 0
        var sweep = sweepService.findSweep(createdSweeps.get(0).id()).orElseThrow();
        assertThat(sweep.status()).isEqualTo("succeeded");
        rails.apply(sweep.railTx(), new PaymentRail.Result(
                PaymentRail.Status.RETURNED, "BANK-RET-1", 5_000L, "GHS", 0L, null, "Operating bank closed"), "callback");

        var returned = sweepService.findSweep(sweep.id()).orElseThrow();
        assertThat(returned.status()).isEqualTo("returned");
        assertThat(ledger.balance(Ledger.REVENUE)).isEqualTo(totalRevenue);
        assertThat(custodianOutflowToday()).containsExactly(0L, 0L);
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
