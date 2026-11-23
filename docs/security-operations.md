# ELEPAY Security, CI/CD, and Human Operations Handover

This handbook is written for a human engineering team. The product is a strict fintech modular monolith; no AI dependency, deploy-vendor dependency, named marketplace, or hardcoded business configuration is required.

## Fintech boundary

Retain ledger, wallets, domestic collections/payouts, multi-merchant financial allocation, escrow, settlement, reconciliation, FX conversion into domestic Ghana merchant settlement, lending, virtual cards, bill-payment integration boundaries, POS financial evidence, fraud, KYC/AML, security, developer APIs and merchant financial dashboards.
Do not reintroduce obsolete E-Levy charging, fake agency submissions, or foreign-beneficiary remittances. Merchant tools were restored Nov 23 2026; gift cards and store credit must not hold live customer value until BoG confirms the e-money position.
International card acceptance and FX conversion are within scope; **international payouts / remittance remain excluded**.

## Reproducible verification

Required: Java 25 LTS, Maven 3.9+, PostgreSQL 18, Flyway, Redis 7+, Node.js 22, npm, Docker.

```bash
python3 scripts/verify_fintech_scope.py
mvn -B clean verify
(cd web && npm ci --no-audit --no-fund && npm run typecheck && npm test && npm run build)
(cd web && npm audit --audit-level=high)
# Load NVD_API_KEY securely before running the OWASP check:
mvn -B -Psecurity-scan -DskipTests verify -DnvdApiKey="$NVD_API_KEY"
docker build -t elepay-core:verification .
```

Use an isolated PostgreSQL test database or Testcontainers. Never test against live payment rails or production customer data. Source lint is supplemental, not proof of deployment correctness. Persist exact commit SHA, test logs, migration results, security scan output and SBOM as CI evidence.

## Secrets and legacy card tokens

Production uses externally supplied secrets only. Required examples include ELEPAY_VAULT_MASTER_KEY (32 random bytes, encoded as documented), ELEPAY_INTEGRITY_MAC_KEY, OIDC signing material and explicitly contracted provider credentials. Rotate secrets according to policy and after suspected exposure. Use TLS, least-privilege database roles, scoped partner/API credentials and authenticated administrative endpoints.

The former legacy PAN decryption fallback was committed to Git history in earlier releases and therefore should be treated as publicly exposed. Current source **does not** silently fall back to that key. The optional ELEPAY_VAULT_LEGACY_MASTER_KEY must be loaded only from a restricted secret manager for a controlled, audited historical-card migration.

Before migration, take an encrypted database backup and verify restoration. Enumerate legacy ciphertext, execute the existing v2 authenticated-encryption migration in a restricted environment, verify counts and card ownership, then remove the legacy secret. Coordinate with the card processor about revoking/reissuing or re-tokenizing affected historic cards. Deleting the key from current source does not remove exposure from Git history. Never log PANs, CVVs or the legacy key.

## No fabricated financial or government evidence

Browser-only fake recharge, bill payment, savings movement, card issuance, card freeze, fabricated receipt hash, agent-float transfer and hardcoded healthy rails are prohibited. Displays must show authoritative server state or a clear unavailable status; never claim a transaction, compliance filing or operational health check was successful without evidence.

Official NIA, GRA, live bank, MoMo and card-network integrations are NOT YET VERIFIED/CONNECTED. Test fixtures in src/test are not live partners. GRA integration NOT IMPLEMENTED: manual submission required. E-Levy repealed April 2025: historical audit data only.

## CI/CD operations and escalation

The sole workflow is .github/workflows/sandbox.yml. Third-party GitHub Actions are pinned to inspected commit SHAs; update pins deliberately with a signed/reviewed dependency-change record. The backend runs clean Maven verification with PostgreSQL 18; frontend runs TypeScript, Vitest and Vite build; security runs OWASP Dependency-Check, npm audit and TruffleHog; container runs non-root and packages the verified code.

GitHub Actions NVD_API_KEY must be provided as an Actions secret where required for the vulnerability feed. Do not weaken scanners or skip finance tests to manufacture a passing status.

On 2026-11-23, the GitHub Actions workflows failed in seconds **before any runner was assigned**: runner_name was empty, the jobs had zero steps, and GitHub returned no usable logs. This is not a Java compiler diagnosis. The account owner must inspect the run annotation and account/org Actions billing, spending limits, runner availability, GitHub-hosted runner policies, and workflow permissions. Repository commits cannot force GitHub to supply runners.

Only call the exact commit GREEN once all backend, frontend, security and container jobs actually execute and pass. If a job has no steps, escalate runner/account execution first. If tests run and fail, record the command, failing test, environment and reproducible evidence before any merge.

## Flyway and financial state safety

Past migrations stay immutable. Future schema cleanup uses new versions and must be rehearsed against a restored PostgreSQL 18 database. Existing merchant accounting, posted financial transactions, refunds, reversals and audit references must remain intact. Retained retail legacy tables are archived evidence, not active application capabilities.

Financial guarantees—double-entry accounting, immutable journal postings, idempotency, authorization, reconciled provider callbacks, validated domestic recipients, reversals and audit integrity—are implementation invariants, not free-form editable settings. Keep business limits validated, versioned and human-readable. Production releases require independent human review of financial and security changes.
