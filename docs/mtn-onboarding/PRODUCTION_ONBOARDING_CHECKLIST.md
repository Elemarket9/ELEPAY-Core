# MTN Ghana MobileMoney Limited (MML) Production Onboarding Checklist

Status of every document from `ghana_kyc_document.zip` against `ELEPAY-Core`:

## Technical & Compliance Readiness (Implemented in Code)
- [x] **Collections API (`RequestToPay` POST/GET):** Implemented in `MtnProductionRail.java`
- [x] **Disbursements API (`Transfer` POST/GET & `Refund` v2/v1):** Implemented in `MtnProductionRail.java`
- [x] **ValidateAccount API (`accountholder/msisdn/{msisdn}/active`):** Implemented in `MtnProductionRail.validateAccountHolder` + `GET /v1/ops/mtn/validate-account` + Ops Console UI
- [x] **Account Balance API (`account/balance` for Collection & Disbursement):** Implemented in `MtnProductionRail.getAccountBalance` + `GET /v1/ops/mtn/balance` + Ops Console UI
- [x] **MML AML §6C Transaction Extract (6 required columns, success & failed):** Implemented in `GET /v1/ops/compliance/mml-transactions` + **Export MML AML Transactions (CSV)** button in Ops Console → Custodian Trust
- [x] **2-Level Disbursement Authorization (Level A + Level B):** Implemented via `MakerCheckerService`
- [x] **Automated 5-Sheet UAT Runner:** Implemented in `scripts/mtn_uat_runner.py` (outputs CSV ready for `MTN MoMo OpenAPI_UAT Testcases .xlsx`)

## Corporate & Legal Paperwork to Complete & Sign
1. **Application Forms** (sign & stamp):
   - `MTN MOMO Open API APPLICATION FORM - Collections.pdf`
   - `MTN MOMO Open API APPLICATION FORM - Disbursement.pdf`
   - *(Optional, if using widget/remittance)* `Collection Widget.pdf` / `International Remittance.pdf`
2. **Partner Agreements** (legal review & signature):
   - `Open API - MML and Vendor Collections Agreement-revised.pdf`
   - `MML Partner Agreement - Open API Disbursement -revised.pdf`
3. **Mandate & Self-Liquidation Forms** (2 director signatures + bank details):
   - `MTN MoMo Open API Mandate Form.pdf`
   - `MTN MoMo Open API Liquidation Service.pdf` (link your Ghanaian settlement bank account)
4. **AML Questionnaire & Supporting Docs**:
   - Transfer answers from `docs/mtn-onboarding/MML_AML_QUESTIONNAIRE_RESPONSE.md` into `MML AML Compliance Questionaire.pdf`
   - Attach: Certificate of Incorporation & Commencement of Business, Directors' & UBOs' Ghana Cards/Passports, Shareholders Register, Bank of Ghana license/partner bank approval, AML Policy & Staff Training Manual
5. **Product Details & UAT Sign-off**:
   - Transfer `docs/mtn-onboarding/PRODUCT_DETAILS_CUSTOMER_JOURNEY_ESCALATION.md` (with app screenshots) into MTN's `.docx` template
   - Run `python3 scripts/mtn_uat_runner.py --out mtn_uat_results.csv` against MTN Sandbox and paste results + ReferenceIDs into `MTN MoMo OpenAPI_UAT Testcases .xlsx`
