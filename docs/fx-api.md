# ELEPAY international payments and FX

Amounts are integer minor units of their own currency (`currency.exponent`; GHS, USD, GBP and EUR use 2).

## Corridors

A corridor is defined by sending country, receiving country, source and destination currency, funding method, partner and delivery method. Operations manage corridors with `PUT /v1/ops/fx/corridors/{code}`. Fees, limits, quote lifetime and estimated delivery are configured per corridor. Adding GBP or EUR is a configuration change: create a corridor with the new currency and a partner that supports it.

## Customer flow

| Endpoint | Purpose |
| --- | --- |
| GET /v1/fx/corridors | Active corridors. |
| POST /v1/fx/quotes | `corridor`, `sourceAmount`. Returns source amount, fee, total debit, partner-confirmed `rate`, `destinationAmount`, `expiresAt` and `estimatedDeliveryMinutes`. |
| POST /v1/fx/transfers | `quoteId`, `recipient` (`name`, `accountNumber`, bank details). `Idempotency-Key` required. Executes exactly the accepted quote. An expired quote returns `409 quote_expired`. A quote the partner no longer honours returns `409 quote_changed`. Either way the customer must review and approve a new quote. A quote can be executed once. |
| GET /v1/fx/transfers/{reference} | Status: `pending`, `paid`, `failed`, `returned` or `unknown`. |

## Ledger

Each transfer is one journal transaction that balances within each currency. For GHS to USD:

- GHS: customer wallet −total, ELEPAY revenue +fee, `system:fx_clearing:GHS` +source amount
- USD: `system:fx_clearing:USD` −destination amount, `system:fx_in_flight:USD` +destination amount

When the partner confirms delivery, in-flight USD moves to the partner clearing account. If the partner rejects the transfer, or the funds come back after delivery, ELEPAY posts the linked reversal of the original transaction. That refunds the customer's full GHS debit, including the fee. Inbound remittances (`POST /v1/fx/partners/{partner}/inbound`, signed by the partner) credit the recipient's GHS wallet against the partner's USD clearing account, through the same FX clearing pair. ELEPAY rejects an instruction whose destination amount does not match its rate.

## Partner notifications and reconciliation

Partner callbacks (`POST /v1/fx/partners/{partner}/callbacks`) are signature-verified and deduplicated. They are applied under a row lock; stale or contradictory statuses go to the operations queue. `POST /v1/ops/fx/recover` queries the partner for pending or unknown transfers. `POST /v1/ops/fx/reconcile/{partner}` matches statement lines by reference on both currencies' amounts and on status. It reports `matched`, `amount_mismatch`, `status_mismatch`, `missing_internal` and `missing_partner`, and raises operations items for every difference.

## Partner access

No remittance partner is connected yet. Western Union, MoneyGram, Remitly, Wise and Payoneer have been evaluated only as targets, and none has provided ELEPAY with contracted API access or a test environment. All FX results so far come from the simulated partner `sim-remit` (sandbox only, rates from configuration). They are not partner-test or live results.
