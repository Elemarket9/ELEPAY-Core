package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.elepay.PostgresTestBase;
import com.elepay.common.OpsQueue;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@DisplayName("RailTransactions & CustodianBankBackstopService Remediation Integration Tests")
class RailTransactionsRemediationIT extends PostgresTestBase {

    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @Autowired OpsQueue ops;
    @Autowired Ledger ledger;
    @Autowired PlatformTransactionManager tm;
    @Autowired CustodianBankBackstopService custodianBackstop;
    @Autowired KillSwitchService killSwitch;
    @Autowired CardTokenVault cardTokenVault;
    @Autowired com.elepay.payout.Payouts payouts;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(tm);
        db.sql("UPDATE rail_transaction SET status = 'failed' WHERE status IN ('created', 'pending', 'unknown')").update();
        custodianBackstop.resetForTesting();
        killSwitch.setGlobalFrozen(false, "test-reset", "test");
    }

    @AfterEach
    void tearDown() {
        custodianBackstop.resetForTesting();
        killSwitch.setGlobalFrozen(false, "test-reset", "test");
    }

    private RailTransactions railTxWith(PaymentRail mockRail, List<Object> publishedEvents) {
        String railName = mockRail.name();
        RailRegistry registry = mock(RailRegistry.class);
        when(registry.names()).thenReturn(Set.of(railName));
        when(registry.defaultName()).thenReturn(railName);
        when(registry.get(railName)).thenReturn(mockRail);
        when(registry.select(anyString(), any(), any())).thenReturn(railName);
        ApplicationEventPublisher publisher = publishedEvents::add;
        RailTransactions rt = new RailTransactions(db, json, registry, publisher, ops, ledger, tm, false, custodianBackstop, killSwitch);
        rt.setPayouts(payouts);
        return rt;
    }

    private PaymentRail baseMockRail(String name) {
        PaymentRail rail = mock(PaymentRail.class);
        when(rail.name()).thenReturn(name);
        when(rail.supports(anyString(), any(), any())).thenReturn(true);
        return rail;
    }

    @Test
    void recoveryUnavailableOnUnknownPayoutKeepsUnknownAndNeverPublishesFailed() {
        PaymentRail rail = baseMockRail("mtn");
        when(rail.payout(any())).thenReturn(PaymentRail.Result.unknown("Socket timeout"));
        when(rail.query(anyString(), anyString(), any()))
                .thenReturn(PaymentRail.Result.unavailable("Circuit breaker OPEN"));

        List<Object> events = new java.util.ArrayList<>();
        RailTransactions rt = railTxWith(rail, events);

        UUID purposeId = UUID.randomUUID();
        var created = tx.execute(s -> rt.create("mtn", "payout", "payout", purposeId, 50_00L,
                RailTransactions.Request.momo("0241234567", "mtn", "Test payout")));

        var submitted = rt.submit(created.id());
        assertThat(submitted.status()).isEqualTo("unknown");

        int resolved = rt.recover(50, true);
        assertThat(resolved).isZero();

        var after = rt.find(created.id()).orElseThrow();
        assertThat(after.status()).isEqualTo("unknown");
        assertThat(events).noneMatch(e -> e instanceof RailEvents.Failed);

        Long reserved = db.sql("""
                SELECT reserved_outflow_pesewas FROM custodian_daily_outflow
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("code", CustodianBankBackstopService.PRIMARY_TRUST_CODE)
                .param("d", LocalDate.now(ZoneId.of("Africa/Accra")))
                .query(Long.class).single();
        assertThat(reserved).isEqualTo(50_00L);
    }

    @Test
    void notFoundReleasesCustodianReservationBeforeRetryAndSettlesOnce() {
        PaymentRail rail = baseMockRail("mtn");
        when(rail.payout(any()))
                .thenReturn(PaymentRail.Result.unknown("Timeout 1"))
                .thenReturn(new PaymentRail.Result(
                        PaymentRail.Status.SUCCEEDED, "MTN-OK-1", 75_00L, "GHS", 0L, null, "Paid"));
        when(rail.query(anyString(), anyString(), any()))
                .thenReturn(new PaymentRail.Result(
                        PaymentRail.Status.NOT_FOUND, null, null, null, null, null, "Not found"));

        List<Object> events = new java.util.ArrayList<>();
        RailTransactions rt = railTxWith(rail, events);

        UUID purposeId = UUID.randomUUID();
        var created = tx.execute(s -> rt.create("mtn", "payout", "payout", purposeId, 75_00L,
                RailTransactions.Request.momo("0241234567", "mtn", "Retry payout")));

        // 1. Submit -> unknown (reserved = 75_00)
        rt.submit(created.id());
        // 2. Recover -> NOT_FOUND -> releases reservation and resets to 'created'
        rt.recover(50, true);
        assertThat(rt.find(created.id()).orElseThrow().status()).isEqualTo("created");

        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        Long reservedAfterNotFound = db.sql("""
                SELECT reserved_outflow_pesewas FROM custodian_daily_outflow
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("code", CustodianBankBackstopService.PRIMARY_TRUST_CODE).param("d", today)
                .query(Long.class).single();
        assertThat(reservedAfterNotFound).isZero();

        // 3. Recover -> re-submits 'created' payout -> SUCCEEDED
        rt.recover(50, true);
        assertThat(rt.find(created.id()).orElseThrow().status()).isEqualTo("succeeded");

        var snapshot = db.sql("""
                SELECT reserved_outflow_pesewas, settled_outflow_pesewas FROM custodian_daily_outflow
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("code", CustodianBankBackstopService.PRIMARY_TRUST_CODE).param("d", today)
                .query((rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}).single();
        assertThat(snapshot[0]).isZero();
        assertThat(snapshot[1]).isEqualTo(75_00L);
    }

    @Test
    void custodianBackstopFailClosedWhenUnreconciledRejectsNonGhsAndChecksOverflow() {
        var report = custodianBackstop.verifyTrustSafeguarding("GHS");
        assertThat(report.reconciledBankBalancePesewas()).isNull();
        assertThat(report.safeguardingSatisfied()).isFalse();

        assertThatThrownBy(() -> custodianBackstop.verifyTrustSafeguarding("USD"))
                .isInstanceOf(IllegalArgumentException.class);

        var nonGhs = custodianBackstop.authorizeOutboundDisbursement(
                UUID.randomUUID(), "REF-USD", "mtn", "payout", 1000L, "USD");
        assertThat(nonGhs.allowed()).isFalse();
        assertThat(nonGhs.reasonCode()).isEqualTo("custodian_unsupported_currency");

        custodianBackstop.authorizeOutboundDisbursement(
                UUID.randomUUID(), "REF-1", "mtn", "payout", 1000L, "GHS");
        custodianBackstop.updateConfig(Long.MAX_VALUE, Long.MAX_VALUE, 0L, null, false, "test");
        assertThatThrownBy(() -> custodianBackstop.authorizeOutboundDisbursement(
                UUID.randomUUID(), "REF-OVERFLOW", "mtn", "payout", Long.MAX_VALUE, "GHS"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void recoverSkipsCreatedPayoutWhenKillSwitchActive() {
        PaymentRail rail = baseMockRail("mtn");
        List<Object> events = new java.util.ArrayList<>();
        RailTransactions rt = railTxWith(rail, events);

        UUID purposeId = UUID.randomUUID();
        var created = tx.execute(s -> rt.create("mtn", "payout", "payout", purposeId, 25_00L,
                RailTransactions.Request.momo("0241234567", "mtn", "Kill switch payout")));

        killSwitch.setGlobalFrozen(true, "Emergency freeze", "ops-admin");
        rt.recover(50, true);

        assertThat(rt.find(created.id()).orElseThrow().status()).isEqualTo("created");
    }

    @Test
    void handleCallbackRollsBackDedupeOnTransientErrorAndOrdersPurposeIdFallback() {
        PaymentRail rail = baseMockRail("mtn");
        when(rail.callbacksAreAuthoritative()).thenReturn(false);
        UUID sharedPurposeId = UUID.randomUUID();

        List<Object> events = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean failFirstEvent = new java.util.concurrent.atomic.AtomicBoolean(true);
        String railName = rail.name();
        RailRegistry registry = mock(RailRegistry.class);
        when(registry.names()).thenReturn(Set.of(railName));
        when(registry.defaultName()).thenReturn(railName);
        when(registry.get(railName)).thenReturn(rail);
        when(registry.select(anyString(), any(), any())).thenReturn(railName);
        ApplicationEventPublisher flakyPublisher = event -> {
            if (failFirstEvent.compareAndSet(true, false)) {
                throw new IllegalStateException("Simulated transient DB/event failure");
            }
            events.add(event);
        };
        RailTransactions rt = new RailTransactions(
                db, json, registry, flakyPublisher, ops, ledger, tm, false, custodianBackstop, killSwitch);

        var first = tx.execute(s -> rt.create("mtn", "charge", "checkout", sharedPurposeId, 30_00L,
                RailTransactions.Request.momo("0241234567", "mtn", "Attempt 1")));
        var second = tx.execute(s -> rt.create("mtn", "charge", "checkout", sharedPurposeId, 30_00L,
                RailTransactions.Request.momo("0241234567", "mtn", "Attempt 2")));

        String dedupeKey = "mtn-cb-" + UUID.randomUUID();
        when(rail.verifyCallback(any(), any())).thenReturn(
                new PaymentRail.Callback(dedupeKey, "charge.success", "charge",
                        sharedPurposeId.toString(), "RAIL-REF-1", null));
        when(rail.query(anyString(), anyString(), any()))
                .thenReturn(new PaymentRail.Result(
                        PaymentRail.Status.SUCCEEDED, "RAIL-REF-1", 30_00L, "GHS", 0L, null, "OK"));

        // 1. Transient failure rolls back rail_callback row
        assertThatThrownBy(() -> rt.handleCallback("mtn", Map.of(), "{}"))
                .isInstanceOf(IllegalStateException.class);
        Integer cbCount = db.sql("SELECT count(*) FROM rail_callback WHERE dedupe_key = :k")
                .param("k", dedupeKey).query(Integer.class).single();
        assertThat(cbCount).isZero();

        // 2. Partner retry succeeds and picks the latest rail_transaction (second)
        var outcome = rt.handleCallback("mtn", Map.of(), "{}");
        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.outcome()).isEqualTo("applied:SUCCEEDED");
        assertThat(rt.find(second.id()).orElseThrow().status()).isEqualTo("succeeded");
        assertThat(rt.find(first.id()).orElseThrow().status()).isEqualTo("created");
    }

    @Test
    void cardTokenVaultUpgradeOnStartupRunsInsideTransaction() {
        // Verifies upgradeOnStartup() executes FOR UPDATE SKIP LOCKED inside a real transaction without error
        cardTokenVault.upgradeOnStartup();
    }

    @Test
    void returnedPayoutDecrementsSettledOutflowWithoutStealingOtherInFlightReservation() {
        PaymentRail rail = baseMockRail("mtn");
        when(rail.payout(any()))
                .thenReturn(new PaymentRail.Result(
                        PaymentRail.Status.SUCCEEDED, "MTN-A-OK", 10_000L, "GHS", 0L, null, "Paid"))
                .thenReturn(PaymentRail.Result.unknown("Payout B in-flight"));

        List<Object> events = new java.util.ArrayList<>();
        RailTransactions rt = railTxWith(rail, events);

        // Payout A (10,000) succeeds -> settled = 10,000, reserved = 0
        var txA = tx.execute(s -> rt.create("mtn", "payout", "payout", UUID.randomUUID(), 10_000L,
                RailTransactions.Request.momo("0241234567", "mtn", "Payout A")));
        rt.submit(txA.id());
        assertThat(rt.find(txA.id()).orElseThrow().status()).isEqualTo("succeeded");

        // Payout B (7,000) submitted and remains in-flight -> reserved = 7,000, settled = 10,000
        var txB = tx.execute(s -> rt.create("mtn", "payout", "payout", UUID.randomUUID(), 7_000L,
                RailTransactions.Request.momo("0247654321", "mtn", "Payout B")));
        rt.submit(txB.id());
        assertThat(rt.find(txB.id()).orElseThrow().status()).isEqualTo("unknown");

        // Payout A is returned (reversed) by receiving rail
        rt.apply(txA.id(), new PaymentRail.Result(
                PaymentRail.Status.RETURNED, "MTN-A-RET", 10_000L, "GHS", 0L, null, "Recipient closed"), "callback");
        assertThat(rt.find(txA.id()).orElseThrow().status()).isEqualTo("returned");

        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        var snapshot = db.sql("""
                SELECT reserved_outflow_pesewas, settled_outflow_pesewas FROM custodian_daily_outflow
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("code", CustodianBankBackstopService.PRIMARY_TRUST_CODE).param("d", today)
                .query((rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}).single();
        // Settled outflow drops to 0; Payout B's in-flight 7,000 reservation is completely untouched!
        assertThat(snapshot[0]).isEqualTo(7_000L);
        assertThat(snapshot[1]).isZero();
    }

    @Test
    void custodianConfigRejectsPartialInconsistentCapsAndAuthorizeIsIdempotentPerTxId() {
        // Default perPayoutCapPesewas is 10,000,000,000; partial update setting dailyCap = 1,000 must fail
        assertThatThrownBy(() -> custodianBackstop.updateConfig(1_000L, null, null, null, null, "ops"))
                .isInstanceOf(com.elepay.common.ApiException.class)
                .hasMessageContaining("Per-payout cap cannot exceed the daily outflow cap");

        // Non-positive outbound disbursement is blocked
        var zeroDec = custodianBackstop.authorizeOutboundDisbursement(
                UUID.randomUUID(), "REF-ZERO", "mtn", "payout", 0L, "GHS");
        assertThat(zeroDec.allowed()).isFalse();
        assertThat(zeroDec.reasonCode()).isEqualTo("custodian_invalid_amount");

        // Repeated authorizeOutboundDisbursement on the same rail_transaction id reserves only once
        PaymentRail rail = baseMockRail("mtn");
        RailTransactions rt = railTxWith(rail, new java.util.ArrayList<>());
        var created = tx.execute(s -> rt.create("mtn", "payout", "payout", UUID.randomUUID(), 4_500L,
                RailTransactions.Request.momo("0241234567", "mtn", "Idem auth")));

        assertThat(custodianBackstop.authorizeOutboundDisbursement(
                created.id(), created.reference(), "mtn", "payout", 4_500L, "GHS").allowed()).isTrue();
        assertThat(custodianBackstop.authorizeOutboundDisbursement(
                created.id(), created.reference(), "mtn", "payout", 4_500L, "GHS").allowed()).isTrue();

        LocalDate today = LocalDate.now(ZoneId.of("Africa/Accra"));
        Long reserved = db.sql("""
                SELECT reserved_outflow_pesewas FROM custodian_daily_outflow
                WHERE trust_account_code = :code AND business_date = :d""")
                .param("code", CustodianBankBackstopService.PRIMARY_TRUST_CODE).param("d", today)
                .query(Long.class).single();
        assertThat(reserved).isEqualTo(4_500L);
    }

    @Test
    void verifyTrustSafeguardingIncludesCustomerWalletLiabilities() {
        long liabilitiesBefore = custodianBackstop.verifyTrustSafeguarding("GHS").totalSafeguardedLiabilitiesPesewas();
        UUID customerId = UUID.randomUUID();
        String custAcct = ledger.open("customer", customerId, "available", Ledger.GHS);
        ledger.post(Ledger.Posting.of("cust-safeguard-test", UUID.randomUUID().toString(), "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 25_000L),
                Ledger.Leg.credit(custAcct, 25_000L))));

        long liabilitiesAfter = custodianBackstop.verifyTrustSafeguarding("GHS").totalSafeguardedLiabilitiesPesewas();
        assertThat(liabilitiesAfter).isEqualTo(liabilitiesBefore + 25_000L);

        // Trust balance 1 pesewa short fails safeguarding; exact match satisfies safeguarding
        custodianBackstop.updateConfig(null, null, null, liabilitiesAfter - 1L, false, "ops");
        assertThat(custodianBackstop.verifyTrustSafeguarding("GHS").safeguardingSatisfied()).isFalse();

        custodianBackstop.updateConfig(null, null, null, liabilitiesAfter, false, "ops");
        assertThat(custodianBackstop.verifyTrustSafeguarding("GHS").safeguardingSatisfied()).isTrue();
    }

    @Test
    void recoverSkipsCreatedPayoutWhenPerMerchantKillSwitchActive() {
        PaymentRail rail = baseMockRail("mtn");
        List<Object> events = new java.util.ArrayList<>();
        RailTransactions rt = railTxWith(rail, events);

        UUID merchantId = UUID.randomUUID();
        UUID payoutId = UUID.randomUUID();
        var created = tx.execute(s -> {
            var railTx = rt.create("mtn", "payout", "payout", payoutId, 3_000L,
                    RailTransactions.Request.momo("0241234567", "mtn", "Merchant kill switch payout"));
            UUID resTx = ledger.post(Ledger.Posting.of("ks-res", payoutId.toString(), "payout_reservation", List.of(
                    Ledger.Leg.debit(Ledger.clearing("sandbox"), 3_000L),
                    Ledger.Leg.credit(ledger.open("merchant", merchantId, "reserved", Ledger.GHS), 3_000L)))).transactionId();
            db.sql("""
                    INSERT INTO payout (id, reference, owner_type, owner_id, destination, amount, rail, status,
                                        reservation_tx, rail_tx, requested_by, verification)
                    VALUES (:id, :ref, 'merchant', :m, '{}'::jsonb, 3000, 'mtn', 'reserved', :rtx, :railtx, 'test', 'verified')""")
                    .param("id", payoutId).param("ref", "PO-KS-" + payoutId.toString().substring(0, 8))
                    .param("m", merchantId).param("rtx", resTx).param("railtx", railTx.id()).update();
            return railTx;
        });

        // Global kill switch is OFF, only this specific merchant is frozen
        killSwitch.setGlobalFrozen(false, "global-open", "ops");
        killSwitch.setMerchantFrozen(merchantId, true, "Compromised merchant", "ops");
        try {
            rt.recover(50, true);
            assertThat(rt.find(created.id()).orElseThrow().status()).isEqualTo("created");
            org.mockito.Mockito.verify(rail, org.mockito.Mockito.never()).payout(any());
        } finally {
            killSwitch.setMerchantFrozen(merchantId, false, "test-cleanup", "ops");
        }
    }
}
