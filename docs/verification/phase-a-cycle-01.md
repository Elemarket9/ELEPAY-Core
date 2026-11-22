# Phase A — cycle 01

Baseline commit: `6da0ca53b99699f51565f93eba2e46c71c91bff9`. Original numbered inventory: `PROJECT.md`, Feature Inventory (100 Algorithms & Capabilities).

## Result

Phase A is **incomplete**. This increment repairs two existing algorithm implementations; it introduces no algorithm family and makes no claim that all 100 algorithms are correct or integrated.

| Metric | Result |
| --- | ---: |
| Original algorithms registered | 100 |
| Original algorithms fully VERIFIED | 0 |
| Original algorithms repaired in this increment | 2 (087, 094) |
| Original algorithms newly integrated into financial workflows | 0 |
| New algorithms | 0 |
| Lexical production source LOC before | 32539 |
| Lexical production source LOC after | 32588 |
| Production LOC change | +49 |
| Isolated Java contract checks | 9,626 passed |
| Spring/PostgreSQL integration suite executed locally | No |
| Performance benchmarks executed | No |

The LOC total excludes tests, documentation, comments, blank lines, migrations, generated markers, exact decommented duplicate files, build artifacts, deployment configuration and non-code assets. It covers tracked Java backend, web TypeScript/JavaScript/CSS and PHP/JavaScript plugin production roots. Runtime literals and SQL embedded in production source count as code. There were no generated-marker, exact-duplicate-file or test-profile exclusions within those roots at this snapshot. Semantic duplicates, placeholders and non-production logic within otherwise production files still require manual adjudication: this is a reproducible source baseline, **not a certified total of meaningful verified production code**. Native apps are outside this cycle.

## Implemented repairs

Algorithm 087 now validates the retry configuration and arguments, uses saturating arithmetic before exponential/decorrelated multiplication, preserves cancellation and the interrupt flag, propagates fatal JVM errors, and starts the first retry at `base * 2^0`. Delay calculation is bounded to at most 63 doubling steps with constant space. The old preset remains only for API compatibility; operational callers must supply validated policy. No production caller was found in the baseline, so runtime enforcement is not asserted.

Algorithm 094 no longer interprets a lost connection as proof that an action can safely repeat. Its compatibility overload executes once on uncertain outcomes. The explicit overload requires a caller-supplied safety decision in addition to failover classification. That decision must be supported by durable idempotency or authoritative rollback/reconciliation evidence, and each invocation must create its own transaction. Connectivity alone is insufficient. Bounds, cancellation, cause-chain cycle detection and exact retry budgets are checked. Broad Spring transient-exception classification was removed because it does not establish database failover. No production caller was found in the baseline.

The existing integration test's synthetic retry action now explicitly declares its replay safety; it has no financial side effects.

## Reproduce

```sh
node scripts/count-production-loc.mjs --ref 6da0ca53b99699f51565f93eba2e46c71c91bff9
node scripts/count-production-loc.mjs
python3 scripts/check-reliability-safety.py
mvn -B verify
```

The standalone Python runner compiles the real two production classes with a temporary no-op Spring `Service` annotation and executes the same contract invoked by JUnit. The annotation substitute supplies no application behavior. It is an isolated arithmetic/control-flow check, not Spring wiring, PostgreSQL transaction or partner certification evidence. Local execution used JDK 27 with `javac --release 17`. Initial execution failed because the host temporary directory was inaccessible; the runner was corrected to use verified repository build output before the successful run.

The source-count script supports historical Git revisions, includes per-file totals and explicitly reports exclusions. The checked-in JSON contains the baseline per-file count. Added CI jobs run isolated contracts and publish the working-tree source count; the existing full CI remains required before merging.

## Confirmed remaining findings

- **059 — DEFECTIVE:** FX sourcing reads observations without a status filter, counts repeated observations from a single source independently and restores all observations when the outlier filter rejects every source. Freshness/outlier/spread policies need validated configuration and tests.
- **062 — DEFECTIVE:** exposure aggregation and percentage calculations use unchecked signed-long arithmetic, including `Math.abs(Long.MIN_VALUE)`; exposure can be understated. Hedging percentages are hardcoded.
- **082 — DEFECTIVE / overlapping:** the legacy device-session implementation uses process-local authoritative cache state, swallows persistence/revocation exceptions, permits an absent presented fingerprint despite a stored fingerprint and rotates tokens without an atomic compare-and-set. The distributed implementation also serves cached session state and requires an authoritative revocation review. Consolidation remains pending.
- **018 — MISSING:** a test-only 3DS implementation is not a production 3DS protocol integration.
- **083 — NOT INTEGRATED:** server configuration generation does not prove native TLS pinning; Android pin expiration is hardcoded. Native application work remains excluded.
- **084 — PARTIAL:** the Java static scanner is not a comprehensive vulnerability scanner; external CI scanner results remain separate evidence.
- Source specifications contain obsolete international-remittance and simulation scope statements. The current assignment supersedes them: retain FX, foreign-issued card acceptance and domestic merchant settlement; exclude international payouts/remittances, marketplace operations, restaurant management and delivery verification.

## Next required work

Repair and verify the remaining confirmed defects; independently review the mathematical foundations, complexity, financial integration, input validation, transaction behavior, security, configuration and test adequacy for each register entry. Candidate callers and candidate tests in the JSON are lexical discovery results and may include comments; they do not prove enforcement or test coverage. Complete financial/property/concurrency/fault tests and justified benchmarks before assigning VERIFIED.

External bank, MoMo and card integration agreements, credentials, specifications, certification environments and reconciliation evidence remain external dependencies. Nothing in this cycle fabricates connectivity, regulatory approval or successful live payments.

Do not advance to Phase B or add new algorithm families while Phase A remains incomplete.
