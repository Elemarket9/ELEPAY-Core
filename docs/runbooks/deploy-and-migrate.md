# Deploy and migrate

1. CI must be green: `mvn -B verify` (unit, Modulith, Testcontainers PostgreSQL 18 suite, migration clean-install and upgrade tests, OpenAPI contract), web typecheck/test/build, and the sandbox smoke and acceptance scripts.
2. Take a PostgreSQL snapshot or confirm point-in-time recovery is current.
3. Deploy the new image. Flyway applies new numbered migrations on the first instance to start; the others wait on Flyway's lock. Migrations are additive: existing migrations are never edited and historical GHS data is preserved (`MigrationIT` upgrades a database populated at V3).
4. Check `GET /actuator/health/readiness` on every replica and `GET /v1/ops/ledger/integrity` (all zeros).
5. Rollback: redeploy the previous image. Additive migrations are backward compatible with the previous release. Never run `flyway clean` or undo migrations against production.

Changing the API: run `ELEPAY_WRITE_OPENAPI=1 mvn verify -Dit.test=OpenApiContractIT` and commit the updated `docs/openapi.json` together with the change.
