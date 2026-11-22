## 2026-11-22T04:40:43Z
You are Worker 7 (Stream 2: Business Accounting, Invoicing, Payroll & CRM).
Working directory: /home/antigravity/Elemarket9/ELEPAY-Core
State folder: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_s2_accounting_payroll_crm
Original request: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/ORIGINAL_REQUEST.md
Project plan: /home/antigravity/Elemarket9/ELEPAY-Core/PROJECT.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. Integrity violations WILL be detected and your work WILL be rejected.

EXCLUSIVE SCOPE:
- Java packages: `src/main/java/com/elepay/accounting/`, `src/main/java/com/elepay/payroll/`, `src/main/java/com/elepay/crm/`
- Test packages: `src/test/java/com/elepay/accounting/`, `src/test/java/com/elepay/payroll/`, `src/test/java/com/elepay/crm/`
- Migration: `src/main/resources/db/migration/V23__accounting_payroll_crm.sql`

TASKS:
1. Domain 5 (Business Accounting & Invoicing):
   - Merchant General Ledger (GL) separate from customer ledger, standard Ghanaian chart of accounts (Assets, Liabilities, Equity, Revenue, COGS, Expenses), invoice and quote generator with payment link embedding, Accounts Receivable (AR) & Accounts Payable (AP) tracking, automated P&L statement, balance sheet, trial balance generation, and Ghana Revenue Authority (GRA) e-VAT fiscal data integration.
2. Domain 6 (Payroll & Supplier Payments):
   - Employee master records, Ghanaian statutory deductions calculator (SSNIT Tier 1 13.5%, Tier 2 5.5%, Tier 3 voluntary, GRA PAYE graduated tax bands), digital itemized payslips, bulk payroll approval and batch execution to mobile money/bank, supplier master records, supplier bill creation, approval workflow, and automated payment runs.
3. Domain 7 (CRM, Loyalty & Marketing Campaigns):
   - Merchant customer profiles, transaction history, RFM (Recency, Frequency, Monetary) segmentation, points-based and tiered loyalty programs, gift card issuance and redemption with PIN, dynamic coupon code engine, customer store credit balance ledger, SMS/WhatsApp marketing campaign templates and dispatch hooks.
4. Database & Verification:
   - Create Flyway migration V23, write comprehensive unit and integration tests, verify compilation and tests pass, commit and push to origin/main immediately. Report handoff when done.
