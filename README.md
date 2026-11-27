# ELEPAY Core

**Payment infrastructure for merchants, marketplaces and digital platforms.**

ELEPAY is a Ghana-first payment processing and wallet platform built around a dedicated financial ledger. It supports domestic collections and payouts, multi-merchant checkout, wallet transfers, refunds, disputes, settlement and foreign exchange conversion for merchant collections.

## Platform capabilities

| Capability | Description |
| --- | --- |
| Wallets and transfers | Wallet accounts, ledger-backed balances and send-by-phone transfers with recipient name confirmation. |
| Merchant payments | Merchant collections and checkout, including multi-merchant payment allocation. |
| Settlement and payouts | Merchant settlement and domestic disbursements. |
| Refunds and disputes | Financial lifecycle management for refunds and disputes. |
| Foreign exchange | Currency conversion for merchant collections and international card acceptance integrations. |
| USSD | USSD payment services. |

ELEPAY focuses on domestic payments and payouts. International card acceptance and FX conversion are within scope; cross-border recipient payouts and remittances are outside the current product scope.

## Technology

- Java 25 and Spring Boot
- Spring Modulith
- PostgreSQL 18 and Redis
- Maven
- Web application with Node.js build tooling
- Docker and Testcontainers for integration testing

## Payment integrations

### MTN Mobile Money

**Integration status: sandbox validated — 24 November 2026.**

| Validation suite | Result |
| --- | --- |
| Payment scenarios | 47/47 passed |
| Database-backed lifecycle tests | 12/12 passed |

The integration test report for [PR #39](https://github.com/Elemarket9/ELEPAY-Core/pull/39) at commit `1b709f9` covers:

- Payment collections, declines and expired payment prompts
- Payouts, refunds and release of funds following failed payouts
- Duplicate transaction prevention and recovery after lost responses
- Uncertain transaction outcome handling and operational alerting

The initial payment suite recorded 46/47 passing scenarios. An upstream response delay left one transaction outcome unresolved; the affected scenario passed on two subsequent runs.

### Banking and card acquiring

Ecobank and card acquiring are part of the integration scope. Ecobank sandbox validation results are not documented in the MTN test report. Production connectivity is pending confirmation.

## Deployment and integration environments

ELEPAY defaults to its production profile. Simulated payment implementations are excluded from the production JAR, and local sandbox fixtures are isolated from production services.

Production payment activation requires provider onboarding, production credentials and end-to-end operational validation, including network controls, authorized charge, payout and refund flows, settlement and reconciliation. Sandbox validation is distinct from production activation.

Tax reporting currently uses manual submission to the Ghana Revenue Authority; automated GRA submission is not available. Historical e-Levy records are retained for audit purposes only; e-Levy charges are disabled following its repeal in April 2025.

For deployment configuration and operational procedures, see the [production Docker Compose configuration](deploy/docker-compose.prod.yml), [configuration guide](docs/configuration.md) and [security operations guide](docs/security-operations.md).

## Build and test

Requirements: Java 25, Maven, Node.js 22 and Docker. Integration tests use PostgreSQL 18 through Testcontainers.

```bash
mvn -B verify
cd web && npm ci && npm run typecheck && npm test && npm run build
```

For local sandbox development, configure a test PostgreSQL 18 database and Redis, then run:

```bash
mvn -B -Dspring-boot.run.profiles=sandbox,dev -Dspring-boot.run.main-class=com.elepay.SandboxApplication spring-boot:test-run
```

From a separate terminal, run the smoke and acceptance suites:

```bash
python3 scripts/smoke.py
python3 scripts/acceptance.py
```

For production deployments, use the `prod` profile, approved provider credentials and externally managed secrets. Keep credentials and temporary provider test harnesses out of version control.

## Documentation

| Resource | Description |
| --- | --- |
| [Product specification](docs/product-specification.md) | Product scope and requirements |
| [OpenAPI contract](docs/openapi.json) | API specification |
| [Wallet API](docs/wallet-api.md) | Wallet operations and transfers |
| [Merchant and checkout API](docs/merchant-api.md) | Merchant payments and checkout |
| [Payouts API](docs/payouts-api.md) | Domestic disbursements |
| [FX API](docs/fx-api.md) | Foreign exchange operations |
| [USSD](docs/ussd.md) | USSD integration |
| [Configuration](docs/configuration.md) | Application configuration |
| [Operator runbooks](docs/runbooks/README.md) | Operational procedures |
| [Security operations](docs/security-operations.md) | Security controls and deployment guidance |

## Maintainer checks

Run the following checks before merging changes:

```bash
python3 scripts/verify_fintech_scope.py
mvn -B clean verify
cd web && npm ci && npm run typecheck && npm test && npm run build
```

Validate CI results for the exact commit under review. Source-level scope checks complement automated testing; external payment connectivity is validated through provider integration tests.
