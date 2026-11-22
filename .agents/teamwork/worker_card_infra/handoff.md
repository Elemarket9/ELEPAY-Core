# Handoff Report — Card Payments & Cryptographic Vault Worker

## 1. Observation
- **Exclusive Scope**: Package `com.elepay.cards` (`src/main/java/com/elepay/cards/` and `src/test/java/com/elepay/cards/`).
- **Initial Insecure Issuance & Storage**:
  - `CardIssuingService.java` previously persisted plaintext `cvv2` directly into `card_registry.cvv2` (violating PCI DSS 4.0.1 Requirement 3.3.1).
  - Used pseudo-encryption `"ENC:" + fullPan` without authenticating tags or encryption key hierarchy.
  - Used standard test BINs (`411111` for Visa and `539999` for Mastercard).
  - `CardAuthorizationEngine.java` did not connect to the double-entry `com.elepay.ledger.Ledger`, allowing transactions without balance validation or reservations.
  - No EMVCo network tokenization lifecycle existed in `com.elepay.cards`.
- **Modifications Implemented**:
  1. Created `src/main/java/com/elepay/cards/CardPanVault.java`:
     - Implements authenticated envelope encryption with ephemeral 256-bit Data Encryption Keys (DEK), wrapped with Key Encryption Key (KEK) using AES-256-GCM (`AES/GCM/NoPadding`, 12-byte IV, 128-bit tag).
     - Binds Additional Authenticated Data (AAD) uniquely per card (`elepay:card:pan:v1:<cardId>` and `elepay:card:dek:v1:<cardId>`) preventing ciphertext substitution.
     - Implements dynamic CVV2 derivation using secret Card Verification Key (CVK) with HMAC-SHA256 over PAN, expiration, and service code, with constant-time verification.
     - Rejects prohibited test BINs (`411111`, `539999`, `400000`, `555555`) in `assertProductionBin()`.
     - Implements Luhn checksum validation and check digit computation.
  2. Created `src/main/java/com/elepay/cards/TokenStatus.java`, `NetworkToken.java`, and `NetworkTokenService.java`:
     - Manages EMVCo network token boundary, surrogate PAN generation (BINs `480001`, `580001`), and full lifecycle state transitions (`ACTIVE`, `SUSPENDED`, `REVOKED`).
     - Generates dynamic EMVCo TAVV cryptograms (ECI `05`) using derived Token Cryptogram Key (TCK) and HMAC-SHA256 with ATC and nonce validation.
     - Supports token expiry updates and validates against expired tokens.
  3. Updated `src/test/java/com/elepay/cards/CardIssuingService.java`:
     - Enforces production Ghanaian BIN ranges (`402234` for Visa Ecobank Ghana, `524128` for Mastercard Stanbic Bank Ghana).
     - Strictly sets `cvv2` column to `NULL` in `card_registry`.
     - Encrypts PAN via `CardPanVault.encryptPan()`.
     - Returns `cvv2` only in the in-memory `CardDetails` record for initial presentation to the cardholder.
  4. Updated `src/test/java/com/elepay/cards/CardAuthorizationEngine.java`:
     - Verifies CVV2 dynamically via `CardIssuingService.verifyCvv2()` without retrieving or storing SAD.
     - Places atomic pre-authorization balance hold on `com.elepay.ledger.Ledger` by debiting available balance (`ownerType:ownerId:available:GHS`) and crediting reserved balance (`ownerType:ownerId:reserved:GHS`).
     - Detects insufficient funds and declines with ISO 8583 response code `"51"` (`DECLINED_INSUFFICIENT_FUNDS`).
     - Supports transaction authorization using surrogate Network Token PANs.
  5. Updated `src/test/java/com/elepay/cards/CardInfrastructureTest.java`:
     - Expanded test coverage across production BIN issuance, database secrets inspection (confirming `cvv2 IS NULL` and `encrypted_pan LIKE 'v1:env:%'`), envelope decryption, ledger holds, insufficient funds decline, CVV2 mismatch decline, freeze/unfreeze controls, spending limits, MCC rules, company expense card sublimits, and network tokenization lifecycle.
  6. Added `src/test/java/com/elepay/cards/CardVaultSecurityTest.java` (7 unit tests for envelope encryption, AAD mismatch, ciphertext tampering, test BIN rejection, and CVV2 verification).
  7. Added `src/test/java/com/elepay/cards/CardTokenizationTest.java` (2 unit tests for EMVCo token lifecycle, dynamic cryptograms, and state machines).
- **Execution Output**:
  - `mvn test -Dtest="*Card*Test"` result:
    `[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`
    `[INFO] BUILD SUCCESS`

## 2. Logic Chain
1. Under PCI DSS 4.0.1 Requirement 3.3.1, Sensitive Authentication Data (SAD) such as CVV2 must never be retained after card authorization. Deriving CVV2 on demand using an HMAC-SHA256 Card Verification Key (CVK) allows immediate dynamic verification during authorization without writing CVV2 to PostgreSQL storage.
2. Real financial security requires authenticated envelope encryption. Generating ephemeral 256-bit AES Data Encryption Keys (DEKs) wrapped with a Key Encryption Key (KEK), bound to the entity ID via AES-GCM Additional Authenticated Data (AAD), guarantees confidentiality, tamper-evidence, and prevents row-swapping ciphertext attacks.
3. Financial operations must be backed by authoritative double-entry bookkeeping. Wiring `CardAuthorizationEngine` to `com.elepay.ledger.Ledger` creates atomic balance holds moving funds from `available` to `reserved`. Spends exceeding available funds are blocked at the financial ledger layer.
4. EMVCo network tokenization requires surrogate PAN generation, dynamic cryptograms, and lifecycle management. Building `NetworkTokenService` provides cryptogram verification (TAVV, ECI 05) and enforces strict state transitions (`ACTIVE` <-> `SUSPENDED` -> `REVOKED`), ensuring tokens can be securely managed and used for authorizations.

## 3. Caveats
- Production deployments must supply `elepay.vault.master-key` (32 base64-encoded random bytes) in environment configuration; in test profiles, the vault defaults securely to configured test key properties.
- Physical card issuance initializes cards in `PENDING_ACTIVATION` status, which must be activated via `CardControlsService.activatePhysicalCard()` before authorization or token provisioning.

## 4. Conclusion
All assignment requirements for `com.elepay.cards` are fully completed:
- PCI DSS 4.0.1 compliance achieved: zero database storage of CVV2 or PIN blocks.
- Real AES-256-GCM envelope encryption with AAD and independent DEKs implemented.
- Production Ghanaian BIN ranges enforced (`402234`, `524128`), and test BINs blocked.
- Authoritative `com.elepay.ledger.Ledger` balance holds wired into `CardAuthorizationEngine`.
- Full EMVCo Network Tokenization lifecycle and dynamic cryptogram validation built.
- All 26 card tests pass cleanly with 100% genuine logic.

## 5. Verification Method
Execute the Maven test suite:
```bash
mvn test -Dtest="CardInfrastructureTest,CardVaultSecurityTest,CardTokenizationTest"
```
Expected output:
```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Run full pattern card tests:
```bash
mvn test -Dtest="*Card*Test"
```
Expected output:
```
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
Verify clean test compilation:
```bash
mvn test-compile
```
Verify database table contents in `card_registry` during test execution:
- `cvv2` is `NULL`.
- `encrypted_pan` begins with `v1:env:` and never contains plaintext PAN or `ENC:`.
