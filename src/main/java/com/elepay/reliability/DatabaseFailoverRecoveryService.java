package com.elepay.reliability;

import java.sql.SQLException;
import java.util.Set;
import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.function.Predicate;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * Algorithm 094: Database failover recovery.
 * Handles lost connections, PostgreSQL primary-to-standby failovers, and transient disconnects.
 * Connection failures can leave commit outcomes unknown. Replay requires an explicit\n * caller-supplied safety decision; exception classification never proves replay safety.
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
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public boolean isFailoverException(Throwable throwable) {
        Throwable curr = throwable;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (curr != null && visited.add(curr)) {
            if (curr instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if (state != null && FAILOVER_SQL_STATES.contains(state)) {
                    return true;
                }
            }
            curr = curr.getCause();
        }
        return false;
    }

    /**
     * Conservative compatibility entry point: an uncertain action is not replayed.
     * Use the explicit overload only when durable idempotency or authoritative
     * rollback/reconciliation evidence establishes that repeating the whole action
     * cannot duplicate financial effects.
     */
    public <T> T executeWithFailoverRetry(Callable<T> action, int maxRetries,
                                         long backoffMs) throws Exception {
        return executeWithFailoverRetry(action, maxRetries, backoffMs, failure -> false);
    }

    /**
     * A fresh transaction must be created inside each action invocation. Do not
     * reuse a failed transaction or retry external side effects without their own
     * idempotency boundary. The safety predicate must fail closed on uncertainty.
     */
    public <T> T executeWithFailoverRetry(Callable<T> action, int maxRetries,
                                         long backoffMs,
                                         Predicate<Exception> safeToReplay) throws Exception {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(safeToReplay, "safeToReplay");
        if (maxRetries < 0 || backoffMs < 0
                || (maxRetries > 0 && backoffMs > Long.MAX_VALUE / maxRetries)) {
            throw new IllegalArgumentException("Invalid retry count or delay");
        }
        int retries = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Database recovery interrupted");
            }
            try {
                return action.call();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception failure) {
                if (retries >= maxRetries || Thread.currentThread().isInterrupted()
                        || !isFailoverException(failure) || !safeToReplay.test(failure)) {
                    throw failure;
                }
                retries++;
                try {
                    Thread.sleep(backoffMs * retries);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
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
