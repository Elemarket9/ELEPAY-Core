package com.elepay.common.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * PostgreSQL High Availability Connection Pooling, Read/Write Split &amp; Cell-Aware Routing
 * (Stage 3B + Stage 4 Shared Foundation).
 *
 * <p>When {@code elepay.datasource.read-write-split.enabled=true}:
 * <ul>
 *   <li>Creates a single HikariCP pool if {@code elepay.datasource.replica-url} is absent, blank,
 *       or identical to {@code spring.datasource.url} (never opening a duplicate idle pool).</li>
 *   <li>Wraps {@link CellAwareRoutingDataSource} in {@link LazyConnectionDataSourceProxy} as the
 *       {@code @Primary DataSource} so {@code @Transactional(readOnly = true)} is evaluated before
 *       acquiring a JDBC connection.</li>
 *   <li>Applies PostgreSQL JDBC batch/prepared-statement tuning ({@code reWriteBatchedInserts=true},
 *       {@code prepareThreshold=3}).</li>
 * </ul>
 */
@Configuration
public class ReadWriteDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ReadWriteDataSourceConfig.class);

    @Bean
    public ReplicaHealthTracker replicaHealthTracker(
            @Value("${elepay.datasource.replica.max-lag-ms:${elepay.datasource.replica-max-lag-ms:2000}}") long maxLagMs,
            @Value("${elepay.datasource.replica.cooldown-ms:15000}") long cooldownMs) {
        return new ReplicaHealthTracker(maxLagMs, Duration.ofMillis(cooldownMs));
    }

    @Bean
    @ConditionalOnProperty(name = "elepay.datasource.read-write-split.enabled", havingValue = "true")
    public CellAwareRoutingDataSource cellAwareRoutingDataSource(
            @Value("${spring.datasource.url}") String primaryUrl,
            @Value("${elepay.datasource.replica-url:}") String replicaUrl,
            @Value("${spring.datasource.username:elepay}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:50}") int maxPoolSize,
            @Value("${spring.datasource.hikari.minimum-idle:10}") int minIdle,
            @Value("${spring.datasource.hikari.leak-detection-threshold:60000}") long leakThreshold,
            @Value("${elepay.datasource.pgbouncer.transaction-pooling:false}") boolean pgBouncerTxMode,
            @Value("${elepay.datasource.ssl-mode:prefer}") String sslMode,
            ReplicaHealthTracker replicaHealthTracker,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {

        HikariDataSource primaryDs = createHikariDataSource(
                "ElepayPrimaryPool", primaryUrl, username, password, maxPoolSize, minIdle, leakThreshold, false,
                pgBouncerTxMode, sslMode);

        HikariDataSource replicaDs = null;
        if (isDistinctReplicaUrl(primaryUrl, replicaUrl)) {
            replicaDs = createHikariDataSource(
                    "ElepayReplicaPool", replicaUrl.trim(), username, password, maxPoolSize, minIdle, leakThreshold, true,
                    pgBouncerTxMode, sslMode);
            log.info("Configured CellAwareRoutingDataSource with distinct primary and read-replica pools for {}",
                    CellDataSourceKey.DEFAULT_CELL_ID);
        } else {
            log.info("Replica URL absent or identical to primary URL; running CellAwareRoutingDataSource in safe single-pool mode for {}",
                    CellDataSourceKey.DEFAULT_CELL_ID);
        }

        CellAwareRoutingDataSource routingDataSource =
                new CellAwareRoutingDataSource(replicaHealthTracker, meterRegistryProvider.getIfAvailable());
        routingDataSource.registerCell(CellDataSourceKey.DEFAULT_CELL_ID, primaryDs, replicaDs);
        routingDataSource.setDefaultTargetDataSource(primaryDs);
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "elepay.datasource.read-write-split.enabled", havingValue = "true")
    public DataSource routingDataSource(CellAwareRoutingDataSource cellAwareRoutingDataSource) {
        return new LazyConnectionDataSourceProxy(cellAwareRoutingDataSource);
    }

    static boolean isDistinctReplicaUrl(String primaryUrl, String replicaUrl) {
        if (replicaUrl == null || replicaUrl.isBlank()) {
            return false;
        }
        if (primaryUrl == null || primaryUrl.isBlank()) {
            return true;
        }
        return !replicaUrl.trim().equals(primaryUrl.trim());
    }

    public static HikariDataSource createHikariDataSource(
            String poolName, String jdbcUrl, String user, String pass,
            int maxPool, int minIdle, long leakThreshold, boolean readOnly) {
        return createHikariDataSource(
                poolName, jdbcUrl, user, pass, maxPool, minIdle, leakThreshold, readOnly, false, "prefer");
    }

    public static HikariDataSource createHikariDataSource(
            String poolName, String jdbcUrl, String user, String pass,
            int maxPool, int minIdle, long leakThreshold, boolean readOnly,
            boolean pgBouncerTxMode, String sslMode) {

        HikariConfig config = buildHikariConfig(
                poolName, jdbcUrl, user, pass, maxPool, minIdle, leakThreshold, readOnly, pgBouncerTxMode, sslMode);
        return new HikariDataSource(config);
    }

    static HikariConfig buildHikariConfig(
            String poolName, String jdbcUrl, String user, String pass,
            int maxPool, int minIdle, long leakThreshold, boolean readOnly) {
        return buildHikariConfig(
                poolName, jdbcUrl, user, pass, maxPool, minIdle, leakThreshold, readOnly, false, "prefer");
    }

    static HikariConfig buildHikariConfig(
            String poolName, String jdbcUrl, String user, String pass,
            int maxPool, int minIdle, long leakThreshold, boolean readOnly,
            boolean pgBouncerTxMode, String sslMode) {

        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(maxPool);
        config.setMinimumIdle(minIdle);
        config.setIdleTimeout(300000);
        config.setConnectionTimeout(20000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(leakThreshold);
        config.setReadOnly(readOnly);
        PgBouncerConnectionPoolManager.applyPgBouncerAndTlsProperties(config, pgBouncerTxMode, sslMode);

        return config;
    }
}
