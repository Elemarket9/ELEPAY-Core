# Settlement and payouts

Settlement: due pending proceeds move to available every minute after each merchant's `settlement_delay_hours`. Operator run: `POST /v1/ops/settlements/run {"ignoreDelay": true, "merchantId": "…"}`. Each run creates one `settlement_batch` per merchant; merchants see them at `GET /v1/merchants/{id}/settlements`.

Withdrawals and payouts: each item reserves funds before sending. `failed` releases the funds; `returned` credits them back and raises `payout_returned`. Retry only through `POST /v1/merchants/{id}/payouts/{reference}/retry`, which allows one retry per failed or returned item; never re-enter a payout by hand.

Withdrawals require a verified settlement destination. When no network name enquiry is connected, verify the account holder against documents, then `POST /v1/ops/merchants/{id}/settlement-destinations/{destination}/verify`.
