## 2026-11-22T04:40:47Z
You are Worker 10 (Stream 5: Security Hardening, Defect Remediation & Production Infrastructure).
Working directory: /home/antigravity/Elemarket9/ELEPAY-Core
State folder: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_s5_security_infra
Original request: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/ORIGINAL_REQUEST.md
Project plan: /home/antigravity/Elemarket9/ELEPAY-Core/PROJECT.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. Integrity violations WILL be detected and your work WILL be rejected.

EXCLUSIVE SCOPE:
- Java packages: `src/main/java/com/elepay/common/`, `src/main/java/com/elepay/identity/`, `src/main/java/com/elepay/reliability/`
- Test packages: `src/test/java/com/elepay/common/`, `src/test/java/com/elepay/identity/`, `src/test/java/com/elepay/reliability/`
- Configurations: `src/main/resources/application*.properties`, `pom.xml`, `docker-compose*.yml`, `.github/workflows/`
- Migration: `src/main/resources/db/migration/V26__security_hardening.sql`

TASKS:
1. Domain 14 (Correct Financial & Security Defects):
   - Java toolchain clean alignment: ensure `pom.xml` builds cleanly under host JDK and in container.
   - Fix Recipient Balance Exposure: ensure `/v1/wallet/recipients/lookup` strictly returns only masked identity (`verifiedName`, `destination`, `confirmationId`, `expiresAt`) and never exposes internal UUIDs, ledger account IDs, or balances.
   - Request Signing Enforcement: enforce `RequestSigningFilter` across all non-public mutation endpoints with strict timestamp expiration (300s) and cached nonce replay rejection.
   - Dynamic Merchant Signing Secrets: generate and store cryptographically secure signing secrets per API credential.
   - Distributed Rate Limiting: enhance `TokenBucketRateLimiter` with distributed Redis backend fallback for multi-instance deployments.
2. Domain 15 (Production Infrastructure & Observability):
   - PostgreSQL High Availability connection pooling configuration (HikariCP tuned for production, connection leak detection, read/write split support).
   - Redis distributed locking (`DistributedLockService`) and distributed session management.
   - OpenTelemetry (OTEL) distributed tracing and Micrometer Prometheus metrics export endpoints for financial throughput, p99 latency, and ledger health.
   - Grafana dashboard definition templates in `deploy/monitoring/`.
   - Enhanced CI/CD pipeline in `.github/workflows/sandbox.yml` with automated test verification, security scans, and container build steps.
3. Verification:
   - Create Flyway migration V26, write comprehensive tests, verify build and tests pass cleanly, commit and push to origin/main immediately. Report handoff when done.

## 2026-11-22T05:05:22Z
[Message] sender=8bad7bfffd7b87726ff5069de7fe43f4
Heartbeat check on Stream 5 (Security Defect Remediation & Production Infra). Status of Java build alignment, recipient balance masking, request signing enforcement, Redis distributed controls, OTEL/Prometheus, and tests.
