package com.elepay.merchanttools.accounting;

import java.util.List;

/**
 * Standard Ghanaian Chart of Accounts definition adhering to GRA and
 * Institute of Chartered Accountants Ghana (ICAG) accounting standards.
 */
public final class GhanaianChartOfAccounts {

    public record StandardAccount(
            String code,
            String name,
            GlAccountCategory category,
            String subcategory,
            NormalBalance normalBalance
    ) {}

    public static final List<StandardAccount> STANDARD_CHART = List.of(
            // Current Assets (1000-1499)
            new StandardAccount("1010", "Cash on Hand", GlAccountCategory.ASSET, "CURRENT_ASSET", NormalBalance.DEBIT),
            new StandardAccount("1020", "Bank Account - Operating", GlAccountCategory.ASSET, "CURRENT_ASSET", NormalBalance.DEBIT),
            new StandardAccount("1030", "Mobile Money Float (MTN/Telecel/AT)", GlAccountCategory.ASSET, "CURRENT_ASSET", NormalBalance.DEBIT),
            new StandardAccount("1040", "Accounts Receivable", GlAccountCategory.ASSET, "CURRENT_ASSET", NormalBalance.DEBIT),
            new StandardAccount("1050", "Merchandise Inventory", GlAccountCategory.ASSET, "CURRENT_ASSET", NormalBalance.DEBIT),
            new StandardAccount("1060", "Prepaid Expenses & Advances", GlAccountCategory.ASSET, "CURRENT_ASSET", NormalBalance.DEBIT),

            // Non-Current Assets (1500-1999)
            new StandardAccount("1510", "Property, Plant & Equipment", GlAccountCategory.ASSET, "NON_CURRENT_ASSET", NormalBalance.DEBIT),
            new StandardAccount("1520", "Accumulated Depreciation", GlAccountCategory.ASSET, "NON_CURRENT_ASSET", NormalBalance.CREDIT),

            // Current Liabilities (2000-2499)
            new StandardAccount("2010", "Accounts Payable (Trade Creditors)", GlAccountCategory.LIABILITY, "CURRENT_LIABILITY", NormalBalance.CREDIT),
            new StandardAccount("2020", "GRA e-VAT & Levies Payable", GlAccountCategory.LIABILITY, "CURRENT_LIABILITY", NormalBalance.CREDIT),
            new StandardAccount("2030", "SSNIT Contributions Payable (Tier 1 & 2)", GlAccountCategory.LIABILITY, "CURRENT_LIABILITY", NormalBalance.CREDIT),
            new StandardAccount("2040", "GRA PAYE Tax Withholding Payable", GlAccountCategory.LIABILITY, "CURRENT_LIABILITY", NormalBalance.CREDIT),
            new StandardAccount("2050", "Accrued Wages & Salaries", GlAccountCategory.LIABILITY, "CURRENT_LIABILITY", NormalBalance.CREDIT),

            // Equity (3000-3999)
            new StandardAccount("3010", "Owner's Equity / Paid-in Capital", GlAccountCategory.EQUITY, "EQUITY", NormalBalance.CREDIT),
            new StandardAccount("3020", "Retained Earnings", GlAccountCategory.EQUITY, "EQUITY", NormalBalance.CREDIT),

            // Operating Revenue (4000-4999)
            new StandardAccount("4010", "Merchandise Sales Revenue", GlAccountCategory.REVENUE, "OPERATING_REVENUE", NormalBalance.CREDIT),
            new StandardAccount("4020", "Service & Consultation Revenue", GlAccountCategory.REVENUE, "OPERATING_REVENUE", NormalBalance.CREDIT),
            new StandardAccount("4030", "Delivery & Convenience Fee Income", GlAccountCategory.REVENUE, "OPERATING_REVENUE", NormalBalance.CREDIT),

            // Cost of Goods Sold (5000-5999)
            new StandardAccount("5010", "Cost of Goods Sold (Merchandise)", GlAccountCategory.COGS, "DIRECT_COST", NormalBalance.DEBIT),
            new StandardAccount("5020", "Direct Freight & Import Duties", GlAccountCategory.COGS, "DIRECT_COST", NormalBalance.DEBIT),

            // Operating Expenses (6000-6999)
            new StandardAccount("6010", "Salaries & Wages Expense", GlAccountCategory.EXPENSE, "OPERATING_EXPENSE", NormalBalance.DEBIT),
            new StandardAccount("6020", "Employer SSNIT Tier 1 Expense (13.5%)", GlAccountCategory.EXPENSE, "OPERATING_EXPENSE", NormalBalance.DEBIT),
            new StandardAccount("6030", "Rent & Premises Expense", GlAccountCategory.EXPENSE, "OPERATING_EXPENSE", NormalBalance.DEBIT),
            new StandardAccount("6040", "Utilities (ECG Electricity & GWCL Water)", GlAccountCategory.EXPENSE, "OPERATING_EXPENSE", NormalBalance.DEBIT),
            new StandardAccount("6050", "Marketing, SMS & WhatsApp Campaigns", GlAccountCategory.EXPENSE, "OPERATING_EXPENSE", NormalBalance.DEBIT),
            new StandardAccount("6060", "Bank & Mobile Money Transaction Fees", GlAccountCategory.EXPENSE, "OPERATING_EXPENSE", NormalBalance.DEBIT)
    );

    private GhanaianChartOfAccounts() {}
}
