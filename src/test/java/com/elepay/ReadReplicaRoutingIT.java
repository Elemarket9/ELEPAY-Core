package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.common.datasource.CellDataSourceKey;
import com.elepay.common.datasource.DataSourceRouteContext;
import com.elepay.common.datasource.RouteType;
import com.elepay.reconciliation.ReconSuspenseService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Integration tests for Stage 3B/3C read-replica routing:
 * - {@link DataSourceRouteContext} RYOW sticky-primary and cell-context behavior
 * - {@code @Transactional(readOnly = true)} reporting/lookup paths across Operations,
 *   OperationalMetrics, Checkouts, and ReconSuspenseService executing cleanly through
 *   the routing DataSource without mutating state.
 */
@TestPropertySource(properties = "elepay.datasource.read-write-split.enabled=true")
class ReadReplicaRoutingIT extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcClient db;
    @Autowired ReconSuspenseService reconSuspenseService;
    @Autowired MeterRegistry meterRegistry;
    @Autowired ApplicationContext applicationContext;

    TestApi api;
    String opsToken;
    String ownerToken;
    UUID merchantId;

    @BeforeEach
    void setUp() {
        DataSourceRouteContext.clear();
        api = new TestApi(port);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        opsToken = api.token("ops-ro-" + suffix, "ops", "ops_admin");
        ownerToken = api.token("owner-ro-" + suffix);

        var created = api.call("POST", "/v1/merchants", ownerToken, null, Map.of(
                "legalName", "ReadRoute Merchant " + suffix,
                "tradingName", "ReadRoute " + suffix,
                "kind", "merchant",
                "contactPhone", "0241234567"));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
        merchantId = UUID.fromString(created.body().get("id").asString());

        var review = api.call("POST", "/v1/ops/merchants/" + merchantId + "/review", opsToken, null,
                Map.of("decision", "approve"));
        assertThat(review.status()).as(review.body().toString()).isEqualTo(200);
    }

    @AfterEach
    void tearDown() {
        DataSourceRouteContext.clear();
    }

    @Test
    void routingDataSourceUsesLazyConnectionProxy() {
        assertThat(dataSource).isInstanceOf(LazyConnectionDataSourceProxy.class);
    }

    @Test
    void routeContextEnforcesRyowStickyPrimaryEvenInsideReadOnlyTransaction() throws Exception {
        try {
            assertThat(DataSourceRouteContext.getCellId()).isEqualTo(CellDataSourceKey.DEFAULT_CELL_ID);
            assertThat(DataSourceRouteContext.isPrimaryWritten()).isFalse();
            assertThat(DataSourceRouteContext.getRouteOverride()).isNull();

            DataSourceRouteContext.setCellId("cell-1");
            assertThat(DataSourceRouteContext.getCellId()).isEqualTo("cell-1");
            DataSourceRouteContext.setCellId(null);
            assertThat(DataSourceRouteContext.getCellId()).isEqualTo(CellDataSourceKey.DEFAULT_CELL_ID);

            DataSourceRouteContext.setRouteOverride(RouteType.REPLICA);
            assertThat(DataSourceRouteContext.getRouteOverride()).isEqualTo(RouteType.REPLICA);
            DataSourceRouteContext.setRouteOverride(RouteType.PRIMARY);
            assertThat(DataSourceRouteContext.getRouteOverride()).isEqualTo(RouteType.PRIMARY);
            DataSourceRouteContext.setRouteOverride(null);
            assertThat(DataSourceRouteContext.getRouteOverride()).isNull();

            // Mark primary written (RYOW) and verify it remains sticky even inside a read-only transaction.
            DataSourceRouteContext.markPrimaryWritten();
            assertThat(DataSourceRouteContext.isPrimaryWritten()).isTrue();

            TransactionTemplate readOnlyTx = new TransactionTemplate(txManager);
            readOnlyTx.setReadOnly(true);

            Integer probe = readOnlyTx.execute(status -> {
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                assertThat(DataSourceRouteContext.isPrimaryWritten()).isTrue();
                CellDataSourceKey effectiveKey = new CellDataSourceKey(
                        DataSourceRouteContext.getCellId(),
                        DataSourceRouteContext.isPrimaryWritten() ? RouteType.PRIMARY : RouteType.REPLICA);
                assertThat(effectiveKey.routeType()).isEqualTo(RouteType.PRIMARY);
                return db.sql("SELECT 1").query(Integer.class).single();
            });
            assertThat(probe).isEqualTo(1);

            // Virtual-thread isolation: ThreadLocal state must not leak across threads.
            AtomicBoolean leakedPrimaryWritten = new AtomicBoolean(true);
            AtomicReference<String> childCellId = new AtomicReference<>();
            Thread vt = Thread.ofVirtual().start(() -> {
                leakedPrimaryWritten.set(DataSourceRouteContext.isPrimaryWritten());
                childCellId.set(DataSourceRouteContext.getCellId());
            });
            vt.join();
            assertThat(leakedPrimaryWritten.get()).isFalse();
            assertThat(childCellId.get()).isEqualTo(CellDataSourceKey.DEFAULT_CELL_ID);
        } finally {
            DataSourceRouteContext.clear();
        }

        assertThat(DataSourceRouteContext.isPrimaryWritten()).isFalse();
        assertThat(DataSourceRouteContext.getCellId()).isEqualTo(CellDataSourceKey.DEFAULT_CELL_ID);
        assertThat(DataSourceRouteContext.getRouteOverride()).isNull();
    }

    @Test
    void readOnlyReportingAndLookupEndpointsExecuteCleanlyWithoutMutatingState() throws Exception {
        String orderRef = "RO-" + UUID.randomUUID().toString().substring(0, 8);
        var created = api.call("POST", "/v1/merchants/" + merchantId + "/checkouts", ownerToken,
                UUID.randomUUID().toString(),
                Map.of("amount", 1500, "orderReference", orderRef, "description", "Read-only routing test"));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
        String checkoutRef = created.body().get("reference").asString();

        long checkoutsBefore = db.sql("SELECT count(*) FROM checkout").query(Long.class).single();
        long auditBefore = db.sql("SELECT count(*) FROM audit_log").query(Long.class).single();

        // Operations read-only endpoints
        assertThat(api.call("GET", "/v1/ops/overview", opsToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/ops/queue?status=open", opsToken, null, null).status()).isEqualTo(200);
        var integrity = api.call("GET", "/v1/ops/ledger/integrity", opsToken, null, null);
        assertThat(integrity.status()).as(integrity.body().toString()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/ops/disputes?status=open", opsToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/ops/reconciliation/runs", opsToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/ops/merchants/search?q=ReadRoute", opsToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/ops/merchants/" + merchantId + "/controls", opsToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/ops/audit?subject=" + merchantId, opsToken, null, null).status()).isEqualTo(200);

        // Checkouts read-only endpoints
        var listResp = api.call("GET", "/v1/merchants/" + merchantId + "/checkouts?limit=10", ownerToken, null, null);
        assertThat(listResp.status()).as(listResp.body().toString()).isEqualTo(200);
        assertThat(listResp.body().isArray()).isTrue();
        assertThat(listResp.body().size()).isGreaterThanOrEqualTo(1);

        var oneResp = api.call("GET", "/v1/merchants/" + merchantId + "/checkouts/" + checkoutRef, ownerToken, null, null);
        assertThat(oneResp.status()).as(oneResp.body().toString()).isEqualTo(200);
        assertThat(oneResp.body().get("reference").asString()).isEqualTo(checkoutRef);

        var byOrderResp = api.call("GET", "/v1/merchants/" + merchantId + "/orders/" + orderRef, ownerToken, null, null);
        assertThat(byOrderResp.status()).as(byOrderResp.body().toString()).isEqualTo(200);
        assertThat(byOrderResp.body().get("reference").asString()).isEqualTo(checkoutRef);

        assertThat(api.call("GET", "/v1/merchants/" + merchantId + "/transactions?limit=10", ownerToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/merchants/" + merchantId + "/fee-schedule", ownerToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/merchants/" + merchantId + "/refunds", ownerToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/merchants/" + merchantId + "/settlements", ownerToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/merchants/" + merchantId + "/disputes", ownerToken, null, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/merchants/" + merchantId + "/payment-links", ownerToken, null, null).status()).isEqualTo(200);
        JsonNode qrBody = api.call("GET", "/v1/merchants/" + merchantId + "/qr", ownerToken, null, null).body();
        assertThat(qrBody.get("qrPayload").asString()).isNotBlank();

        // ReconSuspenseService read-only query methods
        assertThat(reconSuspenseService.findById(UUID.randomUUID())).isEmpty();
        assertThat(reconSuspenseService.listOpenSuspenseItems(null)).isNotNull();

        // OperationalMetrics @Transactional(readOnly = true) refresh executes cleanly
        Object operationalMetrics = applicationContext.getBean("operationalMetrics");
        operationalMetrics.getClass().getMethod("refresh").invoke(operationalMetrics);
        assertThat(meterRegistry.find("elepay.ops.open_total").gauge()).isNotNull();
        assertThat(meterRegistry.find("elepay.ops.open_total").gauge().value()).isNotNaN();

        // Confirm none of the read-only operations mutated checkout or audit_log state
        long checkoutsAfter = db.sql("SELECT count(*) FROM checkout").query(Long.class).single();
        long auditAfter = db.sql("SELECT count(*) FROM audit_log").query(Long.class).single();
        assertThat(checkoutsAfter).isEqualTo(checkoutsBefore);
        assertThat(auditAfter).isEqualTo(auditBefore);
    }
}
