# Access, keys and secrets

- Operations users get the `ops` role from the identity provider. Every ops action is audited (`GET /v1/ops/audit?subject=…`).
- Merchant roles: owner, admin, finance and cashier (one branch). Owners manage members at `/v1/merchants/{id}/members/{subject}`.
- API keys are scoped (`view`, `collect`, `refund`, `withdraw`, `payouts`). Only hashes are stored. Revoke with `DELETE /v1/merchants/{id}/api-keys/{key}`; revocation takes effect on the next request.
- Delegations let a platform act for a merchant (`/v1/merchants/{id}/delegations/{platform}`). Remove them when the relationship ends.
- USSD PIN lockouts clear after 30 minutes. A customer can reset the PIN from the signed-in app (`PUT /v1/wallet/ussd-pin`).
- Phone reassignment: `PUT /v1/ops/customers/{id}/phone`. This revokes open recipient confirmations, and confirmations are re-validated at execution.
- Secrets (database password, USSD secret, rail and partner credentials) come from the deployment secret store. Rotate the USSD secret together with the gateway.
