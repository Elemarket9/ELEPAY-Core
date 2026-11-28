package com.elepay.credit;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 069: Repayment scheduling and sweeps.
 * Automatically sweeps consented percentages from settled merchant checkout proceeds.
 * Enforces deduplication on settlement batch ID to prevent double-sweeps.
 */
@Service
public class RepaymentSweepsService {

    private final JdbcClient db;

    public RepaymentSweepsService(JdbcClient db) {
        this.db = db;
    }

    public record SweepPlan(
            UUID id,
            UUID mcaOfferId,
            UUID merchantId,
            long targetAmountPesewas,
            long collectedAmountPesewas,
            int sweepPercentageBps,
            String status
    ) {}

    public record SweepExecutionResult(
            UUID executionId,
            UUID planId,
            UUID settlementBatchId,
            long settlementAmountPesewas,
            long sweptAmountPesewas,
            long remainingBalancePesewas,
            String status
    ) {}

    @Transactional
    public SweepPlan createPlan(UUID mcaOfferId, UUID merchantId, long targetAmountPesewas, int sweepPercentageBps) {
        if (targetAmountPesewas <= 0) {
            throw new IllegalArgumentException("targetAmountPesewas must be positive");
        }
        if (sweepPercentageBps <= 0 || sweepPercentageBps > 10000) {
            throw new IllegalArgumentException("sweepPercentageBps must be between 1 and 10000");
        }
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO repayment_sweep_plan
            (id, mca_offer_id, merchant_id, target_amount_pesewas, collected_amount_pesewas, sweep_percentage_bps, status, start_date)
            VALUES (?, ?, ?, ?, 0, ?, 'ACTIVE', CURRENT_DATE)
        """).params(id, mcaOfferId, merchantId, targetAmountPesewas, sweepPercentageBps)
        .update();

        return new SweepPlan(id, mcaOfferId, merchantId, targetAmountPesewas, 0L, sweepPercentageBps, "ACTIVE");
    }

    @Transactional
    public SweepExecutionResult executeSweep(UUID planId, UUID settlementBatchId, long settlementAmountPesewas) {
        if (settlementAmountPesewas <= 0) {
            throw new IllegalArgumentException("settlementAmountPesewas must be positive");
        }
        SweepPlan plan = db.sql("SELECT id, mca_offer_id, merchant_id, target_amount_pesewas, collected_amount_pesewas, sweep_percentage_bps, status FROM repayment_sweep_plan WHERE id = ? FOR UPDATE")
                .param(planId)
                .query((rs, rowNum) -> new SweepPlan(
                        rs.getObject("id", UUID.class),
                        rs.getObject("mca_offer_id", UUID.class),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getLong("target_amount_pesewas"),
                        rs.getLong("collected_amount_pesewas"),
                        rs.getInt("sweep_percentage_bps"),
                        rs.getString("status")
                )).single();

        if (!"ACTIVE".equals(plan.status())) {
            return new SweepExecutionResult(null, planId, settlementBatchId, settlementAmountPesewas, 0L, 0L, "SKIPPED_PLAN_NOT_ACTIVE");
        }

        long remaining = Math.subtractExact(plan.targetAmountPesewas(), plan.collectedAmountPesewas());
        if (remaining <= 0) {
            db.sql("UPDATE repayment_sweep_plan SET status = 'COMPLETED', completion_date = CURRENT_DATE WHERE id = ?")
                    .param(planId).update();
            return new SweepExecutionResult(null, planId, settlementBatchId, settlementAmountPesewas, 0L, 0L, "COMPLETED");
        }

        // Exact integer math: min(remaining, settlement * sweepBps / 10000)
        long calculatedSweep = java.math.BigInteger.valueOf(settlementAmountPesewas)
                .multiply(java.math.BigInteger.valueOf(plan.sweepPercentageBps()))
                .divide(java.math.BigInteger.valueOf(10_000L))
                .longValueExact();
        long actualSweep = Math.min(remaining, calculatedSweep);

        UUID executionId = UUID.randomUUID();
        db.sql("""
            INSERT INTO repayment_sweep_execution
            (id, plan_id, settlement_batch_id, settlement_amount_pesewas, swept_amount_pesewas, status)
            VALUES (?, ?, ?, ?, ?, 'EXECUTED')
        """).params(executionId, planId, settlementBatchId, settlementAmountPesewas, actualSweep)
        .update();

        long newCollected = Math.addExact(plan.collectedAmountPesewas(), actualSweep);
        long newRemaining = Math.subtractExact(plan.targetAmountPesewas(), newCollected);
        String newStatus = newRemaining == 0 ? "COMPLETED" : "ACTIVE";

        db.sql("""
            UPDATE repayment_sweep_plan
            SET collected_amount_pesewas = ?,
                status = ?,
                completion_date = CASE WHEN ? = 'COMPLETED' THEN CURRENT_DATE ELSE completion_date END
            WHERE id = ?
        """).params(newCollected, newStatus, newStatus, planId)
        .update();

        return new SweepExecutionResult(executionId, planId, settlementBatchId, settlementAmountPesewas, actualSweep, newRemaining, "EXECUTED");
    }
}
