# ELEPAY wallet sandbox

Use Java 21, Maven and a dedicated local PostgreSQL database named `elepay_sandbox` owned by the `elepay` database role. Set `ELEPAY_DB_PASSWORD` to the role password. Override the connection with `ELEPAY_DB_URL` and `ELEPAY_DB_USER` if needed.

Run `mvn spring-boot:run -Dspring-boot.run.profiles=sandbox`. The server binds to loopback. The sandbox API uses simulated funds and assumes a trusted local caller.

Run `python3 scripts/smoke.py` with the service running. It uses fresh account names each time. Operation IDs identify requests. Reusing an ID with an identical body returns the original operation reference. A different body returns HTTP 409. Refunds reference the checkout UUID and the zero-based allocation line. The refund requires sufficient recipient balance.
