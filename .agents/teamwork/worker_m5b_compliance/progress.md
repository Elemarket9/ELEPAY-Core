# Progress — Milestone 5B: Compliance, Sanctions, PEP, AML & Security

Last visited: 2026-11-22T04:01:20Z
Status: In progress.
- Flyway migration V20 created (`src/main/resources/db/migration/V20__compliance_security.sql`).
- EnvelopeEncryption (075), KeyRotationService (076), JwksService (078) implemented in `com.elepay.security`.
- Implementing remaining security controls: AdminAnomalyDetector (081), SessionDeviceBindingService (082), MobileCertificatePinningService (083), CiSecurityScanner (084).
- Implementing compliance algorithms 040-048 in `com.elepay.compliance`.
- Unit/integration tests and readiness scorecard update in progress.
