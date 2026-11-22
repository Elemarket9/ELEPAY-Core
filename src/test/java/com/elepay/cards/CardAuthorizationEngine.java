package com.elepay.cards;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardAuthorizationEngine implements CardProcessorContract {

    private final JdbcClient db;
    private final CardControlsService controlsService;
    private final CompanyExpenseCardService expenseCardService;

    public CardAuthorizationEngine(JdbcClient db, CardControlsService controlsService, CompanyExpenseCardService expenseCardService) {
        this.db = db;
        this.controlsService = controlsService;
        this.expenseCardService = expenseCardService;
    }

    private static class CardRecord {
        UUID id;
        String status;
        String cvv2;
        int expiryMonth;
        int expiryYear;
        long perTxLimitPesewas;
        long dailyLimitPesewas;
        long monthlyLimitPesewas;
        long currentDaySpentPesewas;
        long currentMonthSpentPesewas;
        LocalDate lastSpendDate;
    }

    @Override
    @Transactional
    public AuthorizationResponse processAuthorization(AuthorizationRequest req) {
        if (!CardIssuingService.isValidLuhn(req.pan())) {
            return recordDecline(null, req, "DECLINED_INVALID_PAN_LUHN", "400");
        }

        String panHash = CardIssuingService.hashPan(req.pan());

        var cardOpt = db.sql("""
            SELECT id, status, cvv2, expiry_month, expiry_year, per_tx_limit_pesewas,
                   daily_limit_pesewas, monthly_limit_pesewas, current_day_spent_pesewas,
                   current_month_spent_pesewas, last_spend_date
            FROM card_registry
            WHERE pan_hash = ?
            FOR UPDATE
        """).param(panHash)
        .query((rs, rowNum) -> {
            CardRecord c = new CardRecord();
            c.id = rs.getObject("id", UUID.class);
            c.status = rs.getString("status");
            c.cvv2 = rs.getString("cvv2");
            c.expiryMonth = rs.getInt("expiry_month");
            c.expiryYear = rs.getInt("expiry_year");
            c.perTxLimitPesewas = rs.getLong("per_tx_limit_pesewas");
            c.dailyLimitPesewas = rs.getLong("daily_limit_pesewas");
            c.monthlyLimitPesewas = rs.getLong("monthly_limit_pesewas");
            c.currentDaySpentPesewas = rs.getLong("current_day_spent_pesewas");
            c.currentMonthSpentPesewas = rs.getLong("current_month_spent_pesewas");
            c.lastSpendDate = rs.getObject("last_spend_date", LocalDate.class);
            return c;
        }).optional();

        if (cardOpt.isEmpty()) {
            return recordDecline(null, req, "DECLINED_CARD_NOT_FOUND", "404");
        }

        CardRecord card = cardOpt.get();

        if (!"ACTIVE".equals(card.status)) {
            return recordDecline(card.id, req, "DECLINED_CARD_" + card.status, "403");
        }

        if (!card.cvv2.equals(req.cvv2())) {
            return recordDecline(card.id, req, "DECLINED_INVALID_CVV2", "401");
        }

        if (card.expiryMonth != req.expiryMonth() || card.expiryYear != req.expiryYear()) {
            return recordDecline(card.id, req, "DECLINED_INVALID_EXPIRY", "401");
        }

        LocalDate today = LocalDate.now();
        // Reset rolling counters if date transitioned
        long daySpent = card.currentDaySpentPesewas;
        long monthSpent = card.currentMonthSpentPesewas;

        if (card.lastSpendDate == null || !card.lastSpendDate.isEqual(today)) {
            daySpent = 0L;
            if (card.lastSpendDate == null || card.lastSpendDate.getMonthValue() != today.getMonthValue() || card.lastSpendDate.getYear() != today.getYear()) {
                monthSpent = 0L;
            }
        }

        // Per-tx limit check
        if (req.amountPesewas() > card.perTxLimitPesewas) {
            return recordDecline(card.id, req, "DECLINED_PER_TX_LIMIT_EXCEEDED", "402");
        }

        // Daily limit check
        if (daySpent + req.amountPesewas() > card.dailyLimitPesewas) {
            return recordDecline(card.id, req, "DECLINED_DAILY_LIMIT_EXCEEDED", "402");
        }

        // Monthly limit check
        if (monthSpent + req.amountPesewas() > card.monthlyLimitPesewas) {
            return recordDecline(card.id, req, "DECLINED_MONTHLY_LIMIT_EXCEEDED", "402");
        }

        // MCC Whitelist / Blacklist check
        List<CardControlsService.MccRule> rules = controlsService.getActiveMccRules(card.id);
        boolean hasWhitelist = rules.stream().anyMatch(r -> "WHITELIST".equalsIgnoreCase(r.ruleType()));
        boolean isWhitelisted = false;

        for (var rule : rules) {
            if ("BLACKLIST".equalsIgnoreCase(rule.ruleType()) && rule.mccCode().equals(req.mcc())) {
                return recordDecline(card.id, req, "DECLINED_MCC_BLACKLISTED (" + req.mcc() + ")", "403");
            }
            if ("WHITELIST".equalsIgnoreCase(rule.ruleType()) && rule.mccCode().equals(req.mcc())) {
                isWhitelisted = true;
            }
        }

        if (hasWhitelist && !isWhitelisted) {
            return recordDecline(card.id, req, "DECLINED_MCC_NOT_WHITELISTED (" + req.mcc() + ")", "403");
        }

        // Company expense card check
        var expenseCardOpt = expenseCardService.getExpenseCardByCardId(card.id);
        if (expenseCardOpt.isPresent()) {
            var expCard = expenseCardOpt.get();
            if (expCard.currentMonthSpentPesewas() + req.amountPesewas() > expCard.monthlySublimitPesewas()) {
                return recordDecline(card.id, req, "DECLINED_EMPLOYEE_SUBLIMIT_EXCEEDED", "402");
            }
            // Update company expense card spent
            db.sql("""
                UPDATE company_expense_card
                SET current_month_spent_pesewas = current_month_spent_pesewas + ?
                WHERE id = ?
            """).params(req.amountPesewas(), expCard.id()).update();
        }

        // All checks passed! Update card registry spending counters
        daySpent += req.amountPesewas();
        monthSpent += req.amountPesewas();

        db.sql("""
            UPDATE card_registry
            SET current_day_spent_pesewas = ?,
                current_month_spent_pesewas = ?,
                last_spend_date = ?,
                updated_at = now()
            WHERE id = ?
        """).params(daySpent, monthSpent, today, card.id).update();

        String authCode = "AUTH-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        UUID txnId = UUID.randomUUID();
        db.sql("""
            INSERT INTO card_transaction
            (id, card_id, merchant_name, merchant_city, mcc, amount_pesewas, currency, auth_code, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED')
        """).params(txnId, card.id, req.merchantName(), req.merchantCity(), req.mcc(),
                req.amountPesewas(), req.currency() != null ? req.currency() : "GHS", authCode)
        .update();

        return new AuthorizationResponse(true, authCode, "00", null);
    }

    private AuthorizationResponse recordDecline(UUID cardId, AuthorizationRequest req, String reason, String code) {
        if (cardId != null) {
            UUID txnId = UUID.randomUUID();
            db.sql("""
                INSERT INTO card_transaction
                (id, card_id, merchant_name, merchant_city, mcc, amount_pesewas, currency, status, decline_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DECLINED', ?)
            """).params(txnId, cardId, req.merchantName(), req.merchantCity(), req.mcc(),
                    req.amountPesewas(), req.currency() != null ? req.currency() : "GHS", reason)
            .update();
        }
        return new AuthorizationResponse(false, null, code, reason);
    }
}
