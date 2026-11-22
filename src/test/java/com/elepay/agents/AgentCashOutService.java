package com.elepay.agents;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentCashOutService {

    private final JdbcClient db;
    private final AgentConfigService configService;
    private final AgentOnboardingService onboardingService;
    private final AgentFloatService floatService;
    private final AgentCommissionEngine commissionEngine;
    private final SecureRandom random = new SecureRandom();

    public AgentCashOutService(
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

    public record CashOutOtpChallenge(
            UUID challengeId,
            UUID agentId,
            String customerPhone,
            long amountPesewas,
            String otpCode,
            OffsetDateTime expiresAt
    ) {}

    public record CashOutReceipt(
            UUID transactionId,
            String reference,
            UUID agentId,
            String customerPhone,
            long amountPesewas,
            long agentCommissionPesewas,
            long updatedFloatPesewas,
            String status
    ) {}

    @Transactional
    public CashOutOtpChallenge generateOtpChallenge(UUID agentId, String customerPhone, UUID customerWalletId, long amountPesewas) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Cash-out amount must be positive");
        }

        UUID challengeId = UUID.randomUUID();
        String otp = String.format("%06d", random.nextInt(1000000));
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(5);

        db.sql("""
            INSERT INTO agent_cash_out_otp
            (id, agent_id, customer_phone, customer_wallet_id, amount_pesewas, otp_code, expires_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
        """).params(challengeId, agentId, customerPhone, customerWalletId, amountPesewas, otp, expiresAt)
        .update();

        return new CashOutOtpChallenge(challengeId, agentId, customerPhone, amountPesewas, otp, expiresAt);
    }

    @Transactional
    public CashOutReceipt confirmCashOut(UUID agentId, String customerPhone, long amountPesewas, String otpCode, boolean isSimulation) {
        configService.assertPermitted(isSimulation);

        var agent = onboardingService.getAgentById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        if (!"ACTIVE".equals(agent.status())) {
            throw new IllegalStateException("Agent is not active: " + agent.status());
        }

        if (amountPesewas > agent.maxSingleCashOutPesewas()) {
            throw new IllegalArgumentException("Cash-out amount (" + amountPesewas
                    + ") exceeds agent limit (" + agent.maxSingleCashOutPesewas() + ")");
        }

        var challengeOpt = db.sql("""
            SELECT id, otp_code, expires_at, status, amount_pesewas
            FROM agent_cash_out_otp
            WHERE agent_id = ? AND customer_phone = ? AND status = 'PENDING'
            ORDER BY created_at DESC
            LIMIT 1
            FOR UPDATE
        """).params(agentId, customerPhone)
        .query((rs, rowNum) -> new Object() {
            UUID id = rs.getObject("id", UUID.class);
            String otp = rs.getString("otp_code");
            OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
            String status = rs.getString("status");
            long expectedAmount = rs.getLong("amount_pesewas");
        }).optional();

        if (challengeOpt.isEmpty()) {
            throw new IllegalArgumentException("No pending cash-out OTP challenge found for phone: " + customerPhone);
        }

        var challenge = challengeOpt.get();

        if (OffsetDateTime.now().isAfter(challenge.expiresAt)) {
            db.sql("UPDATE agent_cash_out_otp SET status = 'EXPIRED' WHERE id = ?").param(challenge.id).update();
            throw new IllegalStateException("Cash-out OTP has expired");
        }

        if (!challenge.otp.equals(otpCode)) {
            throw new IllegalArgumentException("Invalid OTP code provided");
        }

        if (challenge.expectedAmount != amountPesewas) {
            throw new IllegalArgumentException("Amount mismatch against OTP challenge: expected " + challenge.expectedAmount);
        }

        // Mark OTP verified
        db.sql("UPDATE agent_cash_out_otp SET status = 'VERIFIED' WHERE id = ?").param(challenge.id).update();

        var floatAcc = floatService.getFloatAccount(agentId).orElseThrow();
        var comm = commissionEngine.calculateCommission(agent.kycTier(), "CASH_OUT", amountPesewas);

        long floatBefore = floatAcc.availableFloatPesewas();
        long floatAfter = floatBefore + amountPesewas;

        // Credit agent float with cash-out amount, accrue commission
        db.sql("""
            UPDATE agent_float_account
            SET available_float_pesewas = available_float_pesewas + ?,
                accrued_commission_pesewas = accrued_commission_pesewas + ?,
                updated_at = now()
            WHERE agent_id = ?
        """).params(amountPesewas, comm.agentSharePesewas(), agentId)
        .update();

        UUID txnId = UUID.randomUUID();
        String ref = "COT-" + System.currentTimeMillis() + "-" + txnId.toString().substring(0, 6).toUpperCase();

        db.sql("""
            INSERT INTO agent_transaction
            (id, agent_id, transaction_type, customer_identifier, amount_pesewas,
             float_before_pesewas, float_after_pesewas, agent_commission_pesewas,
             platform_fee_pesewas, reference, status)
            VALUES (?, ?, 'CASH_OUT', ?, ?, ?, ?, ?, ?, ?, 'COMPLETED')
        """).params(txnId, agentId, customerPhone, amountPesewas, floatBefore, floatAfter,
                comm.agentSharePesewas(), comm.platformSharePesewas(), ref)
        .update();

        return new CashOutReceipt(txnId, ref, agentId, customerPhone, amountPesewas, comm.agentSharePesewas(), floatAfter, "COMPLETED");
    }
}
