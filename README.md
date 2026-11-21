# ELEPAY-Core

Java payment services for ELEPAY, built with Spring Boot and PostgreSQL.

ELEPAY is a Ghana-first wallet and payment processor. It runs its own ledger and handles wallets, send-by-phone with recipient name confirmation, multi-merchant checkout, refunds, disputes, settlement, payouts, FX transfers and USSD.

## Build and test

Requires Java 25, Maven, Node 22 and Docker (Testcontainers starts PostgreSQL 18).

```
mvn -B verify                 # unit, Spring Modulith, PostgreSQL integration and acceptance suites
cd web && npm ci && npm run typecheck && npm test && npm run build
```

Run locally with simulated rails: `docker compose up --build`. Alternatively, run `java -jar target/elepay-core-*.jar --spring.profiles.active=sandbox,dev` against a PostgreSQL 18 database, then `python3 scripts/smoke.py` and `python3 scripts/acceptance.py`.

## Documentation

- [Product specification](docs/product-specification.md)
- API: [OpenAPI contract](docs/openapi.json), [wallet](docs/wallet-api.md), [merchant and checkout](docs/merchant-api.md), [payouts](docs/payouts-api.md), [FX](docs/fx-api.md), [USSD](docs/ussd.md)
- [Configuration](docs/configuration.md) and [operator runbooks](docs/runbooks/README.md)

## Connection status

All money that enters or leaves ELEPAY in this repository moves through **simulated** rails (`sandbox`) and a simulated FX partner (`sim-remit`). No bank, mobile-money operator, card acquirer, remittance partner, USSD gateway or platform payout interface (Bolt, Yango, X, TikTok) is connected. Network adapters plug in behind the rail, name-verification, FX-partner and USSD-gateway interfaces without changing the ledger or checkout rules.
