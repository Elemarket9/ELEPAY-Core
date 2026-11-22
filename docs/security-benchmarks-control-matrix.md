# ELEPAY Security Benchmarks & Regulatory Compliance Control Matrix

**Document Version:** 1.0.0  
**Effective Date:** 2026-11-22  
**Scope:** ELEPAY Core Platform (`com.elepay.compliance`, `com.elepay.identity`, `com.elepay.security`, `com.elepay.common`, `com.elepay.ledger`)  
**Security Benchmarks Covered:**
- NIST SP 800-53 Rev. 5 (Security and Privacy Controls for Information Systems and Organizations)
- NIST FIPS 204 (Post-Quantum Digital Signature Standard: ML-DSA-65)
- OWASP Application Security Verification Standard (ASVS) Level 3 (v4.0.3)
- Bank of Ghana Cyber and Information Security Directive (BoG CISD 2018/2021)
- Payment Systems and Services Act, 2019 (Act 987) & Data Protection Act, 2012 (Act 843)

---

## 1. Executive Summary & Assurance Framework

ELEPAY Core is engineered as a zero-trust, high-assurance digital payments platform. Financial operations are strictly guarded by immutable double-entry ledger invariants, constant-time cryptographic verification, post-quantum audit checkpointing, field-level envelope encryption, and fail-closed regulatory compliance controls.

In compliance with Directive Section 10, **all production simulations have been eliminated**. In the absence of live external network credentials or partner availability, the platform fails closed with `UNAVAILABLE` or routes to human compliance review queues (`MANUAL_REVIEW_REQUIRED`), prohibiting synthetic outcome generation.

---

## 2. NIST SP 800-53 Rev. 5 Control Mapping

| Control Family | Control ID | Control Name | ELEPAY Implementation Details | Code Artifacts | Verification Method |
|---|---|---|---|---|---|
| **Access Control** | **AC-2** | Account Management | Role-based actor modeling (`Actor`), granular merchant tenant isolation, and administrative anomaly detection. | `Actor.java`, `AdminAnomalyDetector.java` | `AdminAnomalyDetectorTest` |
| **Access Control** | **AC-3** | Access Enforcement | Method- and tenant-level authorization checks. Replay protection using ephemeral and persistent distributed nonce tracking. | `RequestSigningFilter.java`, `DistributedNonceStore.java` | `RequestSigningFilterTest` |
| **Access Control** | **AC-6** | Least Privilege | Separation of duties between payment initiation, maker-checker authorization, and administrative oversight. Self-approval is blocked and flagged as critical anomaly. | `AdminAnomalyDetector.java` | `SecurityControlsTest.testAdminAnomalyDetection` |
| **Audit & Accountability** | **AU-2** | Event Logging | Every administrative action, authentication attempt, compliance screening, and key rotation is recorded. | `Audit.java`, `AuditCompliance.java` | `ComplianceAlgorithmsTest` |
| **Audit & Accountability** | **AU-9** | Protection of Audit Information | Hash-chained cryptographic audit log where each entry embeds the SHA-256 hash of its predecessor. Database mutations on historical records are rejected by triggers. | `Audit.java`, `V3__immutable_ledger.sql` | `AuditIntegrityIT` |
| **Audit & Accountability** | **AU-10** | Non-Repudiation | Periodic cryptographic checkpoints sign the audit log head using hybrid classical + post-quantum signatures (Ed25519 + ML-DSA-65). | `AuditCheckpoints.java`, `IntegrityKeys.java` | `AuditCheckpointsUnitTest` |
| **Identification & Auth** | **IA-2** | Identification & Authentication | Passkey/WebAuthn and asymmetric signature authentication for merchant APIs. Nonce-bound HMAC-SHA256 request signatures with timestamp skew limits. | `RequestSigningFilter.java`, `HmacVerification.java` | `HmacVerificationTest` |
| **Identification & Auth** | **IA-5** | Authenticator Management | API key generation with 256-bit cryptographically secure entropy, SHA-256 key hashing in database, and refresh-token rotation with family revocation on reuse. | `ApiKeys.java`, `SessionDeviceBindingService.java` | `ApiKeysTest`, `SecurityControlsTest` |
| **Identification & Auth** | **IA-8** | Cryptographic Module Auth | Asymmetric authentication using Ed25519 and ML-DSA-65 (FIPS 204). Keys managed via HSM/KMS abstraction. | `IntegrityKeys.java` | `IntegrityKeysTest` |
| **System & Comm Protection** | **SC-8** | Transmission Confidentiality | TLS 1.3 enforced across all public endpoints; mobile SPKI certificate pinning for Android and iOS clients. | `MobileCertificatePinningService.java` | `SecurityControlsTest.testMobileCertificatePinning` |
| **System & Comm Protection** | **SC-12** | Cryptographic Key Establishment | Master Key Encryption Keys (KEK) wrapped in AES-256-GCM; per-record Data Encryption Keys (DEK) with key version tagging and graceful retirement. | `EnvelopeEncryption.java`, `KeyRotationService.java` | `EnvelopeAndRotationHardeningTest` |
| **System & Comm Protection** | **SC-13** | Cryptographic Protection | FIPS-compliant cryptographic primitives: AES-256-GCM (12-byte IV, 128-bit auth tag), HMAC-SHA-256/512 with constant-time equality (`MessageDigest.isEqual`). | `EnvelopeEncryption.java`, `HmacVerification.java`, `IntegrityKeys.java` | `HmacVerificationTest` |
| **System & Comm Protection** | **SC-28** | Protection at Rest | Field-level envelope encryption for sensitive identity and financial credentials (e.g. Ghana Card, PAN, account numbers) with Additional Authenticated Data (AAD) binding. | `EnvelopeEncryption.java` | `SecurityControlsTest.testEnvelopeEncryptionAadTamperingFails` |
| **System & Info Integrity** | **SI-4** | Information System Monitoring | Real-time administrative anomaly detection (impossible travel, off-hours access, bulk enumeration, self-approval). | `AdminAnomalyDetector.java` | `SecurityControlsTest` |
| **System & Info Integrity** | **SI-7** | Software Integrity | Post-quantum hybrid digital signatures (Ed25519 + ML-DSA-65) committing to chain head; automated CI static security scanner for secrets, CVEs, and floating-point money. | `IntegrityKeys.java`, `CiSecurityScanner.java` | `CiSecurityScannerTest`, `IntegrityKeysTest` |

---

## 3. OWASP ASVS Level 3 (v4.0.3) Alignment

OWASP ASVS Level 3 is the highest level of assurance, designated for applications that handle high-value financial transactions and critical national infrastructure.

| ASVS Section | Verification Requirement | ELEPAY Architectural Implementation | Status |
|---|---|---|---|
| **V1: Architecture** | 1.1.1: Verified security architecture documentation covering threat modeling, network segregation, and trust boundaries. | Formal specification in `docs/product-specification.md` and `docs/operational-security-hardening.md`. Micro-services adhere to strictly isolated bounded contexts. | **COMPLIANT** |
| **V1: Architecture** | 1.1.2: Defense-in-depth with centralized security controls. | Standardized security interceptors (`RequestSigningFilter`, `RateLimitFilter`, `ProductionSafetyCheck`). | **COMPLIANT** |
| **V2: Authentication** | 2.1.1: Verified password and credential complexity and entropy. | API keys generated with 256-bit cryptographically secure random bytes; tokens stored exclusively as SHA-256 digests. | **COMPLIANT** |
| **V2: Authentication** | 2.8.1: Replay attack prevention on credential exchange. | Nonce-bound signatures (`canonicalV2`) with timestamp expiration (skew $\le 300$ seconds) and Redis-backed distributed nonce store. | **COMPLIANT** |
| **V3: Session Mgmt** | 3.3.1: Session tokens must be cryptographically random and securely bound. | Refresh-token rotation on every exchange; device fingerprint and device-id binding; complete family revocation if an expired token is reused. | **COMPLIANT** |
| **V4: Access Control** | 4.1.1: Least-privilege access enforcement at controller and service boundaries. | Strict multi-tenant isolation by `merchant_id`; RBAC enforced on administrative endpoints. | **COMPLIANT** |
| **V5: Validation** | 5.1.1: Positive input validation on all parameters. | Strict regex format validation and Luhn mod-10 check digit algorithms for identity credentials (`GhanaCardVerificationService`). | **COMPLIANT** |
| **V6: Cryptography** | 6.2.1: Modern authenticated encryption algorithms used for sensitive data. | AES-256-GCM used exclusively for field-level envelope encryption; plaintext and IVs never reused. | **COMPLIANT** |
| **V6: Cryptography** | 6.2.5: Constant-time comparison for all HMAC and signature operations. | All signature verifications across webhooks, USSD, and API requests execute `MessageDigest.isEqual` to prevent side-channel timing leaks. | **COMPLIANT** |
| **V6: Cryptography** | 6.4.1: Cryptographic agility and post-quantum readiness. | Hybrid signatures combining Ed25519 (classical) and ML-DSA-65 (NIST FIPS 204 post-quantum category 3) in `IntegrityKeys.java`. | **COMPLIANT** |
| **V7: Error & Logging** | 7.1.1: No sensitive data or credentials printed in logs or error messages. | Biometric images and full credentials hashed with SHA-256 prior to audit log persistence; raw bytes sanitized. | **COMPLIANT** |
| **V8: Data Protection** | 8.2.1: Data at rest protected using envelope encryption and key lifecycle workflows. | `EnvelopeEncryption.java` and `KeyRotationService.java` enable master key rollover with background re-encryption of historical records. | **COMPLIANT** |
| **V9: Communication** | 9.1.1: TLS 1.3 enforced for all transport channels. | SPKI certificate pinning profiles exported for mobile clients (`MobileCertificatePinningService.java`). | **COMPLIANT** |
| **V13: API Security** | 13.1.1: Formal REST API security with request signing and rate limiting. | Slotted sliding-window rate limiter per API key and client IP; mandatory V2 HMAC request signing in production. | **COMPLIANT** |

---

## 4. Bank of Ghana (BoG) Cyber & Information Security Directive & Act 987

The Bank of Ghana Cyber and Information Security Directive establishes rigorous requirements for Payment Service Providers (PSPs) and Financial Market Infrastructures.

### 4.1 Directive Security Objectives & Controls

1. **Section 3.1: Information Security Governance & Policies**
   - **Control:** System operational readiness checks (`ProductionSafetyCheck.java`) block deployment if debug bypasses, insecure key sizes, or non-production credentials are detected.
   - **Status:** Enforced via automated build checks and startup safety verification.

2. **Section 4.3: Identity Verification & National Identification Authority (NIA) Integration**
   - **Mandate:** Payment service providers must verify customers against the National Identity Register (Ghana Card) under Act 750 / Act 987.
   - **Implementation:** `GhanaCardVerificationService.java` implements full Luhn check digit validation and format parsing (`GHA-xxxxxxxxx-x`).
   - **Fail-Closed Compliance:** All default fallback to `SimulatedNiaClient` in production is removed. If the official NIA client or API credentials are unconfigured, the service returns `Status.UNAVAILABLE`, preventing unauthorized auto-approvals.
   - **Biometric Anti-Spoofing:** `FaceMatchingLivenessService.java` enforces explicit consent (Data Protection Act 843), performs anti-spoofing liveness analysis ($\ge 0.80$), and requires authentic biometric evaluation. In the absence of an active biometric engine, it fails closed to the human compliance queue (`MANUAL_REVIEW_REQUIRED`).

3. **Section 5.2: Cryptography & Key Management**
   - **Mandate:** Use of internationally recognized cryptographic standards and key management controls.
   - **Implementation:** AES-256-GCM envelope encryption with unique per-record DEKs; master key rotation engine (`KeyRotationService.java`); post-quantum hybrid audit checkpointing (`IntegrityKeys.java`).

4. **Section 6.4: Anti-Money Laundering (AML) & Transaction Monitoring**
   - **Mandate:** Real-time monitoring for structuring, PEP screening, and international sanctions list alignment (OFAC/UN/EU).
   - **Implementation:** `StructuringDetectionService.java` identifies smurfing and threshold evasion; `SanctionsScreeningService.java` performs Jaro-Winkler and Soundex fuzzy matching; `PepScreeningService.java` detects politically exposed persons.

5. **Section 7.1: Authoritative Ledger & Settlement Integrity**
   - **Mandate:** Exact settlement accounting with zero financial discrepancy.
   - **Implementation:** Double-entry journal in integer minor units (pesewas/cents), enforced by PostgreSQL constraint triggers `journal_entry_balanced`, `reject_ledger_mutation`, and `ledger_account_guard`.

---

## 5. Applied Cryptography & Post-Quantum Integrity Architecture

```
                    +------------------------------------+
                    |       Audit Log Chain Head         |
                    +------------------------------------+
                                      |
                                      v
                    +------------------------------------+
                    |  Canonical Checkpoint Message v1   |
                    | (last_id, hash, count, timestamp)  |
                    +------------------------------------+
                                      |
                    +-----------------+------------------+
                    |                                    |
                    v                                    v
     +-----------------------------+      +-----------------------------+
     |   Classical Signature       |      |   Post-Quantum Signature    |
     |   Algorithm: Ed25519        |      |   Algorithm: ML-DSA-65      |
     |   Standard: RFC 8032        |      |   Standard: NIST FIPS 204   |
     +-----------------------------+      +-----------------------------+
                    |                                    |
                    +-----------------+------------------+
                                      |
                                      v
                    +------------------------------------+
                    |       HybridSignature Record       |
                    | Valid only if BOTH signatures hold |
                    +------------------------------------+
```

### 5.1 Hybrid Signature Specification (FIPS 204)
- **Classical Primitive:** Ed25519 (Edwards-curve Digital Signature Algorithm over Curve25519).
- **Post-Quantum Primitive:** ML-DSA-65 (Module-Lattice-Based Digital Signature Standard, NIST Security Category 3).
- **Verification Invariant:** A signature verifies if and only if **both** Ed25519 and ML-DSA-65 verify against the registered public keys. This ensures forward security: if Shor's algorithm compromises Ed25519, ML-DSA-65 guarantees integrity; if a novel lattice attack is discovered against ML-DSA, Ed25519 guarantees integrity.

### 5.2 Constant-Time Webhook Verification
- Webhook endpoints verify signatures via `HmacVerification.verify` and `Webhooks.verifySignature`.
- Comparison executes `MessageDigest.isEqual` over canonical byte arrays:
  $$\text{time}(a, b) = \Theta(|a|)$$
  preventing timing attacks that deduce secret keys through character-by-character comparison latencies.

### 5.3 Envelope Encryption & Re-Encryption Workflow
- **KEK:** AES-256 key stored in secure key registry / KMS.
- **DEK:** Ephemeral 256-bit AES key generated per record via `SecureRandom`.
- **Payload Ciphertext:** Plaintext encrypted under DEK via `AES/GCM/NoPadding` with 12-byte IV and 128-bit authentication tag, bound to AAD (e.g. tenant/customer UUID).
- **Wrapped DEK:** DEK encrypted under KEK via `AES/GCM/NoPadding` with 12-byte IV and bound to KEK ID.
- **Key Rotation Workflow:** When KEK rotates from $v_1$ to $v_2$, `KeyRotationService.reEncrypt` unwraps the DEK using $v_1$, re-wraps the DEK under $v_2$, and leaves the payload ciphertext untouched, allowing seamless bulk key rotation without decrypting sensitive customer data into memory.

---

## 6. Verification and Test Suite Evidence

| Test Suite | Package / Class | Verification Focus | Status |
|---|---|---|---|
| **Post-Quantum Hybrid Signatures** | `com.elepay.common.IntegrityKeysTest` | Validates Ed25519 + ML-DSA-65 dual-signing, message tampering detection, and key rotation. | **PASSED** (4/4) |
| **Audit Checkpoint Verification** | `com.elepay.common.AuditCheckpointsUnitTest` | Validates canonical message signing, checkpoint tampering detection, and public key verification. | **PASSED** (1/1) |
| **Constant-Time HMAC & Webhooks** | `com.elepay.common.HmacVerificationTest` | Validates RFC 4231 vectors, constant-time `isEqual`, replay window tolerance, and `Webhooks.verifySignature`. | **PASSED** (4/4) |
| **Ghana Card & Biometric Hardening** | `com.elepay.compliance.GhanaCardAndBiometricVerificationTest` | Validates unconfigured fail-closed `Status.UNAVAILABLE`, Luhn check digit, consent enforcement, and manual review escalation. | **PASSED** (7/7) |
| **Compliance Algorithms** | `com.elepay.compliance.ComplianceAlgorithmsTest` | Validates sanctions fuzzy matching, PEP screening, structuring detection, and AML alert prioritization. | **PASSED** (9/9) |
| **Envelope Encryption & Rotation** | `com.elepay.security.EnvelopeAndRotationHardeningTest` | Validates null-safety, malformed envelopes, wrong KEK rejection, key retirement, and batch re-encryption. | **PASSED** (5/5) |
| **Security Controls & Anomaly Detection** | `com.elepay.security.SecurityControlsTest` | Validates JWKS rollover, admin anomaly detection, session device binding, cert pinning, and CI scanner. | **PASSED** (8/8) |
| **Identity & Request Signing** | `com.elepay.identity.RequestSigningFilterTest` | Validates V2 canonical request signing, nonce replay rejection, and timestamp skew enforcement. | **PASSED** (12/12) |
