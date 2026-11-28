# ELEPAY wallet sandbox

Use Java 25, Maven, Redis and a dedicated local PostgreSQL 18 test database. Configure `ELEPAY_DB_URL`, `ELEPAY_DB_USER` and `ELEPAY_DB_PASSWORD` for the test database. Do not point the sandbox at a production database or use live payment credentials.

```sh
mvn -B -Dspring-boot.run.profiles=sandbox,dev -Dspring-boot.run.main-class=com.elepay.SandboxApplication spring-boot:test-run
```

From a separate terminal, run `python3 scripts/smoke.py` and `python3 scripts/acceptance.py`. The smoke suite uses fresh account names. Reusing an operation ID with identical input returns the original reference; conflicting input returns HTTP 409. Refunds reference the checkout UUID and allocation index and require sufficient recipient balance.

See [test infrastructure](../TEST_INFRA.md) for optional HTTP regression tests. The test-classpath simulator is excluded from the production JAR.
