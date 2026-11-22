package com.elepay.cards;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardAuthorizationEngine implements CardProcessorContract {

    private final JdbcClient db;
    private final CardControlsService controlsService;
    private final CompanyExpenseCardService expenseCardService;
    private final CardIssuingService issuingService;
    private final Ledger ledger;
    private final NetworkTokenService networkTokenService;

    @org.springframework.beans.factory.annotation.Autowired
    public CardAuthorizationEngine(
            JdbcClient db,
            CardControlsService controlsService,
            CompanyExpenseCardService expenseCardService,
            CardIssuingService issuingService,
            Ledger ledger,
            NetworkTokenService networkTokenService
    ) {
        this.db = db;
        this.controlsService = controlsService;
        this.expenseCardService = expenseCardService;
        this.issuingService = issuingService;
        this.ledger = ledger;
        this.networkTokenService = networkTokenService;
    }

    private static class CardRecord {
        UUID id;
        UUID customerId;
        UUID merchantId;
        String status;
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

        // 1. Check if PAN matches a primary card in card_registry
        var cardOpt = db.sql("""
            SELECT id, customer_id, merchant_id, status, expiry_month, expiry_year, per_tx_limit_pesewas,
                   daily_limit_pesewas, monthly_limit_pesewas, current_day_spent_pesewas,
                   current_month_spent_pesewas, last_spend_date
            FROM card_registry
            WHERE pan_hash = ?
            FOR UPDATE
        """).param(panHash)
        .query(this::mapCardRecord).optional();

        boolean isNetworkToken = false;
        NetworkToken networkToken = null;

        // 2. If not found in card_registry, check if PAN is an active Network Token
        if (cardOpt.isEmpty()) {
            Optional<NetworkToken> tokenOpt = networkTokenService.findByTokenPan(req.pan());
            if (tokenOpt.isPresent()) {
                networkToken = tokenOpt.get();
                isNetworkToken = true;

                if (networkToken.status() != TokenStatus.ACTIVE) {
                    return recordDecline(networkToken.cardId(), req, "DECLINED_TOKEN_" + networkToken.status(), "403");
                }

                if (networkToken.expiryMonth() != req.expiryMonth() || networkToken.expiryYear() != req.expiryYear()) {
                    return recordDecline(networkToken.cardId(), req, "DECLINED_INVALID_TOKEN_EXPIRY", "401");
                }

                // Load underlying card for limits and ledger hold
                cardOpt = db.sql("""
                    SELECT id, customer_id, merchant_id, status, expiry_month, expiry_year, per_tx_limit_pesewas,
                           daily_limit_pesewas, monthly_limit_pesewas, current_day_spent_pesewas,
                           current_month_spent_pesewas, last_spend_date
                    FROM card_registry
                    WHERE id = ?
                    FOR UPDATE
                """).param(networkToken.cardId())
                .query(this::mapCardRecord).optional();
            }
        }

        if (cardOpt.isEmpty()) {
            return recordDecline(null, req, "DECLINED_CARD_NOT_FOUND", "404");
        }

        CardRecord card = cardOpt.get();

        if (!"ACTIVE".equals(card.status)) {
            return recordDecline(card.id, req, "DECLINED_CARD_" + card.status, "403");
        }

        // For non-token card transactions, verify CVV2 dynamically using secret Card Verification Key
        if (!isNetworkToken) {
            if (!issuingService.verifyCvv2(req.pan(), card.expiryMonth, card.expiryYear, req.cvv2())) {
                return recordDecline(card.id, req, "DECLINED_INVALID_CVV2", "401");
            }

            if (card.expiryMonth != req.expiryMonth() || card.expiryYear != req.expiryYear()) {
                return recordDecline(card.id, req, "DECLINED_INVALID_EXPIRY", "401");
            }
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

        // ---------------------------------------------------------------------
        // Wire to Authoritative Financial Ledger: Atomic Balance Hold / Reservation
        // ---------------------------------------------------------------------
        String ownerType = card.customerId != null ? "customer" : (card.merchantId != null ? "merchant" : null);
        UUID ownerId = card.customerId != null ? card.customerId : card.merchantId;

        if (ownerType == null) {
            return recordDecline(card.id, req, "DECLINED_UNBACKED_CARD", "403");
        }

        String currency = req.currency() != null && !req.currency().isBlank() ? req.currency() : Ledger.GHS;
        // Ensure available and reserved accounts exist
        ledger.open(ownerType, ownerId, "available", currency);
        ledger.open(ownerType, ownerId, "reserved", currency);

        String availableAccount = Ledger.code(ownerType, ownerId, "available", currency);
        String reservedAccount = Ledger.code(ownerType, ownerId, "reserved", currency);

        // Pre-check available funds on authoritative ledger
        long availableBalance = ledger.balance(availableAccount);
        if (availableBalance < req.amountPesewas()) {
            return recordDecline(card.id, req, "DECLINED_INSUFFICIENT_FUNDS", "51");
        }

        String authCode = "AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        try {
            Ledger.Posting holdPosting = new Ledger.Posting(
                    "card-auth-hold:" + card.id,
                    authCode,
                    "card:authorization:hold",
                    "Card spend hold: " + req.merchantName(),
                    List.of(
                            Ledger.Leg.debit(availableAccount, req.amountPesewas()),
                            Ledger.Leg.credit(reservedAccount, req.amountPesewas())
                    ),
                    null, // related_to is FK to journal_transaction(id)
                    Map.of(
                            "authCode", authCode,
                            "cardId", card.id.toString(),
                            "merchantName", req.merchantName() != null ? req.merchantName() : "",
                            "mcc", req.mcc() != null ? req.mcc() : "",
                            "isNetworkToken", String.valueOf(isNetworkToken)
                    )
            );
            ledger.post(holdPosting);
        } catch (ApiException e) {
            if ("insufficient_funds".equalsIgnoreCase(e.code()) || (e.getMessage() != null && e.getMessage().contains("insufficient"))) {
                return recordDecline(card.id, req, "DECLINED_INSUFFICIENT_FUNDS", "51");
            }
            return recordDecline(card.id, req, "DECLINED_LEDGER_ERROR: " + e.getMessage(), "500");
        }

        // All checks passed and ledger hold placed! Update card registry spending counters
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

        UUID txnId = UUID.randomUUID();
        db.sql("""
            INSERT INTO card_transaction
            (id, card_id, merchant_name, merchant_city, mcc, amount_pesewas, currency, auth_code, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED')
        """).params(txnId, card.id, req.merchantName(), req.merchantCity(), req.mcc(),
                req.amountPesewas(), currency, authCode)
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

    private CardRecord mapCardRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        CardRecord c = new CardRecord();
        c.id = rs.getObject("id", UUID.class);
        c.customerId = rs.getObject("customer_id", UUID.class);
        c.merchantId = rs.getObject("merchant_id", UUID.class);
        c.status = rs.getString("status");
        c.expiryMonth = rs.getInt("expiry_month");
        c.expiryYear = rs.getInt("expiry_year");
        c.perTxLimitPesewas = rs.getLong("per_tx_limit_pesewas");
        c.dailyLimitPesewas = rs.getLong("daily_limit_pesewas");
        c.monthlyLimitPesewas = rs.getLong("monthly_limit_pesewas");
        c.currentDaySpentPesewas = rs.getLong("current_day_spent_pesewas");
        c.currentMonthSpentPesewas = rs.getLong("current_month_spent_pesewas");
        c.lastSpendDate = rs.getObject("last_spend_date", LocalDate.class);
        return c;
    }
}
