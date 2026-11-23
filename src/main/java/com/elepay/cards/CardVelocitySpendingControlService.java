package com.elepay.cards;

import com.elepay.common.ApiException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Time Card Velocity & Spending Control Service.
 * Evaluates:
 * 1. Card status & lifecycle (ACTIVE vs FROZEN / REVOKED / EXPIRED).
 * 2. Expiry verification.
 * 3. Per-transaction limit constraints.
 * 4. Multi-window calendar rollover & daily velocity thresholds.
 * 5. Monthly velocity thresholds.
 * 6. Company expense card departmental sublimits.
 * 7. Merchant Category Code (MCC) Whitelist & Blacklist enforcement (e.g. gambling 7995, liquor 5921).
 * 8. Atomic spend commitment and transaction journaling.
 */
@Service
public class CardVelocitySpendingControlService {

    private final JdbcClient db;
    private final CardControlsService controlsService;
    private final CompanyExpenseCardService expenseCardService;

    public CardVelocitySpendingControlService(
            JdbcClient db,
            CardControlsService controlsService,
            CompanyExpenseCardService expenseCardService
    ) {
        this.db = db;
        this.controlsService = controlsService;
        this.expenseCardService = expenseCardService;
    }

    public record VelocityEvaluation(
            boolean approved,
            String authCode,
            String declineReason,
            long amountPesewas,
            long daySpentAfterPesewas,
            long monthSpentAfterPesewas
    ) {}

    public record SpendCommitment(
            UUID transactionId,
            UUID cardId,
            boolean approved,
            String authCode,
            String declineReason,
            long amountPesewas
    ) {}

    @Transactional
    public VelocityEvaluation evaluateAndCommitSpend(
            UUID cardId,
            String merchantName,
            String merchantCity,
            String mcc,
            long amountPesewas,
            String currency
    ) {
        if (amountPesewas <= 0) {
            return new VelocityEvaluation(false, null, "INVALID_AMOUNT", amountPesewas, 0, 0);
        }

        // 1. Pessimistic lock on card_registry row
        var cardOpt = db.sql("""
            SELECT id, status, expiry_month, expiry_year,
                   per_tx_limit_pesewas, daily_limit_pesewas, monthly_limit_pesewas,
                   current_day_spent_pesewas, current_month_spent_pesewas, last_spend_date
            FROM card_registry
            WHERE id = :id FOR UPDATE
        """).param("id", cardId).query((rs, rowNum) -> new CardState(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getInt("expiry_month"),
                rs.getInt("expiry_year"),
                rs.getLong("per_tx_limit_pesewas"),
                rs.getLong("daily_limit_pesewas"),
                rs.getLong("monthly_limit_pesewas"),
                rs.getLong("current_day_spent_pesewas"),
                rs.getLong("current_month_spent_pesewas"),
                rs.getDate("last_spend_date") != null ? rs.getDate("last_spend_date").toLocalDate() : null
        )).optional();

        if (cardOpt.isEmpty()) {
            recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, false, null, "CARD_NOT_FOUND");
            return new VelocityEvaluation(false, null, "CARD_NOT_FOUND", amountPesewas, 0, 0);
        }

        CardState card = cardOpt.get();

        // 2. Status verification
        if (!"ACTIVE".equalsIgnoreCase(card.status)) {
            String reason = "CARD_" + card.status.toUpperCase();
            recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, false, null, reason);
            return new VelocityEvaluation(false, null, reason, amountPesewas, card.currentDaySpent, card.currentMonthSpent);
        }

        // 3. Expiry verification
        LocalDate today = LocalDate.now();
        YearMonth expiry = YearMonth.of(card.expiryYear, card.expiryMonth);
        if (expiry.isBefore(YearMonth.from(today))) {
            recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, false, null, "CARD_EXPIRED");
            return new VelocityEvaluation(false, null, "CARD_EXPIRED", amountPesewas, card.currentDaySpent, card.currentMonthSpent);
        }

        // 4. Per-transaction limit
        if (amountPesewas > card.perTxLimit) {
            recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, false, null, "PER_TX_LIMIT_EXCEEDED");
            return new VelocityEvaluation(false, null, "PER_TX_LIMIT_EXCEEDED", amountPesewas, card.currentDaySpent, card.currentMonthSpent);
        }

        // 5. MCC Category Whitelist & Blacklist evaluation
        List<CardControlsService.MccRule> mccRules = controlsService.getActiveMccRules(cardId);
        boolean hasWhitelist = false;
        boolean matchedWhitelist = false;

        for (var rule : mccRules) {
            if ("BLACKLIST".equalsIgnoreCase(rule.ruleType()) && rule.mccCode().equalsIgnoreCase(mcc)) {
                recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, false, null, "MCC_BLACKLISTED");
                return new VelocityEvaluation(false, null, "MCC_BLACKLISTED", amountPesewas, card.currentDaySpent, card.currentMonthSpent);
            }
            if ("WHITELIST".equalsIgnoreCase(rule.ruleType())) {
                hasWhitelist = true;
                if (rule.mccCode().equalsIgnoreCase(mcc)) {
                    matchedWhitelist = true;
                }
            }
        }
        if (hasWhitelist && !matchedWhitelist) {
            recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, false, null, "MCC_NOT_WHITELISTED");
            return new VelocityEvaluation(false, null, "MCC_NOT_WHITELISTED", amountPesewas, card.currentDaySpent, card.currentMonthSpent);
        }

        // 6. Calendar Rollover Calculations
        long daySpent = card.currentDaySpent;
        long monthSpent = card.currentMonthSpent;

        if (card.lastSpendDate == null || !card.lastSpendDate.equals(today)) {
            daySpent = 0;
        }
        if (card.lastSpendDate == null
                || card.lastSpendDate.getMonth() != today.getMonth()
                || card.lastSpendDate.getYear() != today.getYear()) {
            monthSpent = 0;
        }

        // 7. Daily Velocity Check
        if (daySpent + amountPesewas > card.dailyLimit) {
            recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, false, null, "DAILY_LIMIT_EXCEEDED");
            return new VelocityEvaluation(false, null, "DAILY_LIMIT_EXCEEDED", amountPesewas, daySpent, monthSpent);
        }

        // 8. Monthly Velocity Check
        if (monthSpent + amountPesewas > card.monthlyLimit) {
            recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, false, null, "MONTHLY_LIMIT_EXCEEDED");
            return new VelocityEvaluation(false, null, "MONTHLY_LIMIT_EXCEEDED", amountPesewas, daySpent, monthSpent);
        }

        // 9. Departmental Expense Card Sublimit (if attached)
        Optional<CompanyExpenseCardService.ExpenseCard> expenseCard = expenseCardService.getExpenseCardByCardId(cardId);
        if (expenseCard.isPresent()) {
            CompanyExpenseCardService.ExpenseCard ec = expenseCard.get();
            if (monthSpent + amountPesewas > ec.monthlySublimitPesewas()) {
                recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, false, null, "DEPARTMENT_SUBLIMIT_EXCEEDED");
                return new VelocityEvaluation(false, null, "DEPARTMENT_SUBLIMIT_EXCEEDED", amountPesewas, daySpent, monthSpent);
            }
        }

        // 10. Approve & Atomically Commit Spend
        long newDaySpent = daySpent + amountPesewas;
        long newMonthSpent = monthSpent + amountPesewas;
        String authCode = "AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        db.sql("""
            UPDATE card_registry
            SET current_day_spent_pesewas = :daySpent,
                current_month_spent_pesewas = :monthSpent,
                last_spend_date = CURRENT_DATE,
                updated_at = now()
            WHERE id = :id
        """)
                .param("daySpent", newDaySpent)
                .param("monthSpent", newMonthSpent)
                .param("id", cardId)
                .update();

        if (expenseCard.isPresent()) {
            db.sql("""
                UPDATE company_expense_card
                SET current_month_spent_pesewas = :monthSpent
                WHERE card_id = :cardId
            """)
                    .param("monthSpent", newMonthSpent)
                    .param("cardId", cardId)
                    .update();
        }

        recordTransaction(cardId, merchantName, merchantCity, mcc, amountPesewas, currency, true, authCode, null);

        return new VelocityEvaluation(true, authCode, null, amountPesewas, newDaySpent, newMonthSpent);
    }

    private void recordTransaction(
            UUID cardId,
            String merchantName,
            String merchantCity,
            String mcc,
            long amountPesewas,
            String currency,
            boolean approved,
            String authCode,
            String declineReason
    ) {
        db.sql("""
            INSERT INTO card_transaction (
                id, card_id, merchant_name, merchant_city, mcc,
                amount_pesewas, currency, auth_code, status,
                decline_reason, created_at
            ) VALUES (
                :id, :cardId, :name, :city, :mcc,
                :amt, :curr, :auth, :status,
                :reason, now()
            )
        """)
                .param("id", UUID.randomUUID())
                .param("cardId", cardId)
                .param("name", merchantName != null ? merchantName : "MERCHANT")
                .param("city", merchantCity != null ? merchantCity : "ACCRA")
                .param("mcc", mcc != null ? mcc : "0000")
                .param("amt", amountPesewas)
                .param("curr", currency != null ? currency : "GHS")
                .param("auth", authCode)
                .param("status", approved ? "APPROVED" : "DECLINED")
                .param("reason", declineReason)
                .update();
    }

    private record CardState(
            UUID id,
            String status,
            int expiryMonth,
            int expiryYear,
            long perTxLimit,
            long dailyLimit,
            long monthlyLimit,
            long currentDaySpent,
            long currentMonthSpent,
            LocalDate lastSpendDate
    ) {}
}
