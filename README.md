# ELEPAY-Core

Java payment services for ELEPAY, built with Spring Boot and PostgreSQL.

ELEPAY is a Ghana-first wallet and payment processor. It runs its own ledger and handles wallets, send-by-phone with recipient name confirmation, multi-merchant checkout, refunds, disputes, settlement, domestic payouts, FX conversion for merchant collections and USSD.

## Build and test

Requires Java 25, Maven, Node 22 and Docker (Testcontainers starts PostgreSQL 18).

```
mvn -B verify                 # unit, Spring Modulith, PostgreSQL integration and acceptance suites
cd web && npm ci && npm run typecheck && npm test && npm run build
```

Run isolated local test fixtures using `mvn -B -Dspring-boot.run.profiles=sandbox,dev -Dspring-boot.run.main-class=com.elepay.SandboxApplication spring-boot:test-run` against a test PostgreSQL 18 database and Redis, then run `python3 scripts/smoke.py` and `python3 scripts/acceptance.py`. The production JAR excludes simulated payment implementations. For production deployment, use the `prod` profile and [production deployment configuration](deploy/docker-compose.prod.yml) with approved provider credentials and externally managed secrets.

## Documentation

- [Product specification](docs/product-specification.md)
- API: [OpenAPI contract](docs/openapi.json), [wallet](docs/wallet-api.md), [merchant and checkout](docs/merchant-api.md), [payouts](docs/payouts-api.md), [FX](docs/fx-api.md), [USSD](docs/ussd.md)
- [Configuration](docs/configuration.md) and [operator runbooks](docs/runbooks/README.md)

## Connection status

**Bank and mobile-money API integration testing has been completed, as reported by the project owner on 24 November 2026.** This includes Ecobank banking and MTN MoMo integration testing. ELEPAY defaults to the production profile; local simulated rails remain isolated from the production artifact. Provider testing is distinct from live production activation: production credentials, provider onboarding/authorization, and live settlement and reconciliation evidence must be verified separately. Card-network production connectivity has not been confirmed. No real GRA submission integration exists; **GRA integration NOT IMPLEMENTED - manual submission required.** Historic e-Levy data is retained for audit only: e-Levy was repealed in April 2025 and must not be charged. International card acceptance and FX conversion remain within scope; recipient payouts and remittances abroad are excluded.


## Maintainer verification and operational handover

Run `python3 scripts/verify_fintech_scope.py`, `mvn -B clean verify`, and `cd web && npm ci && npm run typecheck && npm test && npm run build` before merging. The source guard is a supplemental test, not evidence of live rails. Review `docs/security-operations.md` for required configuration, legacy vault migration, CI setup, and security controls. CI can only be called green when the jobs for the exact commit actually run and pass.
