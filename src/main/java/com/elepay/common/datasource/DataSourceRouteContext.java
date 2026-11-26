package com.elepay.common.datasource;

/**
 * Virtual-thread-safe {@link ThreadLocal} routing context for {@link CellAwareRoutingDataSource}.
 *
 * <p>Holds per-request/per-thread cell affinity, optional explicit route override, and the
 * Read-Your-Own-Writes (RYOW) sticky-primary flag. Uses plain {@link ThreadLocal} (never
 * {@link InheritableThreadLocal}) without eager initial-value allocation so virtual threads that
 * never bind routing state incur zero ThreadLocalMap overhead.
 *
 * <p>Callers (such as HTTP filters or async workers) MUST invoke {@link #clear()} in a
 * {@code finally} block to prevent state leakage across reused platform threads.
 */
public final class DataSourceRouteContext {

    public static final String DEFAULT_CELL_ID = CellDataSourceKey.DEFAULT_CELL_ID;

    private static final ThreadLocal<String> CELL_ID = new ThreadLocal<>();
    private static final ThreadLocal<RouteType> ROUTE_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PRIMARY_WRITTEN = new ThreadLocal<>();

    private DataSourceRouteContext() {}

    /**
     * Returns the active cell identifier for the current thread, defaulting to {@value #DEFAULT_CELL_ID}.
     */
    public static String getCellId() {
        String cellId = CELL_ID.get();
        return (cellId == null || cellId.isBlank()) ? DEFAULT_CELL_ID : cellId;
    }

    /**
     * Binds the active cell identifier for the current thread. Passing {@code null} or blank
     * clears the binding back to {@value #DEFAULT_CELL_ID}.
     */
    public static void setCellId(String cellId) {
        if (cellId == null || cellId.isBlank()) {
            CELL_ID.remove();
        } else {
            CELL_ID.set(cellId.trim());
        }
    }

    /**
     * Returns the explicit {@link RouteType} override for the current thread, or {@code null} if unset.
     */
    public static RouteType getRouteOverride() {
        return ROUTE_OVERRIDE.get();
    }

    /**
     * Sets or clears an explicit {@link RouteType} override for the current thread.
     */
    public static void setRouteOverride(RouteType routeType) {
        if (routeType == null) {
            ROUTE_OVERRIDE.remove();
        } else {
            ROUTE_OVERRIDE.set(routeType);
        }
    }

    /**
     * Marks that the current request/thread has written to the primary database, forcing subsequent
     * reads on this thread to stick to {@link RouteType#PRIMARY} (Read-Your-Own-Writes).
     */
    public static void markPrimaryWritten() {
        PRIMARY_WRITTEN.set(Boolean.TRUE);
    }

    /**
     * Returns {@code true} if {@link #markPrimaryWritten()} was called on this thread since the
     * last {@link #clear()}.
     */
    public static boolean isPrimaryWritten() {
        return Boolean.TRUE.equals(PRIMARY_WRITTEN.get());
    }

    /**
     * Removes all ThreadLocal routing entries for the current thread.
     */
    public static void clear() {
        CELL_ID.remove();
        ROUTE_OVERRIDE.remove();
        PRIMARY_WRITTEN.remove();
    }
}
