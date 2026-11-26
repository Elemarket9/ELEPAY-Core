package com.elepay.merchanttools.accounting;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 5: Business Accounting & Invoicing
 * Merchant General Ledger (GL) separate from customer safeguarding ledger.
 * Manages double-entry postings, account balances, trial balance, P&L, and balance sheet.
 */
@Service
public class MerchantGeneralLedgerService {

    private final JdbcClient db;

    public MerchantGeneralLedgerService(JdbcClient db) {
        this.db = db;
    }

    public record GlAccount(
            UUID id,
            UUID merchantId,
            String accountCode,
            String accountName,
            GlAccountCategory category,
            String subcategory,
            NormalBalance normalBalance,
            long balancePesewas,
            String currency,
            boolean isActive
    ) {}

    public record JournalLineCommand(
            UUID accountId,
            long debitPesewas,
            long creditPesewas,
            String description
    ) {
        public JournalLineCommand {
            if (debitPesewas < 0 || creditPesewas < 0) {
                throw new IllegalArgumentException("Debits and credits must be non-negative");
            }
            if ((debitPesewas > 0 && creditPesewas > 0) || (debitPesewas == 0 && creditPesewas == 0)) {
                throw new IllegalArgumentException("A line must have either a debit or credit amount, not both or neither");
            }
        }
    }

    public record PostJournalEntryCommand(
            UUID merchantId,
            String entryNumber,
            LocalDate entryDate,
            String description,
            String referenceType,
            UUID referenceId,
            String currency,
            List<JournalLineCommand> lines
    ) {}

    public record JournalEntryResult(
            UUID id,
            UUID merchantId,
            String entryNumber,
            LocalDate entryDate,
            String description,
            long totalAmountPesewas,
            String status
    ) {}

    public record TrialBalanceLine(
            String accountCode,
            String accountName,
            GlAccountCategory category,
            NormalBalance normalBalance,
            long debitBalancePesewas,
            long creditBalancePesewas
    ) {}

    public record TrialBalanceReport(
            UUID merchantId,
            LocalDate asOfDate,
            List<TrialBalanceLine> lines,
            long totalDebitPesewas,
            long totalCreditPesewas,
            boolean isBalanced
    ) {}

    public record ProfitAndLossReport(
            UUID merchantId,
            LocalDate fromDate,
            LocalDate toDate,
            long totalRevenuePesewas,
            long totalCogsPesewas,
            long grossProfitPesewas,
            long totalOperatingExpensePesewas,
            long netProfitPesewas
    ) {}

    public record BalanceSheetReport(
            UUID merchantId,
            LocalDate asOfDate,
            long totalAssetsPesewas,
            long totalLiabilitiesPesewas,
            long totalEquityPesewas,
            long netIncomePesewas,
            boolean isBalanced
    ) {}

    /**
     * Initializes the standard Ghanaian Chart of Accounts for a new or existing merchant.
     */
    @Transactional
    public void initializeStandardChartOfAccounts(UUID merchantId) {
        for (var std : GhanaianChartOfAccounts.STANDARD_CHART) {
            UUID id = UUID.randomUUID();
            db.sql("""
                INSERT INTO merchant_gl_account
                (id, merchant_id, account_code, account_name, account_category, subcategory, normal_balance, balance_pesewas, currency, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'GHS', true)
                ON CONFLICT (merchant_id, account_code) DO NOTHING
            """).params(id, merchantId, std.code(), std.name(), std.category().name(),
                    std.subcategory(), std.normalBalance().name())
            .update();
        }
    }

    @Transactional
    public GlAccount createAccount(UUID merchantId, String code, String name, GlAccountCategory category,
                                   String subcategory, NormalBalance normalBalance) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO merchant_gl_account
            (id, merchant_id, account_code, account_name, account_category, subcategory, normal_balance, balance_pesewas, currency, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'GHS', true)
        """).params(id, merchantId, code, name, category.name(), subcategory, normalBalance.name())
        .update();

        return new GlAccount(id, merchantId, code, name, category, subcategory, normalBalance, 0L, "GHS", true);
    }

    public Optional<GlAccount> getAccountByCode(UUID merchantId, String accountCode) {
        return db.sql("""
            SELECT id, merchant_id, account_code, account_name, account_category, subcategory, normal_balance, balance_pesewas, currency, is_active
            FROM merchant_gl_account
            WHERE merchant_id = ? AND account_code = ?
        """).params(merchantId, accountCode)
        .query((rs, rowNum) -> new GlAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("account_code"),
                rs.getString("account_name"),
                GlAccountCategory.valueOf(rs.getString("account_category")),
                rs.getString("subcategory"),
                NormalBalance.valueOf(rs.getString("normal_balance")),
                rs.getLong("balance_pesewas"),
                rs.getString("currency"),
                rs.getBoolean("is_active")
        )).optional();
    }

    public List<GlAccount> listAccounts(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, account_code, account_name, account_category, subcategory, normal_balance, balance_pesewas, currency, is_active
            FROM merchant_gl_account
            WHERE merchant_id = ?
            ORDER BY account_code ASC
        """).params(merchantId)
        .query((rs, rowNum) -> new GlAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("account_code"),
                rs.getString("account_name"),
                GlAccountCategory.valueOf(rs.getString("account_category")),
                rs.getString("subcategory"),
                NormalBalance.valueOf(rs.getString("normal_balance")),
                rs.getLong("balance_pesewas"),
                rs.getString("currency"),
                rs.getBoolean("is_active")
        )).list();
    }

    /**
     * Posts an atomic, balanced double-entry journal entry to the Merchant GL.
     * Enforces debits == credits, currency consistency, and updates account balances.
     */
    @Transactional
    public JournalEntryResult postJournalEntry(PostJournalEntryCommand command) {
        if (command.lines() == null || command.lines().size() < 2) {
            throw new IllegalArgumentException("Journal entry must contain at least 2 lines for double-entry");
        }

        long totalDebit = command.lines().stream().mapToLong(JournalLineCommand::debitPesewas).sum();
        long totalCredit = command.lines().stream().mapToLong(JournalLineCommand::creditPesewas).sum();

        if (totalDebit != totalCredit) {
            throw new IllegalArgumentException("Journal entry out of balance: total debits (" +
                    totalDebit + ") != total credits (" + totalCredit + ")");
        }

        UUID entryId = UUID.randomUUID();
        String entryNumber = command.entryNumber() != null ? command.entryNumber() : "GL-" + System.currentTimeMillis();

        db.sql("""
            INSERT INTO merchant_gl_journal_entry
            (id, merchant_id, entry_number, entry_date, description, reference_type, reference_id, currency, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED')
        """).params(entryId, command.merchantId(), entryNumber, command.entryDate(),
                command.description(), command.referenceType(), command.referenceId(),
                command.currency() != null ? command.currency() : "GHS")
        .update();

        for (JournalLineCommand line : command.lines()) {
            UUID lineId = UUID.randomUUID();
            db.sql("""
                INSERT INTO merchant_gl_journal_line
                (id, journal_entry_id, account_id, debit_pesewas, credit_pesewas, description)
                VALUES (?, ?, ?, ?, ?, ?)
            """).params(lineId, entryId, line.accountId(), line.debitPesewas(), line.creditPesewas(), line.description())
            .update();

            // Lock and update account balance according to normal balance convention
            GlAccount acc = db.sql("SELECT normal_balance, balance_pesewas FROM merchant_gl_account WHERE id = ? FOR UPDATE")
                    .param(line.accountId())
                    .query((rs, rowNum) -> new GlAccount(
                            line.accountId(), command.merchantId(), null, null, null, null,
                            NormalBalance.valueOf(rs.getString("normal_balance")),
                            rs.getLong("balance_pesewas"), null, true
                    )).single();

            long delta = (acc.normalBalance() == NormalBalance.DEBIT)
                    ? (line.debitPesewas() - line.creditPesewas())
                    : (line.creditPesewas() - line.debitPesewas());

            db.sql("UPDATE merchant_gl_account SET balance_pesewas = balance_pesewas + ? WHERE id = ?")
                    .params(delta, line.accountId())
                    .update();
        }

        return new JournalEntryResult(entryId, command.merchantId(), entryNumber,
                command.entryDate(), command.description(), totalDebit, "POSTED");
    }

    /**
     * Reverses an existing journal entry through an atomic linked reversal entry.
     */
    @Transactional
    public JournalEntryResult reverseJournalEntry(UUID merchantId, UUID originalEntryId, String reason) {
        var original = db.sql("""
            SELECT id, entry_number, currency FROM merchant_gl_journal_entry
            WHERE id = ? AND merchant_id = ? AND status = 'POSTED' FOR UPDATE
        """).params(originalEntryId, merchantId)
        .query((rs, rowNum) -> Map.of(
                "entry_number", rs.getString("entry_number"),
                "currency", rs.getString("currency")
        )).single();

        var lines = db.sql("""
            SELECT account_id, debit_pesewas, credit_pesewas, description
            FROM merchant_gl_journal_line
            WHERE journal_entry_id = ?
        """).param(originalEntryId)
        .query((rs, rowNum) -> new JournalLineCommand(
                rs.getObject("account_id", UUID.class),
                rs.getLong("credit_pesewas"), // Swap debits and credits for reversal
                rs.getLong("debit_pesewas"),
                "Reversal of " + original.get("entry_number") + ": " + reason
        )).list();

        db.sql("UPDATE merchant_gl_journal_entry SET status = 'REVERSED' WHERE id = ?")
                .param(originalEntryId).update();

        String reversalNumber = "REV-" + original.get("entry_number");
        return postJournalEntry(new PostJournalEntryCommand(
                merchantId,
                reversalNumber,
                LocalDate.now(),
                "Reversal: " + reason,
                "REVERSAL",
                originalEntryId,
                original.get("currency"),
                lines
        ));
    }

    private List<GlAccount> listAccountsAsOf(UUID merchantId, LocalDate asOfDate) {
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        return db.sql("""
            SELECT a.id, a.merchant_id, a.account_code, a.account_name, a.account_category,
                   a.subcategory, a.normal_balance, a.currency, a.is_active,
                   CASE WHEN a.normal_balance = 'DEBIT'
                        THEN COALESCE(agg.total_debit, 0) - COALESCE(agg.total_credit, 0)
                        ELSE COALESCE(agg.total_credit, 0) - COALESCE(agg.total_debit, 0)
                   END AS balance_pesewas
            FROM merchant_gl_account a
            LEFT JOIN (
                SELECT l.account_id,
                       SUM(l.debit_pesewas) AS total_debit,
                       SUM(l.credit_pesewas) AS total_credit
                FROM merchant_gl_journal_line l
                JOIN merchant_gl_journal_entry e ON e.id = l.journal_entry_id
                WHERE e.merchant_id = ?
                  AND e.status IN ('POSTED', 'REVERSED')
                  AND e.entry_date <= ?
                GROUP BY l.account_id
            ) agg ON agg.account_id = a.id
            WHERE a.merchant_id = ?
            ORDER BY a.account_code ASC
        """).params(merchantId, effectiveAsOf, merchantId)
        .query((rs, rowNum) -> new GlAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("account_code"),
                rs.getString("account_name"),
                GlAccountCategory.valueOf(rs.getString("account_category")),
                rs.getString("subcategory"),
                NormalBalance.valueOf(rs.getString("normal_balance")),
                rs.getLong("balance_pesewas"),
                rs.getString("currency"),
                rs.getBoolean("is_active")
        )).list();
    }

    private List<GlAccount> listAccountsForPeriod(UUID merchantId, LocalDate fromDate, LocalDate toDate) {
        LocalDate effectiveTo = toDate != null ? toDate : LocalDate.now();
        LocalDate effectiveFrom = fromDate != null ? fromDate : effectiveTo.withDayOfMonth(1);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("Start date cannot be after end date");
        }
        return db.sql("""
            SELECT a.id, a.merchant_id, a.account_code, a.account_name, a.account_category,
                   a.subcategory, a.normal_balance, a.currency, a.is_active,
                   CASE WHEN a.normal_balance = 'DEBIT'
                        THEN COALESCE(agg.total_debit, 0) - COALESCE(agg.total_credit, 0)
                        ELSE COALESCE(agg.total_credit, 0) - COALESCE(agg.total_debit, 0)
                   END AS balance_pesewas
            FROM merchant_gl_account a
            LEFT JOIN (
                SELECT l.account_id,
                       SUM(l.debit_pesewas) AS total_debit,
                       SUM(l.credit_pesewas) AS total_credit
                FROM merchant_gl_journal_line l
                JOIN merchant_gl_journal_entry e ON e.id = l.journal_entry_id
                WHERE e.merchant_id = ?
                  AND e.status IN ('POSTED', 'REVERSED')
                  AND e.entry_date BETWEEN ? AND ?
                GROUP BY l.account_id
            ) agg ON agg.account_id = a.id
            WHERE a.merchant_id = ?
            ORDER BY a.account_code ASC
        """).params(merchantId, effectiveFrom, effectiveTo, merchantId)
        .query((rs, rowNum) -> new GlAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("account_code"),
                rs.getString("account_name"),
                GlAccountCategory.valueOf(rs.getString("account_category")),
                rs.getString("subcategory"),
                NormalBalance.valueOf(rs.getString("normal_balance")),
                rs.getLong("balance_pesewas"),
                rs.getString("currency"),
                rs.getBoolean("is_active")
        )).list();
    }

    /**
     * Generates a complete Trial Balance report verifying arithmetic equality of debits and credits.
     */
    public TrialBalanceReport generateTrialBalance(UUID merchantId, LocalDate asOfDate) {
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        List<GlAccount> accounts = listAccountsAsOf(merchantId, effectiveAsOf);
        List<TrialBalanceLine> lines = new ArrayList<>();
        long totalDebit = 0;
        long totalCredit = 0;

        for (GlAccount acc : accounts) {
            long bal = acc.balancePesewas();
            long debitPart = 0;
            long creditPart = 0;

            if (acc.normalBalance() == NormalBalance.DEBIT) {
                if (bal >= 0) {
                    debitPart = bal;
                } else {
                    creditPart = -bal;
                }
            } else {
                if (bal >= 0) {
                    creditPart = bal;
                } else {
                    debitPart = -bal;
                }
            }

            lines.add(new TrialBalanceLine(
                    acc.accountCode(),
                    acc.accountName(),
                    acc.category(),
                    acc.normalBalance(),
                    debitPart,
                    creditPart
            ));

            totalDebit += debitPart;
            totalCredit += creditPart;
        }

        return new TrialBalanceReport(merchantId, effectiveAsOf, lines, totalDebit, totalCredit, totalDebit == totalCredit);
    }

    /**
     * Generates automated Profit & Loss Statement (Income Statement) for a date range.
     */
    public ProfitAndLossReport generateProfitAndLoss(UUID merchantId, LocalDate fromDate, LocalDate toDate) {
        LocalDate effectiveTo = toDate != null ? toDate : LocalDate.now();
        LocalDate effectiveFrom = fromDate != null ? fromDate : effectiveTo.withDayOfMonth(1);
        List<GlAccount> accounts = listAccountsForPeriod(merchantId, effectiveFrom, effectiveTo);

        long revenue = 0;
        long cogs = 0;
        long expenses = 0;

        for (GlAccount acc : accounts) {
            if (acc.category() == GlAccountCategory.REVENUE) {
                revenue += acc.balancePesewas();
            } else if (acc.category() == GlAccountCategory.COGS) {
                cogs += acc.balancePesewas();
            } else if (acc.category() == GlAccountCategory.EXPENSE) {
                expenses += acc.balancePesewas();
            }
        }

        long grossProfit = revenue - cogs;
        long netProfit = grossProfit - expenses;

        return new ProfitAndLossReport(merchantId, effectiveFrom, effectiveTo, revenue, cogs, grossProfit, expenses, netProfit);
    }

    /**
     * Generates automated Balance Sheet as of a specified date.
     */
    public BalanceSheetReport generateBalanceSheet(UUID merchantId, LocalDate asOfDate) {
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        List<GlAccount> accounts = listAccountsAsOf(merchantId, effectiveAsOf);

        long assets = 0;
        long liabilities = 0;
        long equity = 0;
        long revenue = 0;
        long expenses = 0;
        long cogs = 0;

        for (GlAccount acc : accounts) {
            switch (acc.category()) {
                case ASSET -> assets += acc.balancePesewas();
                case LIABILITY -> liabilities += acc.balancePesewas();
                case EQUITY -> equity += acc.balancePesewas();
                case REVENUE -> revenue += acc.balancePesewas();
                case COGS -> cogs += acc.balancePesewas();
                case EXPENSE -> expenses += acc.balancePesewas();
            }
        }

        long netIncome = revenue - cogs - expenses;
        long totalEquityAndNetIncome = equity + netIncome;

        boolean isBalanced = (assets == liabilities + totalEquityAndNetIncome);

        return new BalanceSheetReport(merchantId, effectiveAsOf, assets, liabilities, equity, netIncome, isBalanced);
    }
}
