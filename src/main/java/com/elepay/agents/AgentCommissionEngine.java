package com.elepay.agents;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentCommissionEngine {

    private final JdbcClient db;

    public AgentCommissionEngine(JdbcClient db) {
        this.db = db;
    }

    public record CommissionCalculation(
            long grossCommissionPesewas,
            long agentSharePesewas,
            long platformSharePesewas,
            int commissionRateBps,
            int platformShareBps
    ) {}

    public record DailyPayoutSummary(
            int agentsPaid,
            long totalCommissionPaidPesewas
    ) {}

    public CommissionCalculation calculateCommission(String kycTier, String transactionType, long amountPesewas) {
        return db.sql("""
            SELECT commission_rate_bps, fixed_commission_pesewas, platform_share_bps
            FROM agent_commission_tier
            WHERE kyc_tier = ? AND transaction_type = ?
              AND min_amount_pesewas <= ? AND max_amount_pesewas >= ?
            LIMIT 1
        """).params(kycTier, transactionType, amountPesewas, amountPesewas)
        .query((rs, rowNum) -> {
            int rateBps = rs.getInt("commission_rate_bps");
            long fixed = rs.getLong("fixed_commission_pesewas");
            int platBps = rs.getInt("platform_share_bps");

            long variable = Math.round(amountPesewas * (rateBps / 10000.0));
            long gross = fixed + variable;

            long platformShare = Math.round(gross * (platBps / 10000.0));
            long agentShare = gross - platformShare;

            return new CommissionCalculation(gross, agentShare, platformShare, rateBps, platBps);
        }).optional().orElseGet(() -> {
            // Default fallback calculation: 50 bps, 80/20 split
            long gross = Math.max(50L, Math.round(amountPesewas * 0.0050));
            long plat = Math.round(gross * 0.20);
            return new CommissionCalculation(gross, gross - plat, plat, 50, 2000);
        });
    }

    @Transactional
    public DailyPayoutSummary executeDailyCommissionPayouts() {
        var agentsWithCommission = db.sql("""
            SELECT id, agent_id, available_float_pesewas, accrued_commission_pesewas
            FROM agent_float_account
            WHERE accrued_commission_pesewas > 0
            FOR UPDATE
        """).query((rs, rowNum) -> new Object() {
            UUID floatId = rs.getObject("id", UUID.class);
            UUID agentId = rs.getObject("agent_id", UUID.class);
            long available = rs.getLong("available_float_pesewas");
            long accrued = rs.getLong("accrued_commission_pesewas");
        }).list();

        long totalPaid = 0;
        int count = 0;

        for (var a : agentsWithCommission) {
            long accrued = a.accrued;
            long before = a.available;
            long after = before + accrued;

            db.sql("""
                UPDATE agent_float_account
                SET available_float_pesewas = available_float_pesewas + ?,
                    total_lifetime_commission_pesewas = total_lifetime_commission_pesewas + ?,
                    accrued_commission_pesewas = 0,
                    updated_at = now()
                WHERE id = ?
            """).params(accrued, accrued, a.floatId).update();

            UUID txnId = UUID.randomUUID();
            String ref = "COMM-PAY-" + txnId.toString().substring(0, 8).toUpperCase();

            db.sql("""
                INSERT INTO agent_transaction
                (id, agent_id, transaction_type, customer_identifier, amount_pesewas,
                 float_before_pesewas, float_after_pesewas, agent_commission_pesewas,
                 platform_fee_pesewas, reference, status)
                VALUES (?, ?, 'COMMISSION_PAYOUT', 'AGENT_COMMISSION', ?, ?, ?, ?, 0, ?, 'COMPLETED')
            """).params(txnId, a.agentId, accrued, before, after, accrued, ref).update();

            totalPaid += accrued;
            count++;
        }

        return new DailyPayoutSummary(count, totalPaid);
    }
}
