package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Algorithm 032: Mule-account graph detection.
 * Analyzes transaction relationship graphs and fund flows to identify pass-through money mules,
 * funneling patterns (high in-degree to single consolidation), and rapid drain velocity.
 */
@Service
public class MuleGraphService {

    private final JdbcClient db;

    public MuleGraphService(JdbcClient db) {
        this.db = db;
    }

    public record TransactionFlow(
            String sourceAccountId,
            String destinationAccountId,
            long amountMinorUnits,
            String currency,
            OffsetDateTime transferredAt
    ) {}

    public record MuleNodeAssessment(
            String accountId,
            int inDegree,
            int outDegree,
            long totalInflowMinorUnits,
            long totalOutflowMinorUnits,
            double drainRatio,
            int rapidPassThroughCount,
            int muleScore,
            boolean isMuleSuspect,
            List<String> suspicionFactors
    ) {}

    @Transactional
    public MuleNodeAssessment recordFlowAndAssess(TransactionFlow flow) {
        OffsetDateTime now = flow.transferredAt() != null ? flow.transferredAt() : OffsetDateTime.now();

        // 1. Record Edge
        db.sql("""
            INSERT INTO risk_mule_edges (id, source_account_id, destination_account_id, amount_minor_units, currency, transferred_at)
            VALUES (:id, :src, :dst, :amt, :curr, :now)
        """)
                .param("id", UUID.randomUUID())
                .param("src", flow.sourceAccountId())
                .param("dst", flow.destinationAccountId())
                .param("amt", flow.amountMinorUnits())
                .param("curr", flow.currency())
                .param("now", now)
                .update();

        // Check source account: Did it recently receive funds within 15 minutes that it is now sending out?
        OffsetDateTime window15m = now.minusMinutes(15);
        Integer recentInflows = db.sql("""
            SELECT COUNT(*) FROM risk_mule_edges
            WHERE destination_account_id = :src
              AND transferred_at >= :window
        """)
                .param("src", flow.sourceAccountId())
                .param("window", window15m)
                .query(Integer.class)
                .single();

        boolean rapidDrain = (recentInflows != null && recentInflows > 0);

        // Assess the source account
        return assessAccount(flow.sourceAccountId(), rapidDrain, now);
    }

    @Transactional
    public MuleNodeAssessment assessAccount(String accountId, boolean incrementRapidDrain, OffsetDateTime now) {
        // Query in-degree & total inflow
        var inStats = db.sql("""
            SELECT COUNT(DISTINCT source_account_id) AS in_degree,
                   COALESCE(SUM(amount_minor_units), 0) AS total_inflow
            FROM risk_mule_edges
            WHERE destination_account_id = :acc
        """)
                .param("acc", accountId)
                .query((rs, rowNum) -> new Object() {
                    int inDegree = rs.getInt("in_degree");
                    long totalInflow = rs.getLong("total_inflow");
                })
                .single();

        // Query out-degree & total outflow
        var outStats = db.sql("""
            SELECT COUNT(DISTINCT destination_account_id) AS out_degree,
                   COALESCE(SUM(amount_minor_units), 0) AS total_outflow
            FROM risk_mule_edges
            WHERE source_account_id = :acc
        """)
                .param("acc", accountId)
                .query((rs, rowNum) -> new Object() {
                    int outDegree = rs.getInt("out_degree");
                    long totalOutflow = rs.getLong("total_outflow");
                })
                .single();

        // Query existing rapid drain count
        Integer currentRapidDrain = db.sql("""
            SELECT rapid_passthrough_count FROM risk_mule_nodes WHERE account_id = :acc
        """)
                .param("acc", accountId)
                .query(Integer.class)
                .optional()
                .orElse(0);

        int rapidCount = currentRapidDrain + (incrementRapidDrain ? 1 : 0);

        double drainRatio = inStats.totalInflow > 0
                ? (double) outStats.totalOutflow / (double) inStats.totalInflow
                : 0.0;

        List<String> factors = new ArrayList<>();
        int score = 0;

        // Drain ratio evaluation: mules retain very little money
        if (inStats.totalInflow >= 100_000L) { // at least 1,000 GHS moved
            if (drainRatio >= 0.95) {
                score += 35;
                factors.add(String.format("EXTREME_DRAIN_RATIO (%.1f%% of inflow transferred out immediately)", drainRatio * 100));
            } else if (drainRatio >= 0.85) {
                score += 20;
                factors.add(String.format("HIGH_DRAIN_RATIO (%.1f%% of inflow transferred out)", drainRatio * 100));
            }
        }

        // Funnel pattern: high in-degree to low out-degree
        if (inStats.inDegree >= 4 && outStats.outDegree <= 2 && outStats.totalOutflow >= 50_000L) {
            score += 35;
            factors.add(String.format("FUNNEL_CONSOLIDATION_PATTERN (In-degree %d -> Out-degree %d)", inStats.inDegree, outStats.outDegree));
        }

        // Rapid pass-through velocity
        if (rapidCount >= 2) {
            score += 35;
            factors.add("REPEATED_RAPID_PASSTHROUGH_WITHIN_15_MINUTES (" + rapidCount + " occurrences)");
        } else if (rapidCount == 1) {
            score += 20;
            factors.add("RAPID_PASSTHROUGH_WITHIN_15_MINUTES");
        }

        int muleScore = Math.min(100, score);
        boolean isSuspect = muleScore >= 50;

        // Upsert node
        db.sql("""
            INSERT INTO risk_mule_nodes (
                id, account_id, in_degree, out_degree, total_inflow_minor_units,
                total_outflow_minor_units, drain_ratio, rapid_passthrough_count,
                mule_score, is_mule_suspect, last_assessed_at
            ) VALUES (
                :id, :acc, :inDeg, :outDeg, :inflow,
                :outflow, :drain, :rapid,
                :score, :suspect, :now
            )
            ON CONFLICT (account_id) DO UPDATE SET
                in_degree = EXCLUDED.in_degree,
                out_degree = EXCLUDED.out_degree,
                total_inflow_minor_units = EXCLUDED.total_inflow_minor_units,
                total_outflow_minor_units = EXCLUDED.total_outflow_minor_units,
                drain_ratio = EXCLUDED.drain_ratio,
                rapid_passthrough_count = EXCLUDED.rapid_passthrough_count,
                mule_score = EXCLUDED.mule_score,
                is_mule_suspect = EXCLUDED.is_mule_suspect,
                last_assessed_at = EXCLUDED.last_assessed_at
        """)
                .param("id", UUID.randomUUID())
                .param("acc", accountId)
                .param("inDeg", inStats.inDegree)
                .param("outDeg", outStats.outDegree)
                .param("inflow", inStats.totalInflow)
                .param("outflow", outStats.totalOutflow)
                .param("drain", drainRatio)
                .param("rapid", rapidCount)
                .param("score", muleScore)
                .param("suspect", isSuspect)
                .param("now", now)
                .update();

        return new MuleNodeAssessment(
                accountId,
                inStats.inDegree,
                outStats.outDegree,
                inStats.totalInflow,
                outStats.totalOutflow,
                drainRatio,
                rapidCount,
                muleScore,
                isSuspect,
                factors
        );
    }
}
