# Operational security and financial integrity

ELEPAY's engineering target is the leading independent fintech infrastructure in Ghana. Maintain one Spring Boot deployment, module-owned tables, explicit public module contracts, and the authoritative double-entry ledger. No production simulator, fabricated partner status, test credential, fake card, billing voucher or identity approval may stand in for external evidence. Test fixtures belong exclusively in the test source set and never the deployable JAR. No native application is part of this change.

## Direct MTN integration

Enable only after obtaining Ghana production credentials from MTN: configure the production origin, target environment, collection and disbursement accounts, subscription keys, and API credentials. Confirm exact property names in `MtnProductionRail` and `application-prod.properties`. Live connectivity, provider-side reconciliation, refund support and partner certification must be verified separately. A missing rail or uncertain result never means success. Other domestic rails require official contracts and credentials; do not route them to MTN.

## Payment and evidence rules

POS digital tender uses a linked checkout and verified journal entry, not a manual success flag. Settlement requires explicit marketplace delivery/release instructions; a deadline is not verification. Production certification requires approved evidence, not simulated self-certification. Use server-generated request-signing v2 signatures and persistent nonce protection. Developer test credentials and webhooks are isolated from live execution.

## Build and test

Run `mvn -B verify` with a dedicated PostgreSQL test database and Redis. Sandbox demonstrations run from the test classpath using `mvn spring-boot:test-run -Dspring-boot.run.profiles=sandbox,dev`; never from the production JAR. CI scans the built JAR for test-only implementations. Existing test schemas and migration history are retained for compatibility. No deployed database has been altered by this pull request.

## Further integration boundaries

Card issuing/authorization based on test BINs, plaintext CVV/PAN, fabricated airtime/utility/school receipts, unofficial biometric scoring, and agent float operations not backed by ledger transfers are isolated from the production artifact. Keep real issuer, biller, agent and identity provider contracts separate from test fixtures; activate only with authenticated evidence and ledger reconciliation. Existing historical records from incomplete features require reconciliation before being treated as authoritative. External verification is not represented as completed.
