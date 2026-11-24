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
        return new RailTransactions(db, json, registry, publisher, ops, ledger, tm, false, custodianBackstop, killSwitch);
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
}
