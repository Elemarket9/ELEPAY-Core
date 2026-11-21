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
