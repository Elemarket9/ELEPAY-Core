# ELEPAY test infrastructure

The repository retains automated regression tests and test fixtures separately from the production artifact. Test results apply only to the exact commit and environment executed.

## Required CI checks

See [CI workflow](.github/workflows/sandbox.yml).

- Maven unit and integration tests, including PostgreSQL 18 and Redis, modularity, ledger invariants, settlement, security and rail failure handling.
- Web and mobile typechecking, tests and builds.
- Fintech scope verification, dependency and secret scanning, production JAR isolation, smoke and acceptance tests, and container packaging.

The separate four-tier Python HTTP suite in `tests/e2e/` is not currently executed in full by the main CI workflow. It must be run against a dedicated local test deployment; never run against production or use production credentials.

## Local verification

Follow the [sandbox instructions](docs/sandbox.md) and [README](README.md) to start an isolated test server.

```sh
python3 scripts/verify_fintech_scope.py
mvn -B clean verify
python3 scripts/smoke.py
python3 scripts/acceptance.py
python3 scripts/run_e2e.py --tier all --verify-ledger --report target/e2e-report.json --junit-xml target/e2e-report.xml
```

The HTTP suite requires a reachable server and passing executed tests; skipped or empty runs are not successful validation. Review remaining HTTP contracts against the current OpenAPI and controllers before using the suite as release evidence. Use the Maven FX tests for retained FX rates, pricing, treasury and reconciliation coverage. International recipient transfers and remittance origination are outside product scope.

## Financial invariants

Verify integer minor units, per-currency balanced journals, no ledger drift, idempotent retries, overdraft protection, tenant isolation, reconciliation, and fail-closed provider behavior. Never infer live integration or certification from a sandbox result.

Temporary reports belong under ignored build directories, not version control. Historical agent plans and sign-off narratives remain available through Git history.
