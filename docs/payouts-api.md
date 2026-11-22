# ELEPAY payouts API

| Endpoint | Purpose |
| --- | --- |
| POST /v1/merchants/{id}/withdrawals | Withdraw `amount` to an active settlement destination (`destinationId`). Owner, admin or finance role; `Idempotency-Key` required. |
| POST /v1/merchants/{id}/payout-batches | Up to 500 items, each with a unique `reference`, `amount`, `type` (`momo`/`bank`), `network` (MoMo network or bank code), `accountNumber`, `accountName` and optional `platform` (`bolt`, `yango`, `x`, `tiktok`). `Idempotency-Key` required. |
| GET /v1/merchants/{id}/payout-batches/{reference}, GET /v1/merchants/{id}/payouts | Batch status with per-item status and counts. |
| POST /v1/merchants/{id}/payouts/{reference}/retry | Retry one `failed` or `returned` item as a new item with a new reference. |
| GET /v1/ops/platform-payout-routes | Evidence register for Bolt, Yango, X and TikTok routes. |

Each item is processed independently:

- `rejected`: beneficiary verification found no such account. Nothing was reserved.
- `reserved` / `submitted`: the amount moved from available to reserved and the instruction was sent with a unique rail reference. Timeouts stay here until a callback or status query resolves them; ELEPAY never sends a second instruction for the same item.
- `succeeded`: reserved funds left through the rail.
- `failed`: reserved funds returned to available.
- `returned`: a completed payout came back from the receiving network. Funds are credited back to available and an operations item is raised.

A retry is allowed only for `failed` or `returned` items and only once per item; retrying a paid, pending or unknown item returns `409`. This guarantees a recipient cannot be paid twice for the same item.

Platform routes: no Bolt, Yango, X or TikTok partner interface or payout-destination eligibility has been verified. All routes are recorded as `not_verified`. Platforms that hold an ELEPAY merchant balance can pay drivers, couriers and creators today through payout batches with `platform` set on each item.

## Withdrawal on request or automatic

Settled merchant balances leave ELEPAY in one of two modes:

- `on_request` (default): the merchant calls `POST /v1/merchants/{id}/withdrawals` whenever it wants.
- `automatic`: every minute, a merchant's whole available balance is paid out to its verified destination once it reaches `minAmount` (pesewas). Automatic withdrawals appear in `GET /v1/merchants/{id}/withdrawals` with a client reference starting `auto-withdrawal-`. Withdrawals on request stay available in both modes.

Who decides:

| Endpoint | Purpose |
| --- | --- |
| PUT/GET /v1/merchants/{marketplace}/seller-payout-policy | The marketplace's default `mode` and `minAmount` for every seller and delivery partner in its network. Owner or admin. |
| PUT/DELETE/GET /v1/merchants/{marketplace}/sellers/{seller}/payout-policy | Override (or remove the override) for one seller. GET shows the seller's policy in force, without its bank details. |
| PUT/GET /v1/merchants/{id}/payout-policy | The merchant's own `mode` and `minAmount`, accepted only when no marketplace governs it (`409 governed_by_platform` otherwise), and `destinationId`, the verified destination for automatic withdrawals, which every merchant chooses for itself. |
| POST /v1/ops/payouts/automatic/run | Operations: run automatic withdrawals now. |

A merchant governed by marketplaces follows them; if several govern it and disagree, it is on request. Without a chosen destination, automatic withdrawal uses the merchant's only verified destination; with none, or several and no choice, the money stays in available and an `auto_withdrawal_no_destination` operations item is raised. Automatic withdrawals carry no ELEPAY fee. Set `elepay.payouts.auto-enabled=false` to stop the job, `elepay.payouts.auto-ms` to change its interval.
