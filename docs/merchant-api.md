# ELEPAY merchant and checkout API

Amounts are integer pesewas (GHS). Merchant routes accept an ELEPAY user token or a merchant API key in `X-Elepay-Api-Key`. A caller with no role at a merchant receives 404 for that merchant.

## Roles

| Role | View | Finance view | Collect | Refund | Withdraw | Manage |
| --- | --- | --- | --- | --- | --- | --- |
| owner, admin | yes | yes | yes | yes | yes | yes |
| finance | yes | yes | yes | yes | yes | no |
| cashier (one branch) | own branch | no | own branch | no | no | no |
| API key | yes | yes | yes | yes | no | no |
| operations | read only | read only | no | no | no | no |

## Onboarding

| Endpoint | Purpose |
| --- | --- |
| POST /v1/merchants | Apply as `merchant`, `delivery` or `marketplace`. The applicant becomes owner. Status starts as `pending_review`. |
| GET /v1/ops/merchants, POST /v1/ops/merchants/{id}/review | Operations approve, reject or suspend, and set the agreed fee (`feeBps`, `feeFixed`) and `settlementDelayHours`. |
| POST/GET /v1/merchants/{id}/branches | Branches. |
| PUT/DELETE/GET /v1/merchants/{id}/members/{subject} | Assign `owner`, `admin`, `finance` or `cashier` (cashiers need `branchId`). |
| POST/DELETE/GET /v1/merchants/{id}/settlement-destinations | MoMo or bank destinations. Changes are audited. |
| POST /v1/merchants/{id}/sellers, POST /v1/merchants/{seller}/marketplaces/{id}/accept | A marketplace invites a seller or delivery partner by merchant code; the seller accepts. |
| POST/GET/DELETE /v1/merchants/{id}/api-keys, POST /v1/merchants/{id}/webhooks | Developer access. Webhooks are signed `Elepay-Signature: t=…,v1=hex(HMAC-SHA256(secret, t + "." + body))` and carry `Elepay-Event-Id`. Delivery is at least once; deduplicate by event id. |
| GET /v1/merchants/{id}/balances | Pending, available and reserved balances. |

## Checkout

| Endpoint | Purpose |
| --- | --- |
| POST /v1/merchants/{id}/checkouts | `Idempotency-Key` required. Send `amount` for a single-merchant sale, or `allocations` (`merchantId`, `kind` = `sale`/`delivery`, `amount`, `description`). Allocations to other merchants require an active marketplace relationship. Each allocation's merchant fee comes from that merchant's agreement. |
| GET /v1/public/checkouts/{reference} | Low-data view: merchant names, lines, subtotal and fee/total for each method. |
| POST /v1/checkouts/{reference}/pay/wallet | Signed-in customer pays from the wallet (`Idempotency-Key`). |
| POST /v1/public/checkouts/{reference}/pay | Guest pays by `momo` (phone, network) or `card` (`Idempotency-Key`). Repeating the request after a dropped connection returns the attempt already in flight; only one charge can be pending per checkout. |
| GET /v1/public/checkouts/{reference}/status | Resume and status under the same reference. |
| GET /v1/public/fees/quote?amount=&method= | Fee and total for any amount, e.g. 500 or 1000 pesewas. |
| POST/GET /v1/merchants/{id}/payment-links, GET /v1/merchants/{id}/qr | Payment links and QR payloads (link or merchant code). |
| POST /v1/public/links/{slug}/checkouts, POST /v1/public/merchant-codes/{code}/checkouts | Start a checkout from a link or merchant code. |
| POST /v1/merchants/{id}/checkouts/{reference}/allocations/{line}/refunds | Partial or full refund of one allocation (`Idempotency-Key`). The allocation's merchant or the collecting marketplace may refund. The agreed fee is returned proportionally. The final refund returns the remainder. |
| GET /v1/merchants/{id}/checkouts, /checkouts/{reference}, /transactions, /refunds, /settlements, /disputes | Reports. A seller sees only its own allocations of a marketplace checkout. |
| POST /v1/ops/disputes, POST /v1/ops/disputes/{id}/resolve | Hold a disputed amount in the merchant's reserved balance; release it (`merchant_won`) or return it to the customer (`customer_won`). |
| POST /v1/ops/settlements/run | Move due pending proceeds to available, one batch per merchant (`ignoreDelay` for an operator run). Runs every minute by default. |

## Money flow

Payment debits the customer's wallet, or the rail clearing account, once for the total. It credits each allocation's net to the merchant's pending balance and the fees to ELEPAY revenue, in one journal transaction. A refund debits the merchant (pending first, then available) and ELEPAY revenue for the returned fee. It credits the customer's wallet, or refunds-in-flight when the payment came through a rail. Settlement moves pending to available. Every movement is a balanced journal transaction linked to the checkout.

## Platform API

| Endpoint | Purpose |
| --- | --- |
| POST /v1/merchants/{id}/api-keys | `label`, `scopes` from `view`, `collect`, `refund`, `withdraw`, `payouts` (default `view`, `collect`, `refund`). The secret is shown once. |
| PUT/DELETE /v1/merchants/{id}/delegations/{platformMerchantId} | A merchant grants or revokes a platform's credentials specific permissions (for example `withdraw`) to act for it. Without a delegation, a platform key receives 404 for that merchant. |
| POST /v1/merchants/{id}/checkouts with `orderReference` | Creates a payment order bound to the platform's order reference (unique per collector). |
| GET /v1/merchants/{id}/orders/{orderReference} | Order status by the platform's reference. |
| POST /v1/merchants/{id}/checkouts/{reference}/cancel | Cancel an unpaid order. An order with a payment in flight must finish or fail first. |
| GET /v1/merchants/{id}/events?after={sequence} | Event recovery: every event concerning the merchant, in order, with stable `eventId`. Use it to fetch webhooks you missed. |
| POST /v1/merchants/{id}/withdrawals | Requires the `withdraw` scope (or an owner, admin or finance user). The destination must be a registered, verified destination of the same merchant. The amount is reserved atomically and the response carries the withdrawal reference and initial status. ELEPAY charges no withdrawal fee. |
| GET /v1/merchants/{id}/withdrawals, GET /v1/merchants/{id}/withdrawals/{reference} | History and status. Signed webhooks report `payout.succeeded`, `payout.failed` and `payout.returned`. |
| POST /v1/merchants/{id}/payout-batches | Requires the `payouts` scope. Paying other parties is separate from withdrawals. |

Settlement destinations are verified through the network's name enquiry when one is connected. Otherwise operations verify them from documents (`POST /v1/ops/merchants/{id}/settlement-destinations/{d}/verify`). A destination the network reports as not found is rejected.

Recurring or off-session payments need a customer mandate that the payment method supports. Mandates are not implemented yet; every payment needs explicit customer approval.
