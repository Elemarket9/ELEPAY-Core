# MobileMoney Limited (MML) Anti-Money Laundering Questionnaire — ELEPAY Response

> Prepared from `MML AML Compliance Questionaire.pdf` (MTN Ghana MobileMoney Limited).
> Technical and control answers below reflect implemented capabilities in `ELEPAY-Core`. Complete the `[FILL IN]` corporate/legal fields before signing and submitting to MML.

## Company & Ownership Details
- **Company Name & Registered Office:** `[FILL IN: Legal Name & Registered Address in Ghana]`
- **Shareholders >= 20%:** `[FILL IN: Name, %, Country of Incorporation/Residence, DOB]`
- **Board Members:** `[FILL IN: Name, Title, Country of Residence, DOB]`
- **Senior Management Members:** `[FILL IN: Name, Title, Country of Residence, DOB]`
- **Regulatory Authority & License:** Bank of Ghana (Payment Systems and Services Act, 2019 — Act 987) — `[FILL IN: PSP License / Partner Bank Sponsor Details & Renewal Date]`

---

## 1. General AML Policies, Practices and Procedures
- **1A. Board-approved Group AML policy:** `YES` (attach signed AML/CFT Policy document)
- **1B. AML policy covers:**
  - KYC including Sanctions & PEP screening: `YES` — implemented in `SanctionsScreeningService.java` and `PepScreeningService.java`
  - Prevention, detection & reporting of suspicious activity: `YES` — implemented in `AmlMonitoringService.java`, `StructuringDetectionService.java`, and `RegulatoryReportingService.java`
  - AML training for staff: `YES` (attach staff & agent AML training materials)
  - Statutory record retention: `YES` — implemented in `Retention.java` and `RetentionRedactionService.java` (append-only ledger & audit tables protected by PostgreSQL immutability triggers in `V49`)
  - Agent due diligence & training: `YES` (where agent network is active)
- **1D. Designated MLRO / Compliance Officer:** `YES` — `[FILL IN: MLRO Name, Email, Phone]`
- **1E. Written STR processes:** `YES` — FIC Ghana suspicious transaction reporting workflow supported by `RegulatoryReportingService.java`
- **1F. Independent / internal audit function:** `YES` — `[FILL IN: Internal / External Auditor Details]`
- **1G. PEP policies (including family & close associates):** `YES` — `PepScreeningService.java`
- **1H. Record retention complying with Ghanaian law (Act 1044 / Act 987):** `YES` — minimum 5–7 year immutable ledger/audit retention (`Retention.java`, `AuditCompliance.java`)
- **1I. Applied across all branches & subsidiaries:** `YES`

## 2. Agent Due Diligence
- **Agency structure & controls:** ELEPAY operates primarily as a merchant acquiring, POS, and payout platform; where agent banking is enabled, all agents/sub-agents undergo full KYC/KYB, UBO identification, Sanctions/PEP screening, premise verification, and ongoing transaction monitoring prior to activation.
- **Unacceptable risk escalation:** Immediate merchant/agent freeze via `KillSwitchController` (`POST /v1/ops/kill-switch/merchants/{id}`), API key revocation, and Maker-Checker dual-control review (`MakerCheckerService`).

## 3. Risk Assessment
- **Risk-based customer & transaction assessment:** `YES` — automated risk scoring (`RuleEngineService.java`, `GradientBoostedFraudModel.java`, `SyntheticIdentityService.java`) + velocity ceilings.
- **Enhanced Due Diligence (EDD):** `YES` — triggered for high-risk merchants, PEPs, large payouts, and elevated velocity tiers.

## 4. Customer Due Diligence (CDD) & KYC
- **4A–4I:** `YES` — ELEPAY collects full legal name, residential/business address, DOB, nationality, Ghana Card / ID type & number, and business activity during merchant & wallet onboarding.
- **4J. Identity verification process:** Ghana Card verification (`GhanaCardVerificationService.java`), mobile number normalization & MTN `ValidateAccount` (`GET /accountholder/msisdn/{msisdn}/active`), and manual compliance review in Ops Console (`Onboarding` tab) before activating merchant accounts.
- **4K. KYC evidence storage:** Encrypted at rest using envelope encryption (`KmsSecretProvider` / `EnvelopeEncryption`), strictly access-controlled via RBAC (`Actor` roles), and retained per statutory requirements.
- **4L. Sanctions & PEP screening process:** Automated screening at onboarding and on disbursement/withdrawal (`SanctionsScreeningService.java`, `PepScreeningService.java`, `EnterpriseWithdrawalComplianceService.java`).

## 5. Suspicious Activity Reporting & Transaction Monitoring
- **5A–5G:** `YES` — real-time transaction monitoring (`AmlMonitoringService.java`), structuring/smurfing detection (`StructuringDetectionService.java`), alert prioritization (`AlertPrioritizationService.java`), mandatory threshold & STR reporting (`RegulatoryReportingService.java`), and sanctions screening.

## 6. Record Retention & Transaction Sharing with MML
- **6A–6B:** `YES` — every transaction (successful, pending, or failed) is assigned a unique UUID/reference in `rail_transaction` and append-only `ledger_entry` tables.
- **6C. Sharing required transaction fields with MML:** `YES` — ELEPAY provides a dedicated Compliance export (`GET /v1/ops/compliance/mml-transactions` and **Export MML AML Transactions (CSV)** in Ops Console → Custodian Trust) outputting:
  - `Transaction ID` & `MTN Financial ID`
  - `Transaction Date/Time`
  - `Transaction Amount (GHS)`
  - `Transaction Status` (successful & failed)
  - `Credit Customer Name`
  - `Debit Customer Name`
- **6D. Secure availability to AML officer:** `YES` — restricted to `risk_compliance`, `finance_ops`, and `ops_admin` roles.

## 7–9. Systems, Audit & Business Continuity
- **7A. AML Software:** `YES` — built-in ELEPAY AML/Fraud monitoring engine (`com.elepay.compliance` + `com.elepay.risk`).
- **7B. PCI-DSS Certification:** `[FILL IN: Current SAQ / PCI-DSS scope — note ELEPAY does not store raw card PAN/CVV; card checkout delegates to certified card rail and NFC uses DUKPT/redacted EMV TLV]`
- **8A–8C. External & Internal Audits:** `[FILL IN: Auditor firm name & last review date]`
- **9A–9I. Business Continuity Plan (BCP):** `[FILL IN: BCP Manager name & last drill date; technically backed by PostgreSQL automated backups, Redis distributed locks, offline-first KDS sync, and idempotent rail recovery jobs]`
