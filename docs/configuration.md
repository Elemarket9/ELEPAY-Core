# ELEPAY configuration

| Variable | Purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` in production. `sandbox,dev` enables simulated rails, the simulated FX partner and development tokens, and must never run with real money. |
| `ELEPAY_DB_URL`, `ELEPAY_DB_USER`, `ELEPAY_DB_PASSWORD` | PostgreSQL 18. Flyway migrates on start. |
| `ELEPAY_OIDC_ISSUER` | OIDC issuer for user tokens (prod). Tokens carry `sub` and a `roles` claim (`ops`, `ops_admin`); Keycloak `realm_access.roles` is also read. |
| `ELEPAY_BIND_ADDRESS` | Listen address (prod profile listens on 0.0.0.0 behind a TLS ingress). |
| `ELEPAY_MANAGEMENT_PORT` | Internal port for `/actuator/health` and `/actuator/prometheus` (prod, default 9090). Do not expose it publicly. |
| `ELEPAY_PUBLIC_BASE_URL` | Base URL used in payment links and QR payloads. |
| `ELEPAY_OUTBOX_ENABLED` | Outbox dispatcher and webhook sender (default true). |
| `ELEPAY_USSD_SECRET` | Shared secret of the connected USSD gateway (generic protocol). USSD stays disabled when unset. |
| `elepay.fees.customer.{wallet,momo,card}-{bps,fixed}` | Customer fees per payment method (default 0). Merchant fees are agreed per merchant at review. |
| `elepay.recipient-lookup.{ttl-seconds,per-minute,per-day,misses-per-hour}` | Name confirmation lifetime and lookup limits (300, 10, 100, 10). |
| `elepay.rails.recovery-ms`, `elepay.checkout.job-ms` | Rail status recovery and settlement/expiry job intervals. |
| `VITE_OIDC_ISSUER`, `VITE_OIDC_CLIENT_ID` | Docker build arguments for the web app's OIDC sign-in (authorization code with PKCE). |

Production deployment: build with `docker build --build-arg VITE_OIDC_ISSUER=… --build-arg VITE_OIDC_CLIENT_ID=… -t elepay .`. Run at least two replicas behind TLS against one PostgreSQL primary. The outbox dispatcher, webhook sender, rail recovery and settlement jobs are safe to run on every replica because they claim rows with `SKIP LOCKED` and post idempotently.

### Browser checkout approval and deployment

The browser submits `X-Elepay-Approved-Total` (positive integer minor units) on wallet and external-rail checkout payments. The service checks it against the amount calculated under the checkout row lock, before money moves. A `quote_changed` conflict requires a fresh quote and new approval; retries of an accepted logical request use the original key and approved total. The header is optional for existing API integrations for backward compatibility; integrators should supply it when they collect customer approval. Do not change fee schedules while relying on unverified client-only quotes.

The frontend OIDC issuer configured at build time must match the runtime resource-server issuer (HTTPS in production). The security policy permits the issuer origin only; if the identity provider advertises token/discovery endpoints on another origin, deployment requires explicitly reviewed configuration. Verify browser sign-in, callbacks and logout on the deployed HTTPS origin. Use only configured, approved payment rails for live money.
