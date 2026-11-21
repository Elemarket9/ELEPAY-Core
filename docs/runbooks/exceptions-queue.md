# Operations exceptions queue

List: `GET /v1/ops/queue` (or the Operations console → Exceptions). Resolve with a note: `POST /v1/ops/queue/{id}/resolve {"resolution": "..."}`. There is one open item per kind and subject; repeats update its details.

| Kind | Meaning | Action |
| --- | --- | --- |
| `rail_status_conflict` | A rail reported success after ELEPAY recorded failure, or the reverse. Recorded state is kept. | Query the network by reference. If money really moved, post a correcting balanced entry through an approved adjustment (two-person review). Otherwise resolve as a stale notification. |
| `rail_amount_mismatch` | A rail confirmed a different amount; the transaction is `mismatch` and nobody was credited. | Confirm with the network statement. Refund the customer through the rail or credit the confirmed amount by adjustment. Never edit the original. |
| `rail_result_unknown` | Repeated status queries failed. Funds stay reserved; nothing is resent. | Contact the network with the ELEPAY reference. Do not create a new charge or payout. |
| `rail_unexpected_return` | A return arrived for a charge or an unfinished transaction. | Investigate with the network before any posting. |
| `callback_unknown_reference` | A verified callback referenced no known transaction. | Check whether it belongs to another environment; otherwise reconcile against the network statement. |
| `checkout_double_payment` | Defensive: a second confirmed charge for a paid checkout was parked in suspense. | Refund the second charge to the customer from suspense. |
| `refund_delivery_failed`, `dispute_refund_delivery` | Money is held in `refunds_in_flight` for a customer who paid through a rail. | Retry delivery through the rail or credit the customer's wallet with a balanced posting. |
| `dispute_unfunded` | The merchant lacked funds to hold for a dispute. | Contact the merchant; hold future settlements; escalate per credit policy. |
| `payout_returned` | A completed payout came back; funds are already back in available. | Contact the recipient owner; retry once with corrected details. |
| `payout_return_unexpected` | A return for a payout not marked succeeded. | Investigate with the network. |
| `recipient_lookup_abuse` | A sender repeatedly hit lookup limits. | Review `recipient_lookup` for the sender; freeze the wallet (`PUT /v1/ops/customers/{id}/status`) if enumeration is suspected. |
| `fx_status_conflict`, `fx_returned`, `fx_inbound_amount_mismatch`, `fx_recon_*` | FX partner differences. | See reconciliation.md. |
