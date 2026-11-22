# Settlement and payouts

Settlement: due pending proceeds move to available every minute after each merchant's `settlement_delay_hours`. Seller and delivery lines of a marketplace checkout are due only after the marketplace confirms delivery (`POST /v1/merchants/{marketplace}/checkouts/{reference}/deliveries`), then after the seller's delay; lines unconfirmed at the order's `delivery_deadline` settle as delivered (`delivery_confirmed_by = 'auto:delivery_deadline'`). Each settling line also moves the marketplace's commission to its available balance and recognises ELEPAY's deferred fee as revenue (`fee_recognition` journal). `ignoreDelay` never releases a line awaiting delivery. Operator run: `POST /v1/ops/settlements/run {"ignoreDelay": true, "merchantId": "…"}`. Each run creates one `settlement_batch` per merchant; merchants see them at `GET /v1/merchants/{id}/settlements`.

Withdrawals and payouts: each item reserves funds before sending. `failed` releases the funds; `returned` credits them back and raises `payout_returned`. Retry only through `POST /v1/merchants/{id}/payouts/{reference}/retry`, which allows one retry per failed or returned item; never re-enter a payout by hand.

Withdrawals require a verified settlement destination. When no network name enquiry is connected, verify the account holder against documents, then `POST /v1/ops/merchants/{id}/settlement-destinations/{destination}/verify`.

Automatic withdrawals: merchants on `automatic` (chosen by their marketplace, or by themselves when independent) are paid out every minute; see `docs/payouts-api.md`. `auto_withdrawal_no_destination` means the merchant must add or choose a verified destination; nothing was paid.
