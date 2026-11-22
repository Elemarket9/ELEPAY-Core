package com.elepay.agents;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentCashInService {

    private final JdbcClient db;
    private final AgentConfigService configService;
    private final AgentOnboardingService onboardingService;
    private final AgentFloatService floatService;
    private final AgentCommissionEngine commissionEngine;

    public AgentCashInService(
            JdbcClient db,
            AgentConfigService configService,
            AgentOnboardingService onboardingService,
            AgentFloatService floatService,
            AgentCommissionEngine commissionEngine
    ) {
        this.db = db;
        this.configService = configService;
        this.onboardingService = onboardingService;
        this.floatService = floatService;
        this.commissionEngine = commissionEngine;
    }

    public record CashInRequest(
            UUID agentId,
            String agentPin,
            String customerPhoneOrAccount,
            long amountPesewas,
            boolean isSimulation
    ) {}

    public record CashInReceipt(
            UUID transactionId,
            String reference,
            UUID agentId,
            String customerIdentifier,
            long amountPesewas,
            long agentCommissionPesewas,
            long remainingFloatPesewas,
            String status
    ) {}

    @Transactional
    public CashInReceipt processCashIn(CashInRequest req) {
        configService.assertPermitted(req.isSimulation());

        var agent = onboardingService.getAgentById(req.agentId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + req.agentId()));

        if (!"ACTIVE".equals(agent.status())) {
            throw new IllegalStateException("Agent is not active: " + agent.status());
        }

        if (!onboardingService.verifyPin(req.agentId(), req.agentPin())) {
            throw new IllegalArgumentException("Invalid agent authorization PIN");
        }

        if (req.amountPesewas() <= 0) {
            throw new IllegalArgumentException("Cash-in amount must be greater than zero");
        }

        if (req.amountPesewas() > agent.maxSingleCashInPesewas()) {
            throw new IllegalArgumentException("Cash-in amount (" + req.amountPesewas()
                    + ") exceeds agent single transaction limit (" + agent.maxSingleCashInPesewas() + ")");
        }

        var floatAcc = floatService.getFloatAccount(req.agentId())
                .orElseThrow(() -> new IllegalStateException("Agent float account not found"));

        if (floatAcc.availableFloatPesewas() < req.amountPesewas()) {
            throw new IllegalStateException("Insufficient agent float: " + floatAcc.availableFloatPesewas()
                    + " pesewas (needed: " + req.amountPesewas() + ")");
        }

        // Calculate commission
        var comm = commissionEngine.calculateCommission(agent.kycTier(), "CASH_IN", req.amountPesewas());

        long floatBefore = floatAcc.availableFloatPesewas();
        long floatAfter = floatBefore - req.amountPesewas();

        // Update float and commission
        db.sql("""
            UPDATE agent_float_account
            SET available_float_pesewas = available_float_pesewas - ?,
                accrued_commission_pesewas = accrued_commission_pesewas + ?,
                updated_at = now()
            WHERE agent_id = ?
        """).params(req.amountPesewas(), comm.agentSharePesewas(), req.agentId())
        .update();

        UUID txnId = UUID.randomUUID();
        String ref = "CIN-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();

        db.sql("""
            INSERT INTO agent_transaction
            (id, agent_id, transaction_type, customer_identifier, amount_pesewas,
             float_before_pesewas, float_after_pesewas, agent_commission_pesewas,
             platform_fee_pesewas, reference, status)
            VALUES (?, ?, 'CASH_IN', ?, ?, ?, ?, ?, ?, ?, 'COMPLETED')
        """).params(txnId, req.agentId(), req.customerPhoneOrAccount(), req.amountPesewas(),
                floatBefore, floatAfter, comm.agentSharePesewas(), comm.platformSharePesewas(), ref)
        .update();

        return new CashInReceipt(txnId, ref, req.agentId(), req.customerPhoneOrAccount(),
                req.amountPesewas(), comm.agentSharePesewas(), floatAfter, "COMPLETED");
    }
}
