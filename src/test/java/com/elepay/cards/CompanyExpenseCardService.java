package com.elepay.cards;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyExpenseCardService {

    private final JdbcClient db;
    private final CardIssuingService issuingService;

    public CompanyExpenseCardService(JdbcClient db, CardIssuingService issuingService) {
        this.db = db;
        this.issuingService = issuingService;
    }

    public record ExpenseCard(
            UUID id,
            UUID cardId,
            UUID merchantId,
            UUID employeeId,
            String department,
            long monthlySublimitPesewas,
            long currentMonthSpentPesewas,
            boolean managerApprovalRequired,
            boolean isActive
    ) {}

    @Transactional
    public ExpenseCard issueEmployeeCard(
            UUID merchantId, UUID employeeId, String department,
            String scheme, long monthlySublimitPesewas, boolean managerApprovalRequired
    ) {
        CardIssuingService.CardDetails card = issuingService.issueCard(null, merchantId, "VIRTUAL", scheme);

        UUID expenseCardId = UUID.randomUUID();
        db.sql("""
            INSERT INTO company_expense_card
            (id, card_id, merchant_id, employee_id, department, monthly_sublimit_pesewas,
             current_month_spent_pesewas, manager_approval_required, is_active)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, true)
        """).params(expenseCardId, card.id(), merchantId, employeeId, department,
                monthlySublimitPesewas, managerApprovalRequired)
        .update();

        return new ExpenseCard(
                expenseCardId, card.id(), merchantId, employeeId, department,
                monthlySublimitPesewas, 0L, managerApprovalRequired, true
        );
    }

    public Optional<ExpenseCard> getExpenseCardByCardId(UUID cardId) {
        return db.sql("""
            SELECT id, card_id, merchant_id, employee_id, department, monthly_sublimit_pesewas,
                   current_month_spent_pesewas, manager_approval_required, is_active
            FROM company_expense_card
            WHERE card_id = ?
        """).param(cardId)
        .query((rs, rowNum) -> new ExpenseCard(
                rs.getObject("id", UUID.class),
                rs.getObject("card_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getString("department"),
                rs.getLong("monthly_sublimit_pesewas"),
                rs.getLong("current_month_spent_pesewas"),
                rs.getBoolean("manager_approval_required"),
                rs.getBoolean("is_active")
        )).optional();
    }
}
