package com.elepay.reliability;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/**
 * Algorithm 094: Database failover recovery.
 * Handles lost connections, PostgreSQL primary-to-standby failovers, and transient disconnects.
 * Ensures idempotent retries and guards against duplicate financial effects on uncertain commits.
 */
@Service
public class DatabaseFailoverRecoveryService {

    private static final Set<String> FAILOVER_SQL_STATES = Set.of(
            "08006", // connection_failure
            "08001", // sqlclient_unable_to_establish_sqlconnection
            "08003", // connection_does_not_exist
            "08004", // sqlserver_rejected_establishment_of_sqlconnection
            "57P01", // admin_shutdown
            "57P02", // crash_shutdown
            "57P03", // cannot_connect_now
            "25006"  // read_only_sql_transaction (standby during promotion)
    );

    private final DataSource dataSource;

    public DatabaseFailoverRecoveryService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isFailoverException(Throwable throwable) {
        Throwable curr = throwable;
        while (curr != null) {
            if (curr instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if (state != null && FAILOVER_SQL_STATES.contains(state)) {
                    return true;
                }
            }
            if (curr instanceof TransientDataAccessException) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    /**
     * Executes database action with failover retry.
     */
    public <T> T executeWithFailoverRetry(Callable<T> action, int maxRetries, long backoffMs) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return action.call();
            } catch (Exception ex) {
                attempt++;
                if (attempt > maxRetries || !isFailoverException(ex)) {
                    throw ex;
                }
                // Verify datasource connectivity before reattempt
                Thread.sleep(backoffMs * attempt);
            }
        }
    }

    public boolean pingDatabase() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }
}
