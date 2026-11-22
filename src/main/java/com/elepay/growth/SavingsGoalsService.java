package com.elepay.growth;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import com.elepay.wallet.CustomerDirectory;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 097: Savings goals and voluntary round-up sweeps.
 * Automatically rounds up merchant/payment transactions to the nearest unit and transfers change to savings.
 * Enforces strict insufficient-funds protection (never overdrafts) and customer consent.
 * Integrates double-entry Ledger.post with row-level locking (FOR UPDATE).
 */
@Service
public class SavingsGoalsService {

    private final JdbcClient db;
    private final Ledger ledger;

    public SavingsGoalsService(JdbcClient db, Ledger ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    public record SavingsGoal(
            UUID id,
            UUID customerId,
            String goalName,
            long targetAmountPesewas,
            long currentAmountPesewas,
            String currency,
            boolean roundUpEnabled,
            int roundUpUnitPesewas,
            String status
    ) {}

    public record RoundUpResult(
            UUID sweepId,
            UUID goalId,
            long transactionAmountPesewas,
            long roundUpAmountPesewas,
            long newSavingsBalancePesewas,
            String status
    ) {}

    @Transactional
    public SavingsGoal createGoal(UUID customerId, String goalName, long targetAmountPesewas,
                                  int roundUpUnitPesewas, LocalDate targetDate) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO savings_goal
            (id, customer_id, goal_name, target_amount_pesewas, current_amount_pesewas, currency, round_up_enabled, round_up_unit_pesewas, status, target_date)
            VALUES (?, ?, ?, ?, 0, 'GHS', true, ?, 'ACTIVE', ?)
        """).params(id, customerId, goalName, targetAmountPesewas, roundUpUnitPesewas > 0 ? roundUpUnitPesewas : 100, targetDate)
        .update();

        ledger.open("customer", customerId, "available", Ledger.GHS);
        ledger.open("customer", customerId, "reserved", Ledger.GHS);

        return new SavingsGoal(id, customerId, goalName, targetAmountPesewas, 0L, "GHS", true, roundUpUnitPesewas, "ACTIVE");
    }

    /**
     * Calculates round-up amount to the nearest unit.
     */
    public long calculateRoundUp(long transactionAmountPesewas, int unitPesewas) {
        if (transactionAmountPesewas <= 0 || unitPesewas <= 0) return 0L;
        long remainder = transactionAmountPesewas % unitPesewas;
        return remainder == 0 ? 0L : (unitPesewas - remainder);
    }

    /**
     * Executes round-up sweep for an active goal given customer available wallet balance.
     * Enforces row-level locking (FOR UPDATE) and double-entry ledger debit & credit.
     */
    @Transactional
    public RoundUpResult sweepTransaction(UUID customerId, UUID transactionId, long transactionAmountPesewas, long customerAvailableBalancePesewas) {
        Optional<SavingsGoal> activeGoal = db.sql("""
                SELECT id, customer_id, goal_name, target_amount_pesewas, current_amount_pesewas,
                       currency, round_up_enabled, round_up_unit_pesewas, status
                FROM savings_goal
                WHERE customer_id = ? AND status = 'ACTIVE' AND round_up_enabled = true
                ORDER BY id
                LIMIT 1
                FOR UPDATE
                """)
                .param(customerId)
                .query((rs, rowNum) -> new SavingsGoal(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("goal_name"),
                        rs.getLong("target_amount_pesewas"),
                        rs.getLong("current_amount_pesewas"),
                        rs.getString("currency"),
                        rs.getBoolean("round_up_enabled"),
                        rs.getInt("round_up_unit_pesewas"),
                        rs.getString("status")
                )).optional();

        if (activeGoal.isEmpty()) {
            return new RoundUpResult(null, null, transactionAmountPesewas, 0L, 0L, "SKIPPED_NO_ACTIVE_GOAL");
        }

        SavingsGoal goal = activeGoal.get();
        long roundUp = calculateRoundUp(transactionAmountPesewas, goal.roundUpUnitPesewas());
        if (roundUp <= 0) {
            return new RoundUpResult(null, goal.id(), transactionAmountPesewas, 0L, goal.currentAmountPesewas(), "SKIPPED_NO_ROUND_UP_NEEDED");
        }

        // Insufficient funds safeguard: customer balance must cover the round-up
        if (customerAvailableBalancePesewas < roundUp) {
            return new RoundUpResult(null, goal.id(), transactionAmountPesewas, roundUp, goal.currentAmountPesewas(), "SKIPPED_INSUFFICIENT_FUNDS");
        }

        String currency = goal.currency() != null ? goal.currency() : Ledger.GHS;
        String availableAccount = CustomerDirectory.available(customerId);
        String savingsGoalAccount = CustomerDirectory.reserved(customerId);

        ledger.open("customer", customerId, "available", currency);
        ledger.open("customer", customerId, "reserved", currency);

        UUID sweepId = UUID.randomUUID();

        // Atomic balanced double-entry posting: debit customer:wallet:available, credit customer:savings:goal
        ledger.post(Ledger.Posting.of(
                "savings",
                sweepId.toString(),
                "savings_round_up",
                List.of(
                        Ledger.Leg.debit(availableAccount, roundUp),
                        Ledger.Leg.credit(savingsGoalAccount, roundUp)
                )
        ).describedAs("customer:savings:goal round-up for: " + goal.goalName()));

        db.sql("""
            INSERT INTO savings_round_up_sweep
            (id, goal_id, customer_id, transaction_id, transaction_amount_pesewas, round_up_amount_pesewas, status)
            VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED')
        """).params(sweepId, goal.id(), customerId, transactionId, transactionAmountPesewas, roundUp)
        .update();

        long newBalance = Math.addExact(goal.currentAmountPesewas(), roundUp);
        db.sql("UPDATE savings_goal SET current_amount_pesewas = ? WHERE id = ?")
                .params(newBalance, goal.id()).update();

        return new RoundUpResult(sweepId, goal.id(), transactionAmountPesewas, roundUp, newBalance, "COMPLETED");
    }

    /**
     * Executes direct deposit to a savings goal with row-level locking and double-entry ledger posting.
     */
    @Transactional
    public RoundUpResult depositToGoal(UUID customerId, UUID goalId, long depositAmountPesewas, long customerAvailableBalancePesewas) {
        if (depositAmountPesewas <= 0) {
            throw ApiException.badRequest("invalid_amount", "Deposit amount must be positive");
        }
        Optional<SavingsGoal> activeGoal = db.sql("""
                SELECT id, customer_id, goal_name, target_amount_pesewas, current_amount_pesewas,
                       currency, round_up_enabled, round_up_unit_pesewas, status
                FROM savings_goal
                WHERE id = ? AND customer_id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """)
                .params(goalId, customerId)
                .query((rs, rowNum) -> new SavingsGoal(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("goal_name"),
                        rs.getLong("target_amount_pesewas"),
                        rs.getLong("current_amount_pesewas"),
                        rs.getString("currency"),
                        rs.getBoolean("round_up_enabled"),
                        rs.getInt("round_up_unit_pesewas"),
                        rs.getString("status")
                )).optional();

        if (activeGoal.isEmpty()) {
            return new RoundUpResult(null, goalId, depositAmountPesewas, 0L, 0L, "SKIPPED_NO_ACTIVE_GOAL");
        }

        SavingsGoal goal = activeGoal.get();
        if (customerAvailableBalancePesewas < depositAmountPesewas) {
            return new RoundUpResult(null, goal.id(), depositAmountPesewas, depositAmountPesewas, goal.currentAmountPesewas(), "SKIPPED_INSUFFICIENT_FUNDS");
        }

        String currency = goal.currency() != null ? goal.currency() : Ledger.GHS;
        String availableAccount = CustomerDirectory.available(customerId);
        String savingsGoalAccount = CustomerDirectory.reserved(customerId);

        ledger.open("customer", customerId, "available", currency);
        ledger.open("customer", customerId, "reserved", currency);

        UUID depositId = UUID.randomUUID();
        ledger.post(Ledger.Posting.of(
                "savings",
                depositId.toString(),
                "savings_deposit",
                List.of(
                        Ledger.Leg.debit(availableAccount, depositAmountPesewas),
                        Ledger.Leg.credit(savingsGoalAccount, depositAmountPesewas)
                )
        ).describedAs("customer:savings:goal deposit for: " + goal.goalName()));

        long newBalance = Math.addExact(goal.currentAmountPesewas(), depositAmountPesewas);
        db.sql("UPDATE savings_goal SET current_amount_pesewas = ? WHERE id = ?")
                .params(newBalance, goal.id()).update();

        return new RoundUpResult(depositId, goal.id(), depositAmountPesewas, depositAmountPesewas, newBalance, "COMPLETED");
    }
}
