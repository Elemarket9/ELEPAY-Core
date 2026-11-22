# Original User Request
## 2026-11-22T01:29:21Z
ELEPAY CORE — COMPLETE IMPLEMENTATION OF THE 100 ALGORITHMS

Repository: https://github.com/Elemarket9/ELEPAY-Core
Target branch: main
Authoritative product specification: docs/product-specification.md

ROLE AND MISSION

Act as a principal fintech engineer responsible for implementing, testing, securing and operating ELEPAY.

Build ELEPAY as a Ghana-first financial platform for individuals, merchants and marketplaces, with architecture that supports international expansion.

ELEPAY owns its customer and merchant relationships, payment APIs, transaction records and authoritative double-entry ledger. Implement accounts, wallets, internal transfers, checkout, merchant payments, payouts, refunds, disputes, foreign exchange, reconciliation and settlement.

Process transfers between ELEPAY wallets and wallet-funded merchant payments internally. Use direct partner adapters for transactions crossing into bank, mobile-money, acquiring, card and remittance networks.

Exclude Paystack and Hubtel from the implementation and processing path.

This is an implementation assignment. Deliver working code connected to normal product workflows, with meaningful tests and operational evidence. Documentation, disconnected helper functions, placeholder endpoints and simulated success responses do not constitute completed implementation.

AUTHORIZATION AND WORKING METHOD

You are authorized to inspect and modify the repository, create implementation branches, add dependencies where justified, introduce append-only migrations, fix defects, run checks, push changes and merge verified increments into main.

Follow repository instructions and branch protections. Never bypass security checks or force-push shared history.

Do not request confirmation for routine, reversible engineering decisions within this scope. Continue independent work when external credentials, partner access or infrastructure are unavailable.

Do not deploy to production, move real customer funds or change production credentials without explicit authorization for those actions.

FIRST: ESTABLISH THE CURRENT STATE

1. Read the latest main branch and all applicable repository instructions.
2. Read:
   - docs/product-specification.md
   - README.md
   - docs/runbooks/README.md
   - docs/openapi.json
   - Build configuration and dependency versions
   - Database migrations
   - Existing architecture and security documentation
   - Current CI workflows
3. Inspect existing accounts, wallets, ledger, checkout, transfers, payouts, refunds, disputes, FX, reconciliation, authentication and frontend implementations.
4. Establish the actual toolchain from the repository. The intended baseline is Java 25, Spring Boot 4, Spring Modulith and PostgreSQL 18; verify compatibility before changing versions.
5. Run the existing checks, including those below when present:
   - mvn -B verify
   - Web dependency installation, type checking, tests and production build
   - scripts/smoke.py
   - scripts/acceptance.py
6. Run service-dependent checks against an isolated development or sandbox environment.
7. Record baseline failures and fix relevant defects before extending affected workflows.
8. Inspect previously claimed implementations. Reuse working components, but verify every completion claim against code and tests.

ARCHITECTURE AND ENGINEERING RULES

Maintain a modular monolith with explicit module boundaries and shared financial primitives.

Use:
- Java and Spring Boot for core financial workflows.
- PostgreSQL as the authoritative transactional database.
- TypeScript and React for merchant, customer and operations web interfaces.
- Kotlin for Android and Swift for iOS where these channels are required.
- Redis for bounded caching and rate limiting when justified.
- Python for isolated analytics and model training where useful.
- Additional services or languages only when a measured requirement justifies their operational cost.

Do not introduce Kafka, microservices or distributed infrastructure merely to increase the technology count.

Preserve historical records and working behavior. Add new numbered Flyway migrations; never rewrite released migrations.

FINANCIAL CORRECTNESS: NON-NEGOTIABLE

1. Represent money in integer minor units with explicit currency and checked arithmetic. Never use binary floating point for monetary amounts.

2. Every posted journal transaction must balance independently for each currency. Cross-currency workflows must use explicit FX clearing accounts and balanced postings in both currencies.

3. Keep the posted journal append-only. Correct errors through linked reversals or new balanced adjustments.

4. Derive balances from authoritative postings. Any cached balance or snapshot must be reconcilable to the journal.

5. Require an idempotency key for every financial command:
   - An identical retry returns the original result.
   - A changed payload under the same key returns HTTP 409.
   - Scope keys by the appropriate tenant, actor and operation.
   - Persist the request fingerprint and outcome atomically where possible.

6. Use explicit transaction boundaries and deterministic locking to prevent overspending, race conditions and deadlocks.

7. A timeout means the outcome may be unknown. Never submit another debit, credit, payment or payout merely because a response was lost.

8. Persist external submission intent and a stable reference before submission. Use partner idempotency where available. If a partner lacks safe deduplication, block unsafe automatic resubmission and resolve the outcome by query or review.

9. Validate callbacks, deduplicate them and enforce permitted state transitions. Handle duplicate and out-of-order events safely.

10. Prevent double capture, double refund, duplicate settlement, duplicate payout and cumulative refunds exceeding the captured amount.

11. Enforce account status, available funds, currency, KYC limits, permissions and risk controls at the authoritative execution boundary.

12. Keep customer funds, fees, merchant liabilities, settlement obligations, suspense balances and platform revenue distinguishable in the ledger.

13. Never silently repair financial discrepancies. Create an auditable exception and use an authorized correction workflow.

SECURITY AND DATA RULES

- Enforce authorization and tenant isolation on the server.
- Prevent object enumeration and cross-tenant access; return 404 where appropriate.
- Never log secrets, PINs, full card numbers or unnecessary personal information.
- Use vetted cryptographic libraries and managed key systems. Do not invent cryptographic primitives.
- Do not retain card verification codes.
- Treat a card vault as a specialized security boundary. Prefer approved partner or network tokens where available.
- Require maker-checker approval for sensitive financial and administrative actions.
- Make audit records tamper-evident and protect their independent integrity anchors.
- Separate simulated, partner-test and live execution modes.
- Never present simulated verification, settlement or payment success as live.
- Fail closed when a required external verification is unavailable.
- Keep regulatory controls explicit in implementation and operational documentation. Do not claim legal approval or partner certification without evidence.

IMPLEMENT THE FOLLOWING 100 ALGORITHMS AND CAPABILITIES

The numbered items are an implementation checklist. Some are deterministic algorithms; others are workflows, security controls or operational capabilities. Completion requires integration and evidence, regardless of category.

A. LEDGER AND MONEY

001. Double-entry posting
Create atomic, balanced journal postings with currency validation, account validation and immutable references.

002. Ordered row locking
Acquire locks deterministically and implement bounded handling of serialization failures and deadlocks.

003. Idempotent request hashing
Canonicalize financial requests, persist fingerprints and reject changed-payload retries.

004. Integer proportional allocation
Distribute money using deterministic rounding, preserving the exact total and recording residual allocation.

005. Linked reversals
Reverse eligible postings through linked balanced entries with duplicate-reversal protection.

006. Per-currency FX clearing
Implement balanced cross-currency movements through explicit clearing accounts and recorded rates.

007. Fee schedule engine
Support versioned fixed, percentage, tiered, minimum and capped fees with effective dates and deterministic rounding.

008. Interest and yield accrual
Implement configurable accrual conventions, eligibility, rounding and posting schedules. Keep disabled for live use until the relevant product is authorized.

009. Revenue recognition
Separate collected fees, deferred amounts, earned revenue and reversals using explicit accounting rules.

010. Balance snapshots
Create verifiable snapshots and prove consistency with subsequent journal postings.

B. PAYMENT PROCESSING

011. Duplicate-safe rail submission
Prevent duplicate external money movement using durable intent, stable references, partner idempotency and explicit submission states.

012. Unknown-outcome recovery
Query unresolved transactions, reconcile evidence and prevent unsafe resubmission.

013. Callback state machine
Authenticate events, deduplicate callbacks and enforce valid transitions under reordered delivery.

014. Smart payment routing
Choose eligible routes using cost, availability, supported capabilities and measured success rates.

015. Safe failover routing
Fail over only when the first route is definitively unsubmitted or failed without money movement.

016. Network health detection
Maintain partner health signals using bounded windows, minimum sample sizes and recovery probes.

017. Card BIN lookup
Resolve card metadata from a maintained, versioned data source without treating BIN metadata as proof of authorization.

018. Risk-based 3-D Secure
Integrate supported authentication flows and preserve challenge, exemption and liability-related evidence.

019. Card tokenization boundary
Implement token lifecycle, restricted access, revocation and secure references without exposing raw card data to general application modules.

020. Recurring billing mandates
Capture consent, mandate scope, billing schedules, cancellation and duplicate-charge protection.

021. Dunning schedules
Retry eligible failed collections using bounded schedules, clear customer notices and mandate restrictions.

022. EMVCo QR encoding
Generate and validate supported QR payloads, including checksums and applicable scheme constraints.

C. FRAUD AND RISK

023. Velocity rules
Enforce configurable transaction-count and value limits across relevant time windows and entities.

024. Rules engine and risk score
Use versioned rules, explainable decisions and auditable outcomes.

025. Device risk signals
Collect privacy-conscious device signals and distinguish confidence from certainty.

026. SIM-swap checks
Integrate authorized data sources, handle signal age and require step-up or review when appropriate.

027. Geo-velocity checks
Detect implausible location changes while accounting for uncertain location data and VPN effects.

028. Account takeover detection
Combine session, credential, device and transaction signals into actionable decisions.

029. Adaptive step-up authentication
Require stronger authentication for elevated risk and bind confirmation to the intended action.

030. Merchant risk scoring
Assess merchant activity using explainable features, review queues and configurable thresholds.

031. Promotion and referral abuse detection
Detect duplicate identities, coordinated accounts and repeated incentive exploitation.

032. Mule-account graph detection
Analyze transaction relationships, suspicious fund flows and linked-account patterns.

033. Synthetic identity detection
Identify inconsistent identity evidence and suspicious relationship patterns without claiming certainty.

034. Behavioral biometrics
Implement an optional, privacy-reviewed signal pipeline with documented accessibility and false-positive limitations.

035. Gradient-boosted fraud model
Build reproducible training, evaluation, versioning and inference. Label synthetic-data models experimental.

036. Chargeback prediction
Estimate dispute risk using calibrated models and report precision, recall, calibration and decision consequences.

D. COMPLIANCE CONTROLS

037. Rate-limited audited lookups
Restrict sensitive identity and recipient lookups, prevent enumeration and record access.

038. Bound single-use confirmations
Bind confirmations to actor, operation, recipient, amount, currency and expiry; prevent replay.

039. KYC tier limits
Enforce configurable per-transaction and cumulative limits atomically across all financial entry points.

040. Ghana Card matching
Integrate an authorized NIA verification channel when available. Keep simulation explicit and distinguish unavailable from verified.

041. Face matching and liveness
Integrate an appropriate service with consent, secure handling, thresholds and manual review paths.

042. Fuzzy sanctions screening
Combine normalized exact matching, Jaro-Winkler and phonetic signals with list versioning and analyst review.

043. PEP screening
Screen against authorized sources and support review, rescreening and evidence retention.

044. Structuring detection
Detect suspicious splitting of transactions across configurable windows and related accounts.

045. AML transaction monitoring
Create versioned rules, cases, evidence and review workflows without automatic unsupported conclusions.

046. Alert prioritization
Rank cases using severity, exposure, confidence and age, with explainable ordering.

047. Regulatory report generation
Generate reviewable Bank of Ghana and FIC outputs against verified current specifications. Do not submit automatically.

048. Retention and redaction
Apply configurable retention policies, legal holds, controlled deletion and redaction while preserving required financial records.

E. RECONCILIATION AND TREASURY

049. Exact statement matching
Match external records to internal transactions using references, currency, amount and status.

050. Ledger reconstruction checks
Rebuild balances from journal entries and detect differences from stored projections.

051. Settlement batching
Create reproducible batches with eligibility checks, immutable membership and duplicate-settlement protection.

052. Fuzzy statement matching
Propose uncertain matches using tolerances and confidence scores; require review where evidence is insufficient.

053. Partner fee verification
Compare charged fees against applicable contracts and flag discrepancies.

054. Suspense aging and clearing
Track unresolved balances, ownership, age and authorized resolution.

055. Settlement netting
Calculate obligations with traceable gross components and correct legal-entity and currency boundaries.

056. Float forecasting
Estimate liquidity requirements and report uncertainty and data limitations.

057. Float rebalancing
Implement policy-controlled recommendations and approved transfers with liquidity safeguards.

058. Merchant cash-flow forecasting
Produce reproducible forecasts with backtesting and uncertainty ranges.

F. FX AND CROSS-BORDER

059. Rate sourcing and blending
Validate rate freshness, source quality, currency pairs and outliers.

060. Dynamic FX margin pricing
Apply versioned margins and explicit rounding with transparent customer quotes.

061. Corridor routing
Choose eligible partner paths using corridor support, cost, liquidity and operational constraints.

062. Exposure limits and hedging triggers
Measure currency exposure and generate policy-driven actions or review requests.

063. Delivery-time estimation
Estimate completion times from route-specific evidence and show uncertainty.

064. Multi-currency partner reconciliation
Reconcile source amounts, destination amounts, rates, fees and clearing balances.

G. CREDIT

065. Cash-flow underwriting
Implement explainable eligibility assessment with data provenance and review paths.

066. Earned-wage access eligibility
Validate verified earned amounts, existing obligations and configurable eligibility rules.

067. Overdraft eligibility scoring
Assess affordability and exposure under explicit product policies.

068. Merchant cash-advance pricing
Calculate transparent offers, repayment obligations and total cost using versioned rules.

069. Repayment scheduling and sweeps
Create deterministic schedules and consented collections without duplicate debits.

070. Collections prioritization
Prioritize cases using documented policies and auditable decisions.

071. Default prediction
Build reproducible evaluation and calibrated outputs; do not present synthetic-data performance as production evidence.

072. Dynamic credit limits
Apply explainable limit changes with exposure controls, notices and review.

Keep credit products disabled for live use until their required approvals, funding arrangements and operational controls exist.

H. SECURITY

073. HMAC verification
Verify signatures using constant-time comparison, correct canonicalization and key versioning.

074. PIN hashing and lockout
Use a vetted password-hashing function, secure parameters and abuse-resistant attempt controls.

075. Field-level envelope encryption
Encrypt sensitive fields using managed wrapping keys, authenticated encryption and explicit access controls.

076. Key rotation
Support new writes under current keys and controlled access to historical ciphertext with tested recovery.

077. Request signing and replay protection
Bind signatures to method, path, body, timestamp and nonce or equivalent replay controls.

078. JWKS caching and rollover
Validate issuer, audience and permitted algorithms with bounded caching and safe key refresh.

079. Maker-checker approvals
Enforce distinct authorized actors and bind approval to the exact proposed action.

080. Tamper-evident audit records
Create hash-linked records with independently protected checkpoints and integrity verification.

081. Administrative anomaly detection
Flag unusual privileged activity and connect alerts to response workflows.

082. Session and device binding
Manage session revocation, refresh-token rotation and device-associated authentication.

083. Mobile certificate pinning
Where justified by the threat model, implement tested pin rotation, backup pins and recovery procedures.

084. CI security scanning
Block relevant secret leaks, vulnerable dependencies, unsafe containers and high-confidence security findings.

I. RELIABILITY AND PLATFORM

085. Transactional outbox and inbox deduplication
Persist events atomically with business changes and make consumers idempotent.

086. Partner circuit breakers
Configure bounded timeouts, opening thresholds, half-open probes and recovery.

087. Backoff and jitter
Retry only eligible operations with bounded attempts, budgets and randomized delays.

088. Token-bucket rate limiting
Apply appropriate per-key, actor and endpoint limits with consistent behavior across instances.

089. Load shedding and backpressure
Protect critical financial operations under overload and bound queues.

090. SLO burn-rate alerting
Measure user-visible availability and latency with actionable multi-window alerts.

091. Success-rate anomaly detection
Detect meaningful changes using sufficient samples and route-specific baselines.

092. Scaling signals
Expose capacity indicators and scaling policies supported by measured bottlenecks.

093. Fault injection
Test interrupted processes, network failures, timeouts, delayed callbacks and duplicate delivery.

094. Database failover recovery
Handle lost connections, uncertain commits and recovery without duplicate financial effects.

J. CUSTOMER AND GROWTH

095. Exact phone normalization
Normalize Ghana and international numbers using maintained numbering metadata; preserve verified ownership separately.

096. Spending categorization
Assign explainable categories and allow corrections without altering financial records.

097. Savings goals and round-ups
Implement explicit consent, exact rounding, insufficient-funds handling and reversible preferences.

098. Churn prediction
Build an evaluated, privacy-conscious model with clear limitations and no unsupported performance claims.

099. Notification timing
Respect preferences, quiet hours, delivery limits and urgency.

100. Personalized limits
Apply transparent, policy-constrained limits with explanations, review and protection against unauthorized overrides.

IMPLEMENTATION PHASES

PHASE 1 — COMPLETE THE CORE FINANCIAL JOURNEY

Prioritize one complete vertical flow:

1. A customer authenticates.
2. The customer enters a recipient phone number.
3. ELEPAY performs an authorized recipient lookup.
4. The customer sees an appropriately masked recipient identity.
5. ELEPAY presents amount, currency, fees and total.
6. The customer confirms the exact transaction.
7. ELEPAY checks limits, risk, permissions and available funds.
8. ELEPAY executes an internal wallet transfer atomically.
9. Both parties receive accurate receipts and updated balances.
10. Retrying the request returns the same result without another transfer.
11. Operations can trace the transaction and reconcile its postings.
12. Failures return clear, actionable messages.

Extend the same proven financial primitives into checkout, refunds, payouts, disputes, settlement and FX.

PHASE 2 — DIRECT PARTNER ADAPTERS

Implement adapter contracts and controlled simulators for external networks.

Each adapter must distinguish:
- Not submitted
- Submission in progress
- Pending
- Succeeded
- Definitively failed
- Unknown outcome
- Reversed, where supported

Implement authentication, stable references, status queries, callback validation, reconciliation and contract tests.

Use real partner-test environments only when authorized credentials and documentation exist. Record blockers precisely. Do not invent integrations or report simulator results as partner certification.

PHASE 3 — ADVANCED ANALYTICS AND MODELS

Implement reproducible data pipelines, feature definitions, model versioning, evaluation and controlled inference.

Use synthetic data for development when necessary, but mark the resulting models experimental and unsuitable for unsupported live financial decisions.

Provide deterministic rules or manual review where a model lacks adequate evidence.

DEFINITION OF DONE

Mark an item Built only when:

- Its implementation is reachable through the intended workflow.
- Financial and security invariants are enforced.
- Configuration and migrations are complete.
- Meaningful positive, negative, concurrency and recovery tests pass where relevant.
- API contracts and user interfaces are updated where applicable.
- Metrics and actionable errors exist.
- Operational procedures are documented.
- Evidence identifies the verified commit and execution environment.
- Any required external dependency has been validated for the claimed environment.

Use these statuses:
- Built
- Partial
- Missing
- Blocked

Record execution mode separately:
- Simulated
- Partner-test
- Live

A feature working only in simulation must not be described as a validated live integration.

READINESS SCORECARD

Maintain docs/readiness-scorecard.md.

For each of the 100 items, record:
- ID and name
- Status
- Implementation location
- Workflow or entry point
- Test evidence
- Execution mode
- Metrics or operational evidence
- External dependencies
- Remaining gaps
- Verified commit

Maintain a separate table for readiness targets. Algorithm completion and launch readiness are different measurements.

Do not claim “85% ready” solely because 85 items exist. Describe the result as “85 of 100 items Built” only when supported, and report unmet launch gates separately.

REQUIRED VALIDATION

FINANCIAL TESTING

- Run at least 10,000 generated financial operation sequences per relevant CI build, with reproducible seeds and useful failure output.
- Include top-ups, transfers, checkout, refunds, disputes, settlement, payouts, FX, timeouts and duplicate callbacks.
- Assert per-currency balance, journal-to-balance consistency, no unauthorized negative available balances and no duplicate financial effects.
- Test concurrent spending, simultaneous refunds, repeated settlement requests and idempotency collisions.

SECURITY TESTING

- Prove cross-tenant isolation for relevant endpoints.
- Reject replayed signed requests.
- Prove single-operator approval cannot execute maker-checker actions.
- Verify key rotation preserves authorized access to historical data.
- Test authorization at financial execution boundaries.
- Test expired, reused and modified transaction confirmations.

PARTNER AND RECOVERY TESTING

- Interrupt execution before and after external submission.
- Simulate response loss after partner success.
- Deliver callbacks repeatedly and out of order.
- Verify safe failover and circuit-breaker recovery.
- Prove uncertain transactions remain unresolved until sufficient evidence exists.

PERFORMANCE TESTING

Use an isolated environment and report its hardware, dataset, topology and configuration.

Target:
- 200 completed payments per second for one hour.
- p99 under 500 ms for internal wallet transfers at the stated load.
- Zero ledger drift.
- Zero duplicate financial effects.

Measure external-network latency separately from internal execution.

If the available environment cannot sustain the target, report measured results and the limiting resource. Never invent benchmark numbers.

OPERATIONAL TARGETS

Implement and verify where infrastructure permits:
- 99.95% monthly availability objective.
- SLO burn-rate alerts.
- Reconciliation exceptions identified and assigned within 24 hours.
- Ledger drift checks every 30 seconds where operationally appropriate.
- Point-in-time recovery and documented restore drills.
- Database failover target below 60 seconds with tested durability assumptions.
- Staged rollout and tested rollback procedures.
- Tested retention, redaction and incident response.

Distinguish configured targets from measured achievements.

CODE QUALITY

- Target at least 90% line coverage for ledger, checkout, payout and FX modules, while prioritizing meaningful invariant and recovery tests.
- Pass module-boundary and API contract checks.
- Validate migrations from supported released schemas.
- Resolve high-severity security findings or document explicit, reviewable exceptions.
- Keep dependencies justified and maintained.

CHANNELS

Connect supported channels to the same authoritative APIs:
- Low-data customer web experience
- Merchant dashboard
- Operations dashboard
- Android application
- iOS application
- USSD adapter through a contracted gateway

Provide clear balances, recipient confirmation, payment status, receipts, error recovery and support references.

Do not create fake balances or local-only success states that contradict the backend.

DELIVERY AND MERGING

Work in coherent, reviewable increments.

For each increment:
1. Implement the complete change.
2. Add necessary migrations and configuration.
3. Update API contracts and regenerate docs/openapi.json when required.
4. Add meaningful tests.
5. Run applicable checks.
6. Fix failures introduced or exposed by the change.
7. Update the scorecard and operational documentation.
8. Commit and push.
9. Merge into main when required checks and repository policies pass.
10. If main advances, integrate it and rerun affected checks before merging.

Never merge known broken financial behavior merely to increase the completion count.

Continue available independent work when an integration or infrastructure task is blocked.

FINAL REPORT

Report:
- Exact commits and merged pull requests
- Implemented workflows
- Built, Partial, Missing and Blocked totals
- Simulated, partner-test and live validation separately
- Passing, failing and unexecuted checks
- Measured performance and test environment
- Financial correctness and recovery evidence
- Security findings and unresolved risks
- Partner and infrastructure blockers
- Remaining launch gates

Do not describe the platform as production-ready, externally certified or equivalent to another fintech without evidence.

START NOW

Inspect the latest main, establish the baseline, verify existing implementations and complete the internal wallet-transfer journey first. Then work through the remaining 100-item checklist in dependency order until all feasible implementation work is complete and every remaining blocker is precisely documented.                                                                                    share everything to the teams, commit and merge to the repo instantly the was a finished work, don't wait for all

## 2026-11-22T03:50:07Z
CRITICAL USER DIRECTIVE:
The user has explicitly instructed:
"stop the team test runs, auditing, and always commit instantly code is ready, we will challange and audit it later when all 100 is completed"

Instructions:
1. Immediately bypass/halt the 5-agent verification waves (Reviewers, Challengers, Forensic Audits) and extensive test run gates during milestone implementation.
2. Commit and push finished code, migrations, and scorecard updates to origin/main immediately as soon as an algorithm or batch is ready.
3. Proceed rapidly through remaining milestones (Milestone 2, 3, 4, 5, 6) to finish implementing all 100 algorithms.
4. All auditing, challenging, and extensive verification will occur at the very end once all 100 algorithms are complete.

## Follow-up — 2026-11-22T03:50:15Z
stop the team test runs, auditing, and always commit instantly code is ready, we will challange and audit it later when all 100 is completed

## 2026-11-22T03:52:12Z
URGENT USER DIRECTIVE:
The user has ordered:
"add the 5 subagents to the coding task to complete early"

Action required immediately:
1. Reallocate/spawn the 5 subagents directly into CODING / IMPLEMENTATION WORKER roles.
2. Parallelize the coding of the remaining milestones across these 5 coding workers:
   - Worker 1: Milestone 3 (FX, Cross-Border & Payouts — Algorithms 059-064, 011-016 partner payment processing rails)
   - Worker 2: Milestone 4 (Direct Partner Rail Adapters & Simulators — Cards, Mobile Money, Banks, excluding Paystack & Hubtel)
   - Worker 3: Milestone 5A (Fraud & Risk Engine — Algorithms 023-036)
   - Worker 4: Milestone 5B (Compliance, Sanctions, PEP, AML, BoG Reporting — Algorithms 040-048)
   - Worker 5: Milestone 6 (Credit, Reliability & Customer Growth — Algorithms 065-072, 081-084, 086-087, 089-094, 096-100)
3. Each worker must write code and migrations rapidly, commit and push to main as soon as ready without waiting for auditing.
