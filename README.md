# ELEPAY-Core

Java payment services for ELEPAY, built with Spring Boot and PostgreSQL.

ELEPAY is a Ghana-first wallet and payment processor. It runs its own ledger and handles wallets, send-by-phone with recipient name confirmation, multi-merchant checkout, refunds, disputes, settlement, domestic payouts, FX conversion for merchant collections and USSD.

## Build and test

Requires Java 25, Maven, Node 22 and Docker (Testcontainers starts PostgreSQL 18).

```
mvn -B verify                 # unit, Spring Modulith, PostgreSQL integration and acceptance suites
cd web && npm ci && npm run typecheck && npm test && npm run build
```

Run local isolated sandbox/test fixtures with: `docker compose up --build`. Alternatively, run `java -jar target/elepay-core-*.jar --spring.profiles.active=sandbox,dev` against a PostgreSQL 18 database, then `python3 scripts/smoke.py` and `python3 scripts/acceptance.py`.

## Documentation

- [Product specification](docs/product-specification.md)
- API: [OpenAPI contract](docs/openapi.json), [wallet](docs/wallet-api.md), [merchant and checkout](docs/merchant-api.md), [payouts](docs/payouts-api.md), [FX](docs/fx-api.md), [USSD](docs/ussd.md)
- [Configuration](docs/configuration.md) and [operator runbooks](docs/runbooks/README.md)

## Connection status

**Live bank, MoMo, card networks: NOT YET CONNECTED. Local testing uses sandbox/test fixtures only.** No real GRA submission integration exists; **GRA integration NOT IMPLEMENTED - manual submission required.** Historic e-Levy data is retained for audit only: e-Levy was repealed in April 2025 and must not be charged. International card acceptance and FX conversion remain within scope; recipient payouts and remittances abroad are excluded.

