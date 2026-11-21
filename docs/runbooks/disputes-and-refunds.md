# Disputes and refunds

- Open: `POST /v1/ops/disputes {checkout, line, amount, reason, caseReference}`. This moves the amount from the merchant's pending or available balance into reserved.
- Resolve: `POST /v1/ops/disputes/{id}/resolve {"outcome": "merchant_won" | "customer_won"}`. Customer-won returns funds to the wallet, or to refunds-in-flight for rail payments (see `dispute_refund_delivery`).
- Refunds are created by the merchant or marketplace per allocation. The agreed fee is returned proportionally. A refund larger than the merchant's balance is refused (`insufficient_funds`); collect from the merchant first.
