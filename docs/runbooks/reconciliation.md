# Reconciliation

Daily:

1. `GET /v1/ops/ledger/integrity`: `accountsWithDrift`, `unbalancedTransactions` and every `netByCurrency` value must be 0. Any non-zero value is a severity-1 incident: stop payouts (scale the payout-sending instances to zero or disable rails), page engineering, preserve the database.
2. Rail statements: compare network statements with `rail_transaction` (reference, amount, status). Sum of succeeded charges minus succeeded payouts and refunds equals the movement of `system:clearing:<rail>:GHS`; network fees appear in `system:rail_fees:GHS`.
3. FX partner statements: `POST /v1/ops/fx/reconcile/{partner}` with the statement lines (`reference`, both currencies' amounts, `status`). Every non-matched line raises an exceptions-queue item. Runs are listed at `GET /v1/ops/reconciliation/runs`.

Every balance is reconstructable: `SELECT * FROM ledger_account_reconstruction WHERE projected_balance <> journal_balance` must return no rows.
