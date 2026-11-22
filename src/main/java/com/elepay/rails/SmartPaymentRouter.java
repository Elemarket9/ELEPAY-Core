package com.elepay.rails;

import com.elepay.common.ApiException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm 014: Smart payment routing.
 * Selects optimal payment rail across MTN, Telecel, AirtelTigo, GHIPSS (banks), and Card
 * based on channel capability, cost (bps & fixed fee), circuit health, and measured success rate.
 */
@Service
public class SmartPaymentRouter {
    private static final Logger log = LoggerFactory.getLogger(SmartPaymentRouter.class);

    public record RouteConfig(
            UUID id,
            String channel,
            String network,
            String primaryRail,
            String secondaryRail,
            int costBps,
            long costFixed,
            int priority,
            boolean enabled
    ) {}

    public record SelectedRoute(
            String rail,
            String channel,
            String network,
            long estimatedFee,
            double successRate,
            NetworkHealthDetector.CircuitState healthState,
            String secondaryRail,
            String reason
    ) {}

    private final JdbcClient db;
    private final NetworkHealthDetector healthDetector;
    private final RailRegistry rails;

    public SmartPaymentRouter(
            JdbcClient db,
            NetworkHealthDetector healthDetector,
            RailRegistry rails
    ) {
        this.db = db;
        this.healthDetector = healthDetector;
        this.rails = rails;
    }

    /**
     * Resolves the best available rail for a transaction.
     */
    public SelectedRoute selectRoute(String channel, String network, long amount, String currency) {
        String cleanChannel = channel != null ? channel.toLowerCase(Locale.ROOT) : "momo";
        String cleanNetwork = network != null ? network.toLowerCase(Locale.ROOT) : "mtn";

        List<RouteConfig> configs = loadConfigs(cleanChannel, cleanNetwork);
        if (configs.isEmpty()) {
            // Default fallback
            String railName = defaultRailFor(cleanChannel, cleanNetwork);
            if (!rails.names().contains(railName) || !healthDetector.isRailAvailable(railName))
                throw ApiException.unprocessable("rail_unavailable", "No operational payment connection for this route");
            return new SelectedRoute(railName, cleanChannel, cleanNetwork, 0L, 0.0,
                    healthDetector.getHealth(railName).circuitState(), null, "configured_routing");
        }

        // Rank candidates
        RouteConfig best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        String selectionReason = "optimal_health_and_cost";

        for (RouteConfig cfg : configs) {
            String primary = cfg.primaryRail();
            var health = healthDetector.getHealth(primary);
            boolean available = rails.names().contains(primary) && healthDetector.isRailAvailable(primary);

            if (!available) {
                log.warn("Primary rail {} for {}/{} is unavailable (circuit OPEN)", primary, cleanChannel, cleanNetwork);
                continue;
            }

            // Success rate (0.0 to 1.0)
            double successRate = health.totalRequests() == 0 ? 1.0 : (1.0 - health.errorRate());
            long estimatedFee = Math.addExact(Math.multiplyExact(amount, cfg.costBps()) / 10000L, cfg.costFixed());

            // Scoring: prioritize high success rate, then lower cost, then configured priority
            double score = (successRate * 100.0) - (cfg.costBps() / 100.0) + (cfg.priority() * 10.0);

            if (score > bestScore) {
                bestScore = score;
                best = cfg;
            }
        }

        if (best == null) {
            // All primary routes are down, check if secondary is healthy
            RouteConfig first = (configs != null && !configs.isEmpty()) ? configs.get(0) : null;
            if (first != null && first.secondaryRail() != null && rails.names().contains(first.secondaryRail()) && healthDetector.isRailAvailable(first.secondaryRail())) {
                log.info("Failing over routing to healthy secondary rail {}", first.secondaryRail());
                var h = healthDetector.getHealth(first.secondaryRail());
                return new SelectedRoute(
                        first.secondaryRail(), cleanChannel, cleanNetwork,
                        Math.addExact(Math.multiplyExact(amount, first.costBps()) / 10000L, first.costFixed()),
                        1.0 - h.errorRate(), h.circuitState(), null, "primary_circuit_open_fallback"
                );
            }
            throw ApiException.unprocessable("rail_unavailable", "No healthy operational payment connection for this route");
        }

        var h = healthDetector.getHealth(best.primaryRail());
        long fee = Math.addExact(Math.multiplyExact(amount, best.costBps()) / 10000L, best.costFixed());
        return new SelectedRoute(
                best.primaryRail(), cleanChannel, cleanNetwork, fee,
                1.0 - h.errorRate(), h.circuitState(), best.secondaryRail(), selectionReason
        );
    }

    public List<RouteConfig> loadConfigs(String channel, String network) {
        try {
            var list = db.sql("""
                    SELECT id, channel, network, primary_rail, secondary_rail, cost_bps, cost_fixed, priority, enabled
                    FROM rail_route_config
                    WHERE enabled = true AND channel = :ch AND network = :net
                    ORDER BY priority ASC""")
                    .param("ch", channel)
                    .param("net", network)
                    .query(this::mapConfig)
                    .list();
            if (list != null && !list.isEmpty() && list.get(0) != null) return list;
        } catch (Exception e) {
            log.warn("Could not query rail_route_config: {}", e.getMessage());
        }
        return defaultConfigsFor(channel, network);
    }

    private List<RouteConfig> defaultConfigsFor(String channel, String network) {
        if ("momo".equals(channel) && "mtn".equals(network)) {
            return List.of(new RouteConfig(UUID.randomUUID(), "momo", "mtn", "mtn", null, 100, 0, 1, true));
        }
        if ("momo".equals(channel) && "telecel".equals(network)) {
            return List.of(new RouteConfig(UUID.randomUUID(), "momo", "telecel", "telecel", null, 75, 0, 1, true));
        }
        if ("momo".equals(channel) && "airteltigo".equals(network)) {
            return List.of(new RouteConfig(UUID.randomUUID(), "momo", "airteltigo", "airteltigo", null, 50, 0, 1, true));
        }
        if ("bank".equals(channel)) {
            return List.of(new RouteConfig(UUID.randomUUID(), "bank", "ghipss", "ghipss", null, 0, 50, 1, true));
        }
        if ("card".equals(channel)) {
            return List.of(new RouteConfig(UUID.randomUUID(), "card", "card", "card", null, 150, 0, 1, true));
        }
        return List.of();
    }

    private String defaultRailFor(String channel, String network) {
        if ("card".equals(channel)) return "card";
        if ("bank".equals(channel)) return "ghipss";
        return network;
    }

    private RouteConfig mapConfig(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new RouteConfig(
                rs.getObject("id", UUID.class),
                rs.getString("channel"),
                rs.getString("network"),
                rs.getString("primary_rail"),
                rs.getString("secondary_rail"),
                rs.getInt("cost_bps"),
                rs.getLong("cost_fixed"),
                rs.getInt("priority"),
                rs.getBoolean("enabled")
        );
    }
}
