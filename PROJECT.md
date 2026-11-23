# ELEPAY-Core — engineering and product status

## Product boundary
ELEPAY is strictly fintech. Core financial functions are wallet accounts, append-only double-entry ledger, domestic Ghana payments and collections, provider-neutral multi-merchant payment allocations, financial escrow, settlement, payouts and reconciliation, FX conversion, treasury, reserves, lending, virtual cards, POS payment handling, bill payment, agent banking, compliance (KYC/AML), fraud controls, security, merchant financial BackOffice, developer APIs and webhooks.

Inventory, product catalogs, purchasing, warehouses, retail POS held carts, restaurant operations, CRM, loyalty, gift cards, campaigns, marketing and non-financial merchant payroll are excluded. A POS payment record or financial receipt is in scope; ELEPAY does not manage the items sold.

**International payouts / remittance EXCLUDED from current scope per founder decision Nov 23 2026. Only domestic settlement.** International card acceptance and foreign-currency merchant collections remain in scope; FX conversion and merchant settlement must end at authorized domestic Ghana destinations.

## External integrations and compliance status
**Live bank, MoMo, card networks: NOT YET CONNECTED. Local testing uses sandbox/test fixtures only.**

**GRA integration NOT IMPLEMENTED - manual submission required.** No generated local fiscal receipt, signature or acknowledgment may be presented as a successful agency submission.

**E-Levy repealed April 2025 - no longer charged. Historical only.** Historical audit records must not be changed or used to originate a deduction.

## Engineering
Java 25, Spring Boot 4.2.0, Spring Modulith 2.2.0, PostgreSQL 18, Flyway, Maven. Preserve the modular monolith, the append-only double-entry ledger, accounting idempotency and the separation of external providers from internal financial state. Do not edit applied Flyway migrations; introduce only new migration versions for approved data structure changes.

## Readiness and evidence
The original 100-algorithm inventory describes source implementations, **implemented in code, not yet proven in production**. The register and old milestone records are not evidence of operational readiness. Re-run backend, frontend, integration, Flyway, security and ledger integrity tests at the exact commit. Mark CI green only when a successful run is observed; simulated/test payment rails do not imply live partner connection.

Historical versions of removed features remain available in Git commit history for audit, not reactivation.
