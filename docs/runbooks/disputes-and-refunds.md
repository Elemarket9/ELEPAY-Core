# Disputes and refunds

- Open: `POST /v1/ops/disputes {checkout, line, amount, reason, caseReference}`. This moves the amount from the merchant's pending or available balance into reserved.
- Resolve: `POST /v1/ops/disputes/{id}/resolve {"outcome": "merchant_won" | "customer_won"}`. Customer-won returns funds to the wallet, or starts a refund through the original MoMo or card rail automatically (a `refund` row linked from the dispute; failures raise `refund_delivery_failed`). Merchant-won returns the held seller net, marketplace commission and ELEPAY fee to where they came from: pending if the line has not settled, available and revenue if it has. Disputes opened before V28 resolve the old way.
- Refunds are created by the merchant or marketplace per allocation. The agreed fee is returned proportionally. A refund larger than the merchant's balance is refused (`insufficient_funds`); collect from the merchant first.
