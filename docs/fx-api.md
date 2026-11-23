# ELEPAY FX conversion and Ghana domestic settlement

ELEPAY retains foreign-issued card acceptance, foreign-currency merchant collections, FX rate sourcing and conversion pricing, currency-specific minor-unit accounting, treasury exposure and multi-currency reconciliation.

**International payouts / remittance EXCLUDED from current scope per founder decision Nov 23 2026. Only domestic settlement.**

Customer-facing international recipient money-sending and partner-originated remittance endpoints have been removed. Historical transfer records and their status reconciliation remain for audit and reconciliation of any previously recorded pending state; they cannot originate a new international payout.

## Supported FX administration and rate APIs

- `POST /v1/ops/fx/rates/sources`: ingest rate evidence.
- `POST /v1/ops/fx/rates/synthesize`: synthesize blended FX rates.
- `GET /v1/fx/rates/latest?from=USD&to=GHS`: inspect a rate.
- `POST /v1/fx/pricing`: calculate indicative FX conversion pricing.
- `GET /v1/ops/fx/exposure/{currency}`: treasury exposure.
- `POST /v1/ops/fx/reconcile-dual/{partner}`: reconcile both currency legs.
- `POST /v1/ops/fx/reconcile/{partner}` and `POST /v1/ops/fx/recover`: historical reconciliation.
- `POST /v1/fx/partners/{partner}/callbacks`: verified callbacks for previously recorded FX transactions.

Administrative corridor/routing endpoints remain for rate and settlement configuration and legacy evidence. They do not create international beneficiary payments.

**Live bank, MoMo, card networks: NOT YET CONNECTED. Local testing uses sandbox/test fixtures only.** No FX conversion, acquirer or domestic settlement partner should be described as live without verified contractual connectivity and real settlement records.
