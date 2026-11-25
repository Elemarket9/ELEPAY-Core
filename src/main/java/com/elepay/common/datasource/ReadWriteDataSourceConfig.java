package com.elepay.common.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL High Availability Connection Pooling & Read/Write Split Routing (Domain 15).
 * Dynamically routes read-only transactions to read replicas and write transactions to primary.
 */
@Configuration
public class ReadWriteDataSourceConfig {

    public enum RouteType {
        PRIMARY,
        REPLICA
    }

    public static class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            return isReadOnly ? RouteType.REPLICA : RouteType.PRIMARY;
        }
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "elepay.datasource.read-write-split.enabled", havingValue = "true")
    public DataSource routingDataSource(
            @Value("${spring.datasource.url}") String primaryUrl,
            @Value("${elepay.datasource.replica-url:${spring.datasource.url}}") String replicaUrl,
            @Value("${spring.datasource.username:elepay}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:50}") int maxPoolSize,
            @Value("${spring.datasource.hikari.minimum-idle:10}") int minIdle,
            @Value("${spring.datasource.hikari.leak-detection-threshold:60000}") long leakThreshold) {

        DataSource primaryDs = createHikariDataSource("ElepayPrimaryPool", primaryUrl, username, password, maxPoolSize, minIdle, leakThreshold, false);
        DataSource replicaDs = createHikariDataSource("ElepayReplicaPool", replicaUrl, username, password, maxPoolSize, minIdle, leakThreshold, true);

        ReadWriteRoutingDataSource routingDataSource = new ReadWriteRoutingDataSource();
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(RouteType.PRIMARY, primaryDs);
        targetDataSources.put(RouteType.REPLICA, replicaDs);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(primaryDs);
        routingDataSource.afterPropertiesSet();

        return new LazyConnectionDataSourceProxy(routingDataSource);
    }

    private static DataSource createHikariDataSource(
            String poolName, String jdbcUrl, String user, String pass,
            int maxPool, int minIdle, long leakThreshold, boolean readOnly) {

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
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        return new HikariDataSource(config);
    }
}
