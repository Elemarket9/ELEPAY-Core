package com.elepay.fx;

import com.elepay.common.ApiException;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm #061: Corridor routing.
 *
 * <p>Selects the optimal payout or remittance partner route for a corridor based
 * on a multi-criteria scoring function evaluating cost, delivery speed, partner
 * liquidity, and historical transaction success rate.
 */
@Service
public class FxRoutingService {
    private final JdbcClient db;

    public FxRoutingService(JdbcClient db) {
        this.db = db;
    }

    public record PartnerRoute(UUID id, String corridor, String partner, int costBps, long fixedCost,
                                int avgDeliveryMinutes, int liquidityScore, int successRateBps,
                                boolean active, int priority, double compositeScore) {}

    /**
     * Scores and ranks all active partner routes for a given corridor and transaction volume.
     */
    public List<PartnerRoute> rankRoutes(String corridorCode, long sourceAmount) {
        List<PartnerRoute> routes = db.sql("""
                SELECT id, corridor, partner, cost_bps, fixed_cost, avg_delivery_minutes,
                       liquidity_score, success_rate_bps, active, priority
                FROM fx_partner_route
                WHERE corridor = :c AND active = true
                ORDER BY priority, id""")
                .param("c", corridorCode)
                .query((rs, i) -> new PartnerRoute(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getLong(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                        rs.getBoolean(9), rs.getInt(10), 0.0))
                .list();

        if (routes.isEmpty()) {
            return List.of();
        }

        List<PartnerRoute> scored = new ArrayList<>();
        long amt = Math.max(1, sourceAmount);

        for (PartnerRoute r : routes) {
            // 1. Cost score (higher is cheaper/better, 0 to 10,000)
            long totalCostBps = r.costBps() + (r.fixedCost() * 10_000L / amt);
            double costScore = Math.max(0.0, 10_000.0 - totalCostBps);

            // 2. Speed score (shorter delivery time is higher score, up to 10,000)
            double speedScore = Math.min(10_000.0, (10_000.0 * 60.0) / Math.max(1, r.avgDeliveryMinutes()));

            // 3. Liquidity score (0-100 mapped to 0-10,000)
            double liquidityScore = r.liquidityScore() * 100.0;

            // 4. Reliability score (success rate bps: 0-10,000)
            double reliabilityScore = r.successRateBps();

            // Multi-criteria composite score: 30% Cost, 25% Speed, 25% Liquidity, 20% Reliability
            double composite = (0.30 * costScore) + (0.25 * speedScore) + (0.25 * liquidityScore) + (0.20 * reliabilityScore);

            scored.add(new PartnerRoute(r.id(), r.corridor(), r.partner(), r.costBps(), r.fixedCost(),
                    r.avgDeliveryMinutes(), r.liquidityScore(), r.successRateBps(), r.active(), r.priority(),
                    composite));
        }

        // Sort descending by composite score
        scored.sort(Comparator.comparingDouble(PartnerRoute::compositeScore).reversed());
        return scored;
    }

    /**
     * Resolves the optimal partner route for the corridor. Falls back to default partner if no explicit routes configured.
     */
    public String selectOptimalPartner(String corridorCode, long sourceAmount, String defaultPartner) {
        List<PartnerRoute> ranked = rankRoutes(corridorCode, sourceAmount);
        if (ranked.isEmpty()) {
            return defaultPartner;
        }
        return ranked.get(0).partner();
    }
}
