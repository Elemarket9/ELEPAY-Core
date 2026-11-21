# ELEPAY wallet API

Use an ELEPAY user bearer token. Amounts are integer pesewas. The current endpoints operate GHS wallets.

| Endpoint | Purpose |
| --- | --- |
| POST /v1/ops/customers | An operations user enrolls a verified identity using `subject`, `phone` and `displayName`. Enrollment creates available, pending and reserved accounts. |
| GET /v1/wallet | Read the signed-in customer's wallet accounts and balances. |
| GET /v1/wallet/statement | Read the signed-in customer's available-account entries. Pass ISO-8601 `from` and `to` timestamps for a range of up to 31 days. |
| POST /v1/wallet/transfers | Transfer `amount` to a customer UUID supplied as `recipient`. Send an `Idempotency-Key` header. |

A transfer response includes a `transactionId`, receipt `reference`, `amount` and `currency`. The transfer debits and credits the two accounts in one journal and stores linked records for both customers. Retry with the same key and request body after a connection failure. Reusing the key with different details returns HTTP 409.

Run `mvn verify` with Docker to let Testcontainers start PostgreSQL 18. To use an existing disposable PostgreSQL 18 test instance, set `ELEPAY_TEST_DB_URL`, `ELEPAY_TEST_DB_USER` and `ELEPAY_TEST_DB_PASSWORD`. The test user needs permission to create databases for migration tests.

## Send by phone with recipient name confirmation

| Endpoint | Purpose |
| --- | --- |
| POST /v1/wallet/recipients/lookup | Check one complete destination. Body: `rail` (`wallet`, `momo` or `bank`) plus `phone`, or `phone` and `network` (`mtn`, `telecel`, `airteltigo`), or `bankCode` and `accountNumber`. Local (`024…`) and international (`+233 24…`) forms of a Ghana number are the same destination. |
| POST /v1/wallet/transfers/preview | Body: `confirmationId`, `amount`. Returns the verified name, destination, amount, fee and total for the final confirmation screen. |
| POST /v1/wallet/transfers/confirmed | Body: `confirmationId`, `amount`, with an `Idempotency-Key` header. Executes the transfer to the confirmed recipient. |
| PUT /v1/ops/customers/{id}/phone, PUT /v1/ops/customers/{id}/status | Operations reassign a number or freeze/close a wallet. Both revoke outstanding confirmations and are audited. |

A lookup returns `status`: `verified` (with `verifiedName`, `confirmationId` and `expiresAt`), `not_found`, `recipient_unavailable` or `verification_unavailable`, plus a plain-language `message`. Only the registered or network-verified name is returned. No customer id, balance or profile field is exposed, and there is no search or bulk lookup.

A confirmation expires after 5 minutes and can be used once. It is bound on the server to the sender, normalized destination, verified recipient account and rail. The transfer request carries only the confirmation id, so changing the number, account or rail requires a new lookup. At execution the server re-checks that the recipient wallet is still active and still owns the number; otherwise it returns `409 confirmation_stale`. Expired or used confirmations return `409 confirmation_expired` or `409 confirmation_used`. Another sender's confirmation returns 404.

Lookups require a signed-in customer and are limited per sender (10 per minute, 100 per day, 10 unmatched numbers per hour by default). Exceeding a limit returns 429 and writes an audit record. Repeated blocking raises a `recipient_lookup_abuse` operations item. Destinations are stored only as hashes in the lookup log.

MoMo and bank names come from the connected network's verification interface. No live MoMo or bank connection is configured yet. In production such lookups return `verification_unavailable` and the transfer cannot proceed. The sandbox uses a simulated verifier. A confirmed MoMo or bank transfer reserves the amount in the sender's wallet and executes as a payout to the confirmed account. The receipt `status` is `submitted`, `succeeded` or `failed`; a failure releases the reservation.
