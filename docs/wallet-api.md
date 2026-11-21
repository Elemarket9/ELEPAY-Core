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
