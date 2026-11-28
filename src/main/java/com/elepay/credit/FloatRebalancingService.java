package com.elepay.credit;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 057: Inter-float rebalancing recommendations and automated transfers.
 * Analyzes multi-rail liquidity reserves and recommends rebalancing while enforcing minimum retention safeguards.
 */
@Service
public class FloatRebalancingService {

    private final JdbcClient db;

    public FloatRebalancingService(JdbcClient db) {
        this.db = db;
    }

    public record FloatStatus(String rail, long currentBalancePesewas, long minTargetPesewas, long maxTargetPesewas) {}

    public record RebalanceRecommendation(
            UUID id,
            String sourceRail,
            String targetRail,
            long recommendedAmountPesewas,
            String urgency,
            String reason,
            String status
    ) {}

    @Transactional
    public void upsertFloatAccount(String rail, String accountName, long currentBalancePesewas, long minTargetPesewas, long maxTargetPesewas) {
        db.sql("""
            INSERT INTO float_account (id, rail, account_name, current_balance_pesewas, min_target_pesewas, max_target_pesewas)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (rail) DO UPDATE SET
                current_balance_pesewas = EXCLUDED.current_balance_pesewas,
                min_target_pesewas = EXCLUDED.min_target_pesewas,
                max_target_pesewas = EXCLUDED.max_target_pesewas,
                updated_at = now()
        """).params(UUID.randomUUID(), rail, accountName, currentBalancePesewas, minTargetPesewas, maxTargetPesewas)
        .update();
    }

    /**
     * Evaluates float balances across rails and produces actionable rebalance recommendations.
     */
    @Transactional
    public List<RebalanceRecommendation> evaluateRebalance() {
        List<FloatStatus> accounts = db.sql("SELECT rail, current_balance_pesewas, min_target_pesewas, max_target_pesewas FROM float_account")
                .query((rs, rowNum) -> new FloatStatus(
                        rs.getString("rail"),
                        rs.getLong("current_balance_pesewas"),
                        rs.getLong("min_target_pesewas"),
                        rs.getLong("max_target_pesewas")
                )).list();

        List<RebalanceRecommendation> recommendations = new ArrayList<>();

        List<FloatStatus> deficits = accounts.stream()
                .filter(a -> a.currentBalancePesewas() < a.minTargetPesewas())
                .toList();

        List<FloatStatus> surpluses = accounts.stream()
                .filter(a -> a.currentBalancePesewas() > a.maxTargetPesewas())
                .toList();

        java.util.Map<String, Long> remainingExcessByRail = new java.util.HashMap<>();
        for (FloatStatus surplus : surpluses) {
            remainingExcessByRail.put(surplus.rail(), surplus.currentBalancePesewas() - surplus.maxTargetPesewas());
        }

        for (FloatStatus deficit : deficits) {
            long needed = deficit.minTargetPesewas() - deficit.currentBalancePesewas();

            for (FloatStatus surplus : surpluses) {
                long availableExcess = remainingExcessByRail.getOrDefault(surplus.rail(), 0L);
                if (availableExcess <= 0) continue;

                long transferAmount = Math.min(needed, availableExcess);
                if (transferAmount > 0) {
                    UUID id = UUID.randomUUID();
                    String urgency = (deficit.currentBalancePesewas() < (deficit.minTargetPesewas() / 2)) ? "CRITICAL" : "NORMAL";
                    String reason = String.format("Rebalance %s deficit (balance=%d < min=%d) from %s surplus (balance=%d > max=%d)",
                            deficit.rail(), deficit.currentBalancePesewas(), deficit.minTargetPesewas(),
                            surplus.rail(), surplus.currentBalancePesewas(), surplus.maxTargetPesewas());

                    db.sql("""
                        INSERT INTO float_rebalance_recommendation
                        (id, source_rail, target_rail, recommended_amount_pesewas, urgency, reason, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'RECOMMENDED')
                    """).params(id, surplus.rail(), deficit.rail(), transferAmount, urgency, reason)
                    .update();

                    recommendations.add(new RebalanceRecommendation(id, surplus.rail(), deficit.rail(), transferAmount, urgency, reason, "RECOMMENDED"));
                    remainingExcessByRail.put(surplus.rail(), availableExcess - transferAmount);
                    needed -= transferAmount;
                    if (needed <= 0) break;
                }
            }
        }
        return recommendations;
    }

    @Transactional
    public void executeRebalance(UUID recommendationId, UUID operatorId) {
        RebalanceRecommendation rec = db.sql("""
                SELECT id, source_rail, target_rail, recommended_amount_pesewas, urgency, reason, status
                FROM float_rebalance_recommendation
                WHERE id = ?
                FOR UPDATE
                """)
                .param(recommendationId)
                .query((rs, rowNum) -> new RebalanceRecommendation(
                        rs.getObject("id", UUID.class),
                        rs.getString("source_rail"),
                        rs.getString("target_rail"),
                        rs.getLong("recommended_amount_pesewas"),
                        rs.getString("urgency"),
                        rs.getString("reason"),
                        rs.getString("status")
                )).single();

        if (!"RECOMMENDED".equals(rec.status())) {
            throw new IllegalStateException("Recommendation is already " + rec.status());
        }
        if (rec.recommendedAmountPesewas() <= 0) {
            throw new IllegalArgumentException("Rebalance amount must be positive");
        }

        // Lock source and target float accounts in deterministic alphabetical order to avoid AB-BA deadlocks
        List<String> rails = java.util.stream.Stream.of(rec.sourceRail(), rec.targetRail()).distinct().sorted().toList();
        java.util.Map<String, FloatStatus> locked = new java.util.HashMap<>();
        for (String rail : rails) {
            FloatStatus fs = db.sql("""
                    SELECT rail, current_balance_pesewas, min_target_pesewas, max_target_pesewas
                    FROM float_account
                    WHERE rail = ?
                    FOR UPDATE
                    """)
                    .param(rail)
                    .query((rs, rowNum) -> new FloatStatus(
                            rs.getString("rail"),
                            rs.getLong("current_balance_pesewas"),
                            rs.getLong("min_target_pesewas"),
                            rs.getLong("max_target_pesewas")
                    )).single();
            locked.put(rail, fs);
        }

        FloatStatus source = locked.get(rec.sourceRail());
        if (source.currentBalancePesewas() - rec.recommendedAmountPesewas() < source.minTargetPesewas()) {
            throw new IllegalStateException("Insufficient surplus on source rail " + rec.sourceRail()
                    + " (balance=" + source.currentBalancePesewas() + ", minTarget=" + source.minTargetPesewas()
                    + ", requested=" + rec.recommendedAmountPesewas() + ")");
        }

        int updated = db.sql("""
                UPDATE float_rebalance_recommendation
                SET status = 'EXECUTED', executed_by = ?, executed_at = now()
                WHERE id = ? AND status = 'RECOMMENDED'
                """)
                .params(operatorId, recommendationId).update();
        if (updated != 1) {
            throw new IllegalStateException("Recommendation was concurrently executed");
        }

        // Apply transfer to float accounts
        db.sql("UPDATE float_account SET current_balance_pesewas = current_balance_pesewas - ?, updated_at = now() WHERE rail = ?")
                .params(rec.recommendedAmountPesewas(), rec.sourceRail()).update();

        db.sql("UPDATE float_account SET current_balance_pesewas = current_balance_pesewas + ?, updated_at = now() WHERE rail = ?")
                .params(rec.recommendedAmountPesewas(), rec.targetRail()).update();
    }
}
