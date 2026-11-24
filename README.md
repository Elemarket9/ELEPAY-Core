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

### MTN MoMo

**Provider sandbox integration: passed (reported 24 November 2026).** Following [PR #39](https://github.com/Elemarket9/ELEPAY-Core/pull/39), the integration test report records 47/47 payment scenarios and 12/12 database-backed lifecycle tests passing against MTN's live sandbox at commit `1b709f9`. On the initial run, 46/47 scenarios passed; one slow upstream response produced an unknown outcome that was handled without fabricating success, and the scenario passed on two reruns. Reported coverage includes collections, declines, expired prompts, payouts, refunds, failed-payout release, duplicate prevention after a lost response, and operational alerting for repeated payout failures. These results are from the supplied integration report, not an independent reproduction.

### Ecobank and card acquiring

The project owner has separately reported bank integration testing, but the supplied MTN report lists Ecobank as untested at the time of that report. Ecobank test results and production connectivity therefore remain unverified in this repository. Card-network production connectivity has not been confirmed.

### Production activation

ELEPAY defaults to the production profile; local simulated rails remain isolated from the production artifact. Provider sandbox testing is not live-money activation. Before enabling MTN production traffic, confirm production credentials and onboarding, production firewall behavior, and an authorized low-value charge, payout and refund with settlement and reconciliation evidence. Rotate any credentials previously shared in chat. Do not store credentials or temporary provider test harnesses in Git.

 No real GRA submission integration exists; **GRA integration NOT IMPLEMENTED - manual submission required.** Historic e-Levy data is retained for audit only: e-Levy was repealed in April 2025 and must not be charged. International card acceptance and FX conversion remain within scope; recipient payouts and remittances abroad are excluded.


## Maintainer verification and operational handover

Run `python3 scripts/verify_fintech_scope.py`, `mvn -B clean verify`, and `cd web && npm ci && npm run typecheck && npm test && npm run build` before merging. The source guard is a supplemental test, not evidence of live rails. Review `docs/security-operations.md` for required configuration, legacy vault migration, CI setup, and security controls. CI can only be called green when the jobs for the exact commit actually run and pass.
