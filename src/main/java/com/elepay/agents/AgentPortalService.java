package com.elepay.agents;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AgentPortalService {

    private final JdbcClient db;
    private final AgentOnboardingService onboardingService;
    private final AgentFloatService floatService;

    public AgentPortalService(JdbcClient db, AgentOnboardingService onboardingService, AgentFloatService floatService) {
        this.db = db;
        this.onboardingService = onboardingService;
        this.floatService = floatService;
    }

    public record AgentPortalSummary(
            UUID agentId,
            String agentCode,
            String businessName,
            String kycTier,
            long availableFloatPesewas,
            long reservedFloatPesewas,
            long accruedCommissionPesewas,
            long totalLifetimeCommissionPesewas,
            int todayTxnCount,
            long todayTxnVolumePesewas
    ) {}

    public record AgentLedgerEntry(
            UUID id,
            UUID agentId,
            String transactionType,
            String customerIdentifier,
            long amountPesewas,
            long floatBeforePesewas,
            long floatAfterPesewas,
            long agentCommissionPesewas,
            long platformFeePesewas,
            String reference,
            String status,
            OffsetDateTime createdAt
    ) {}

    public AgentPortalSummary getPortalSummary(UUID agentId) {
        var agent = onboardingService.getAgentById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        var floatAcc = floatService.getFloatAccount(agentId)
                .orElseThrow(() -> new IllegalStateException("Float account not found"));

        var stats = db.sql("""
            SELECT count(*) as txn_cnt, coalesce(sum(amount_pesewas), 0) as txn_vol
            FROM agent_transaction
            WHERE agent_id = ? AND created_at >= CURRENT_DATE
        """).param(agentId)
        .query((rs, rowNum) -> new Object() {
            int cnt = rs.getInt("txn_cnt");
            long vol = rs.getLong("txn_vol");
        }).single();

        return new AgentPortalSummary(
                agent.id(), agent.agentCode(), agent.businessName(), agent.kycTier(),
                floatAcc.availableFloatPesewas(), floatAcc.reservedFloatPesewas(),
                floatAcc.accruedCommissionPesewas(), floatAcc.totalLifetimeCommissionPesewas(),
                stats.cnt, stats.vol
        );
    }

    public List<AgentLedgerEntry> getTransactionLedger(UUID agentId, String transactionType, int limit, int offset) {
        String baseSql = """
            SELECT id, agent_id, transaction_type, customer_identifier, amount_pesewas,
                   float_before_pesewas, float_after_pesewas, agent_commission_pesewas,
                   platform_fee_pesewas, reference, status, created_at
            FROM agent_transaction
            WHERE agent_id = ?
        """;

        if (transactionType != null && !transactionType.isBlank()) {
            return db.sql(baseSql + " AND transaction_type = ? ORDER BY created_at DESC LIMIT ? OFFSET ?")
                    .params(agentId, transactionType, limit, offset)
                    .query((rs, rowNum) -> new AgentLedgerEntry(
                            rs.getObject("id", UUID.class),
                            rs.getObject("agent_id", UUID.class),
                            rs.getString("transaction_type"),
                            rs.getString("customer_identifier"),
                            rs.getLong("amount_pesewas"),
                            rs.getLong("float_before_pesewas"),
                            rs.getLong("float_after_pesewas"),
                            rs.getLong("agent_commission_pesewas"),
                            rs.getLong("platform_fee_pesewas"),
                            rs.getString("reference"),
                            rs.getString("status"),
                            rs.getObject("created_at", OffsetDateTime.class)
                    )).list();
        } else {
            return db.sql(baseSql + " ORDER BY created_at DESC LIMIT ? OFFSET ?")
                    .params(agentId, limit, offset)
                    .query((rs, rowNum) -> new AgentLedgerEntry(
                            rs.getObject("id", UUID.class),
                            rs.getObject("agent_id", UUID.class),
                            rs.getString("transaction_type"),
                            rs.getString("customer_identifier"),
                            rs.getLong("amount_pesewas"),
                            rs.getLong("float_before_pesewas"),
                            rs.getLong("float_after_pesewas"),
                            rs.getLong("agent_commission_pesewas"),
                            rs.getLong("platform_fee_pesewas"),
                            rs.getString("reference"),
                            rs.getString("status"),
                            rs.getObject("created_at", OffsetDateTime.class)
                    )).list();
        }
    }
}
