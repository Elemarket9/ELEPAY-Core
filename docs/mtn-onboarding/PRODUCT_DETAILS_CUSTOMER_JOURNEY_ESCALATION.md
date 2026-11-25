# MTN MoMo Open API — Product Details, Customer Journey & Escalation Matrix

> Prepared from `MML Open API Product Details_Customer Journey _Escalation Template.docx`.

## Business Details
- **Business Name:** `[FILL IN: ELEPAY Legal Entity Name]`
- **Business Address:** `[FILL IN: Registered Accra Office Address]`
- **Business Contact:** `[FILL IN: Primary Operations Phone]`
- **Business Email:** `[FILL IN: ops@elepay.gh]`
- **Date:** `[FILL IN: Submission Date]`
- **Product Name:** **ELEPAY Payments, Restaurant POS & Merchant Settlement Platform**

---

## Product Description & Features
ELEPAY is a Ghanaian fintech payment acceptance, restaurant/retail Point-of-Sale (POS), and merchant payout platform built on a double-entry ledger and Bank of Ghana safeguarding controls.

**Core MTN MoMo Open API Integrations:**
1. **Collections (`RequestToPay`):** Customers pay merchants via ELEPAY Hosted Checkout (`/pay/{ref}`), Merchant Payment Links, Restaurant POS MoMo Push, or dynamic QR codes.
2. **Disbursements (`Transfer` & `Refund`):** Verified merchants withdraw settled balances to pre-approved, cooling-off-verified MTN MoMo wallets, execute staff payroll batches, and issue customer refunds.
3. **Account Validation (`ValidateAccount`):** Verifies MTN MoMo subscriber status (`GET /{product}/v1_0/accountholder/msisdn/{msisdn}/active`) before disbursement.
4. **Float Monitoring (`Balance`):** Real-time collection and disbursement float inquiries (`GET /{product}/v1_0/account/balance`) surfaced in ELEPAY Operations Console for treasury and reconciliation.

---

## Customer Journeys

### Journey 1: Merchant Checkout Collection (MTN MoMo)
1. Customer scans a merchant's ELEPAY QR code, enters a 6-digit merchant code, or opens a hosted payment link (`/pay/{ref}`).
2. Customer reviews itemized order total + transparent fee and selects **Mobile money → MTN MoMo**.
3. Customer enters their Ghana mobile number (`024/054/055/059...`), which ELEPAY normalizes to `233XXXXXXXXX`.
4. ELEPAY submits `POST /collection/v1_0/requesttopay` with an idempotent UUIDv5 `X-Reference-Id`.
5. Customer receives the MTN MoMo USSD approval prompt on their handset and enters their MoMo PIN.
6. ELEPAY polls `GET /collection/v1_0/requesttopay/{ref}` until terminal status (`SUCCESSFUL` / `FAILED`), posts balanced double-entry ledger journals, displays confirmation, and provides a downloadable itemized receipt.

### Journey 2: Restaurant POS In-Person MoMo Push
1. Cashier/waiter builds a dine-in or takeaway order in the **ELEPAY Restaurant POS** app (`apps/restaurant-pos` or web POS) and routes items to the **Kitchen Display System (KDS)**.
2. At payment, cashier selects **MoMo → MTN MoMo**, enters the customer's phone number, and taps **Send MoMo Push**.
3. Customer approves the USSD prompt on their phone; POS auto-polls every 3 seconds, confirms settlement, and prints/displays the thermal receipt.

### Journey 3: Merchant Settlement Withdrawal (Disbursement)
1. Merchant registers a payout destination (MTN MoMo wallet or bank account), verified via account check and subject to a 24-hour cooling-off lock.
2. Merchant requests a withdrawal in **ELEPAY Merchant App** (`apps/merchant-app`) or web dashboard.
3. ELEPAY runs AML/Sanctions/PEP screening, velocity/cap checks, and dual-control approval (Level A + Level B via Maker-Checker where threshold applies).
4. ELEPAY executes `POST /disbursement/v1_0/transfer`, verifies terminal status via `GET /disbursement/v1_0/transfer/{ref}`, and notifies the merchant.

---

## Escalation Matrix & Support Contacts

| Level | Team / Role | Scope & SLA | Contact Phone | Contact Email |
|---|---|---|---|---|
| **Level 1** | Merchant & Customer Support Desk | Failed prompts, receipt inquiries, account lookups (Response: 15 min) | `[FILL IN]` | `support@elepay.gh` |
| **Level 2** | Payments Operations & Reconciliation | Stuck/unknown MTN transactions, rail recovery, disputes, daily recon (Response: 30 min) | `[FILL IN]` | `ops@elepay.gh` |
| **Level 3** | Engineering & Infrastructure On-Call | API outages, WAF/TLS issues, webhook/queue incidents (Response: 15 min) | `[FILL IN]` | `engineering@elepay.gh` |
| **Level 4** | MLRO / Executive Escalation | AML/fraud alerts, regulatory/MML compliance requests, emergency kill switch (Immediate) | `[FILL IN]` | `compliance@elepay.gh` |
