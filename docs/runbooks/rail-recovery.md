# Rail recovery

Every external movement has a `rail_transaction` with ELEPAY's unique reference. States: `created` → `unknown`/`pending` → `succeeded`/`failed` (or `mismatch`, `returned`).

- Automatic: the recovery job queries due transactions every 15 seconds with exponential backoff.
- Manual: `POST /v1/ops/rails/recover` queries every open transaction immediately. `GET /v1/ops/rails/open` lists them.
- A charge that never reached the network (`created`, not found after 10 minutes) is failed. It is never sent late.
- A payout or refund not found at the network is resent with the same reference, which networks treat as idempotent.
- A timeout never creates a new charge or payout. Funds stay reserved until the network confirms.

Escalate if `elepay_rails_unresolved_over_1h` > 0 for 15 minutes.
