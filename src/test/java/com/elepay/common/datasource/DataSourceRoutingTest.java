package com.elepay.common.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("Cell-Aware & Read/Write DataSource Routing Unit Tests")
class DataSourceRoutingTest {

    @BeforeEach
    void setUp() {
        DataSourceRouteContext.clear();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @AfterEach
    void tearDown() {
        DataSourceRouteContext.clear();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void routesWriteToPrimaryAndReadOnlyToReplica() {
        DataSource primary = new StubDataSource("cell-0-primary");
        DataSource replica = new StubDataSource("cell-0-replica");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        CellAwareRoutingDataSource router = new CellAwareRoutingDataSource(new ReplicaHealthTracker(), metrics);
        router.registerCell(CellDataSourceKey.DEFAULT_CELL_ID, primary, replica);
        router.afterPropertiesSet();

        // Default (non-read-only) -> PRIMARY
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultPrimary());
        assertThat(router.determineTargetDataSource()).isSameAs(primary);

        // Read-only transaction -> REPLICA
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultReplica());
        assertThat(router.determineTargetDataSource()).isSameAs(replica);

        assertThat(metrics.counter(CellAwareRoutingDataSource.METRIC_ROUTE_COUNTER,
                "cell", "cell-0", "route", "primary").count()).isEqualTo(2.0);
        assertThat(metrics.counter(CellAwareRoutingDataSource.METRIC_ROUTE_COUNTER,
                "cell", "cell-0", "route", "replica").count()).isEqualTo(2.0);
    }

    @Test
    void ryowStickyPrimaryOverridesReadOnlyAndExplicitReplicaOverride() {
        DataSource primary = new StubDataSource("cell-0-primary");
        DataSource replica = new StubDataSource("cell-0-replica");

        CellAwareRoutingDataSource router = new CellAwareRoutingDataSource(new ReplicaHealthTracker());
        router.registerCell(CellDataSourceKey.DEFAULT_CELL_ID, primary, replica);
        router.afterPropertiesSet();

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(router.determineTargetDataSource()).isSameAs(replica);

        // Once a write occurs on this thread, subsequent reads stick to PRIMARY (RYOW)
        DataSourceRouteContext.markPrimaryWritten();
        assertThat(DataSourceRouteContext.isPrimaryWritten()).isTrue();
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultPrimary());
        assertThat(router.determineTargetDataSource()).isSameAs(primary);

        // Even an explicit REPLICA override cannot violate RYOW sticky-primary
        DataSourceRouteContext.setRouteOverride(RouteType.REPLICA);
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultPrimary());
        assertThat(router.determineTargetDataSource()).isSameAs(primary);

        // Clearing context restores normal read-only replica routing
        DataSourceRouteContext.clear();
        assertThat(DataSourceRouteContext.isPrimaryWritten()).isFalse();
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultReplica());
        assertThat(router.determineTargetDataSource()).isSameAs(replica);
    }

    @Test
    void explicitRouteOverrideTakesPrecedenceWhenNoWriteOccurred() {
        DataSource primary = new StubDataSource("cell-0-primary");
        DataSource replica = new StubDataSource("cell-0-replica");

        CellAwareRoutingDataSource router = new CellAwareRoutingDataSource(new ReplicaHealthTracker());
        router.registerCell(CellDataSourceKey.DEFAULT_CELL_ID, primary, replica);
        router.afterPropertiesSet();

        // Force PRIMARY inside a read-only transaction
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        DataSourceRouteContext.setRouteOverride(RouteType.PRIMARY);
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultPrimary());
        assertThat(router.determineTargetDataSource()).isSameAs(primary);

        // Force REPLICA outside a read-only transaction
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        DataSourceRouteContext.setRouteOverride(RouteType.REPLICA);
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultReplica());
        assertThat(router.determineTargetDataSource()).isSameAs(replica);
    }

    @Test
    void replicaFailureAndExcessiveLagFallBackToPrimaryAndRecoverAfterCooldown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
        ReplicaHealthTracker tracker = new ReplicaHealthTracker(2_000L, Duration.ofSeconds(15), clock);

        DataSource primary = new StubDataSource("cell-0-primary");
        DataSource replica = new StubDataSource("cell-0-replica");
        CellAwareRoutingDataSource router = new CellAwareRoutingDataSource(tracker);
        router.registerCell("cell-0", primary, replica);
        router.afterPropertiesSet();

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(tracker.isReplicaHealthy("cell-0")).isTrue();
        assertThat(router.determineTargetDataSource()).isSameAs(replica);

        // Probe failure trips circuit breaker -> PRIMARY
        tracker.recordReplicaFailure("cell-0");
        assertThat(tracker.isReplicaHealthy("cell-0")).isFalse();
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultPrimary());
        assertThat(router.determineTargetDataSource()).isSameAs(primary);

        // Still unhealthy before cooldown expires
        clock.advance(Duration.ofSeconds(10));
        assertThat(tracker.isReplicaHealthy("cell-0")).isFalse();
        assertThat(router.determineTargetDataSource()).isSameAs(primary);

        // Cooldown expires -> half-open / eligible for REPLICA again
        clock.advance(Duration.ofSeconds(5));
        assertThat(tracker.isReplicaHealthy("cell-0")).isTrue();
        assertThat(router.determineTargetDataSource()).isSameAs(replica);

        // Lag exceeding maxLagMs (2000ms) trips circuit breaker -> PRIMARY
        tracker.recordReplicaHealthy("cell-0", 3_500L);
        assertThat(tracker.isReplicaHealthy("cell-0")).isFalse();
        assertThat(tracker.getLastLagMs("cell-0")).isEqualTo(3_500L);
        assertThat(router.determineTargetDataSource()).isSameAs(primary);

        // Healthy lag probe immediately restores REPLICA routing
        tracker.recordReplicaHealthy("cell-0", 120L);
        assertThat(tracker.isReplicaHealthy("cell-0")).isTrue();
        assertThat(tracker.getLastLagMs("cell-0")).isEqualTo(120L);
        assertThat(tracker.getConsecutiveFailures("cell-0")).isZero();
        assertThat(router.determineTargetDataSource()).isSameAs(replica);
    }

    @Test
    void multiCellRoutingAndPerCellReplicaIsolation() {
        ReplicaHealthTracker tracker = new ReplicaHealthTracker();
        DataSource cell0Primary = new StubDataSource("cell-0-primary");
        DataSource cell0Replica = new StubDataSource("cell-0-replica");
        DataSource cell1Primary = new StubDataSource("cell-1-primary");
        DataSource cell1Replica = new StubDataSource("cell-1-replica");

        CellAwareRoutingDataSource router = new CellAwareRoutingDataSource(tracker);
        router.registerCell("cell-0", cell0Primary, cell0Replica);
        router.registerCell("cell-1", cell1Primary, cell1Replica);
        router.afterPropertiesSet();

        assertThat(router.registeredCells()).containsExactlyInAnyOrder("cell-0", "cell-1");

        // Route to cell-1 primary
        DataSourceRouteContext.setCellId("cell-1");
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.primary("cell-1"));
        assertThat(router.determineTargetDataSource()).isSameAs(cell1Primary);

        // Route to cell-1 replica on read-only
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.replica("cell-1"));
        assertThat(router.determineTargetDataSource()).isSameAs(cell1Replica);

        // Failure on cell-1 replica falls back to cell-1 primary WITHOUT affecting cell-0 replica
        tracker.recordReplicaFailure("cell-1");
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.primary("cell-1"));
        assertThat(router.determineTargetDataSource()).isSameAs(cell1Primary);

        DataSourceRouteContext.setCellId("cell-0");
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.replica("cell-0"));
        assertThat(router.determineTargetDataSource()).isSameAs(cell0Replica);
    }

    @Test
    void unregisteredCellFallsBackToDefaultCellPrimaryInSingleCellMode() {
        DataSource cell0Primary = new StubDataSource("cell-0-primary");
        DataSource cell0Replica = new StubDataSource("cell-0-replica");

        CellAwareRoutingDataSource router = new CellAwareRoutingDataSource(new ReplicaHealthTracker());
        router.registerCell("cell-0", cell0Primary, cell0Replica);
        router.afterPropertiesSet();

        DataSourceRouteContext.setCellId("cell-99");
        // Write transaction on logical cell falls back to (cell-0, PRIMARY)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultPrimary());
        assertThat(router.determineTargetDataSource()).isSameAs(cell0Primary);

        // Read-only transaction on logical cell falls back to cell-0 while preserving REPLICA route
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultReplica());
        assertThat(router.determineTargetDataSource()).isSameAs(cell0Replica);
    }

    @Test
    void singlePoolModeForcesPrimaryWhenNoDistinctReplicaConfigured() {
        DataSource primary = new StubDataSource("cell-0-primary");

        CellAwareRoutingDataSource router = new CellAwareRoutingDataSource(new ReplicaHealthTracker());
        router.registerCell("cell-0", primary, null);
        router.afterPropertiesSet();

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(router.hasDistinctReplica("cell-0")).isFalse();
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultPrimary());
        assertThat(router.determineTargetDataSource()).isSameAs(primary);

        // Passing the same DataSource instance as both primary and replica also stays single-pool
        router.registerCell("cell-0", primary, primary);
        assertThat(router.hasDistinctReplica("cell-0")).isFalse();
        assertThat(router.determineCurrentLookupKey()).isEqualTo(CellDataSourceKey.defaultPrimary());
    }

    @Test
    void setTargetDataSourcesPopulatesDynamicRoutingMap() {
        DataSource primary = new StubDataSource("cell-0-primary");
        DataSource replica = new StubDataSource("cell-0-replica");

        CellAwareRoutingDataSource router = new CellAwareRoutingDataSource(new ReplicaHealthTracker());
        Map<Object, Object> targets = new HashMap<>();
        targets.put(CellDataSourceKey.defaultPrimary(), primary);
        targets.put(CellDataSourceKey.defaultReplica(), replica);
        router.setTargetDataSources(targets);
        router.setDefaultTargetDataSource(primary);
        router.afterPropertiesSet();

        assertThat(router.hasCell("cell-0")).isTrue();
        assertThat(router.hasDistinctReplica("cell-0")).isTrue();

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(router.determineTargetDataSource()).isSameAs(replica);
    }

    @Test
    void configDetectsDistinctReplicaUrlAndAppliesPostgresTuning() {
        String primaryUrl = "jdbc:postgresql://primary:5432/elepay";
        String replicaUrl = "jdbc:postgresql://replica:5432/elepay";

        assertThat(ReadWriteDataSourceConfig.isDistinctReplicaUrl(primaryUrl, null)).isFalse();
        assertThat(ReadWriteDataSourceConfig.isDistinctReplicaUrl(primaryUrl, "")).isFalse();
        assertThat(ReadWriteDataSourceConfig.isDistinctReplicaUrl(primaryUrl, "   ")).isFalse();
        assertThat(ReadWriteDataSourceConfig.isDistinctReplicaUrl(primaryUrl, primaryUrl)).isFalse();
        assertThat(ReadWriteDataSourceConfig.isDistinctReplicaUrl(primaryUrl, " " + primaryUrl + " ")).isFalse();
        assertThat(ReadWriteDataSourceConfig.isDistinctReplicaUrl(primaryUrl, replicaUrl)).isTrue();

        HikariConfig config = ReadWriteDataSourceConfig.buildHikariConfig(
                "TestPool", primaryUrl, "elepay", "secret", 20, 5, 30000L, false);
        assertThat(config.getDataSourceProperties().getProperty("reWriteBatchedInserts")).isEqualTo("true");
        assertThat(config.getDataSourceProperties().getProperty("prepareThreshold")).isEqualTo("3");
        assertThat(config.isReadOnly()).isFalse();
    }

    @Test
    void routeContextIsIsolatedAcrossVirtualThreads() throws Exception {
        DataSourceRouteContext.setCellId("cell-parent");
        DataSourceRouteContext.markPrimaryWritten();

        AtomicReference<String> childCell = new AtomicReference<>();
        AtomicReference<Boolean> childWritten = new AtomicReference<>();

        Thread vt = Thread.ofVirtual().start(() -> {
            childCell.set(DataSourceRouteContext.getCellId());
            childWritten.set(DataSourceRouteContext.isPrimaryWritten());
            DataSourceRouteContext.setCellId("cell-vt");
            DataSourceRouteContext.clear();
        });
        vt.join();

        assertThat(childCell.get()).isEqualTo(DataSourceRouteContext.DEFAULT_CELL_ID);
        assertThat(childWritten.get()).isFalse();
        assertThat(DataSourceRouteContext.getCellId()).isEqualTo("cell-parent");
        assertThat(DataSourceRouteContext.isPrimaryWritten()).isTrue();
    }

    private static final class StubDataSource extends AbstractDataSource {
        private final String name;

        private StubDataSource(String name) {
            this.name = name;
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("StubDataSource: " + name);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new UnsupportedOperationException("StubDataSource: " + name);
        }

        @Override
        public String toString() {
            return "StubDataSource[" + name + "]";
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
