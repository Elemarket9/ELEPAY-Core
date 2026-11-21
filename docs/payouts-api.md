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
