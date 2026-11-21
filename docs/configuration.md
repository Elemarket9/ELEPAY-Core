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
