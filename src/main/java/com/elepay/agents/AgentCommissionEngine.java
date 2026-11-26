package com.elepay.agents;

import com.elepay.ledger.Ledger;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentCommissionEngine {

    private final JdbcClient db;
    private final Ledger ledger;

    public AgentCommissionEngine(JdbcClient db, Ledger ledger) {
        this.db = db;
        this.ledger = ledger;
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

    record FloatRow(UUID floatId, UUID agentId, long available, long accrued) {}

    public CommissionCalculation calculateCommission(String kycTier, String transactionType, long amountPesewas) {
        if (db == null) {
            long gross = Math.max(50L, BigInteger.valueOf(amountPesewas)
                    .multiply(BigInteger.valueOf(50L))
                    .divide(BigInteger.valueOf(10000L)).longValueExact());
            long plat = BigInteger.valueOf(gross)
                    .multiply(BigInteger.valueOf(20L))
                    .divide(BigInteger.valueOf(100L)).longValueExact();
            return new CommissionCalculation(gross, gross - plat, plat, 50, 2000);
        }
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

            long variable = BigInteger.valueOf(amountPesewas)
                    .multiply(BigInteger.valueOf(rateBps))
                    .divide(BigInteger.valueOf(10000L)).longValueExact();
            long gross = fixed + variable;

            long platformShare = BigInteger.valueOf(gross)
                    .multiply(BigInteger.valueOf(platBps))
                    .divide(BigInteger.valueOf(10000L)).longValueExact();
            long agentShare = gross - platformShare;

            return new CommissionCalculation(gross, agentShare, platformShare, rateBps, platBps);
        }).optional().orElseGet(() -> {
            // Default fallback calculation: 50 bps, 80/20 split
            long gross = Math.max(50L, BigInteger.valueOf(amountPesewas)
                    .multiply(BigInteger.valueOf(50L))
                    .divide(BigInteger.valueOf(10000L)).longValueExact());
            long plat = BigInteger.valueOf(gross)
                    .multiply(BigInteger.valueOf(20L))
                    .divide(BigInteger.valueOf(100L)).longValueExact();
            return new CommissionCalculation(gross, gross - plat, plat, 50, 2000);
        });
    }

    @Transactional
    public DailyPayoutSummary executeDailyCommissionPayouts() {
        List<FloatRow> agentsWithCommission = db.sql("""
            SELECT id, agent_id, available_float_pesewas, accrued_commission_pesewas
            FROM agent_float_account
            WHERE accrued_commission_pesewas > 0
            FOR UPDATE
        """).query((rs, rowNum) -> new FloatRow(
                rs.getObject("id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getLong("available_float_pesewas"),
                rs.getLong("accrued_commission_pesewas")
        )).list();

        long totalPaid = 0;
        int count = 0;

        for (var a : agentsWithCommission) {
            long accrued = a.accrued();
            long before = a.available();
            long after = before + accrued;

            String agentFloat = ledger.open("merchant", a.agentId(), "available", Ledger.GHS);
            UUID txnId = UUID.randomUUID();
            String ref = "COMM-PAY-" + txnId.toString().substring(0, 8).toUpperCase();

            List<Ledger.Leg> legs = new ArrayList<>(ledger.debitAcrossStripes(Ledger.REVENUE, accrued));
            legs.add(Ledger.Leg.credit(agentFloat, accrued));
            var posted = ledger.post(Ledger.Posting.of("agent-commission", ref, "agent_commission_payout", legs));

            db.sql("""
                UPDATE agent_float_account
                SET available_float_pesewas = available_float_pesewas + ?,
                    total_lifetime_commission_pesewas = total_lifetime_commission_pesewas + ?,
                    accrued_commission_pesewas = 0,
                    updated_at = now()
                WHERE id = ?
            """).params(accrued, accrued, a.floatId()).update();

            db.sql("""
                INSERT INTO agent_transaction
                (id, agent_id, transaction_type, customer_identifier, amount_pesewas,
                 float_before_pesewas, float_after_pesewas, agent_commission_pesewas,
                 platform_fee_pesewas, reference, status, journal_tx)
                VALUES (?, ?, 'COMMISSION_PAYOUT', 'AGENT_COMMISSION', ?, ?, ?, ?, 0, ?, 'COMPLETED', ?)
            """).params(txnId, a.agentId(), accrued, before, after, accrued, ref, posted.transactionId()).update();

            totalPaid += accrued;
            count++;
        }

        return new DailyPayoutSummary(count, totalPaid);
    }
}
