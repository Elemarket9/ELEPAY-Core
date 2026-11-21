# Monitoring

Scrape `/actuator/prometheus` on the management port. Gauges refresh every 30 seconds.

| Metric | Alert |
| --- | --- |
| `elepay_ledger_accounts_with_drift` | > 0: severity 1 (see reconciliation.md) |
| `elepay_outbox_oldest_pending_seconds` | > 300 for 10 minutes |
| `elepay_webhooks_dead` | increase |
| `elepay_rails_unresolved_over_1h` | > 0 for 15 minutes |
| `elepay_ops_open_high` | > 0 during business hours |
| `elepay_disputes_open` | trend |
| `http_server_requests_seconds` (5xx, p99) | 5xx rate > 1% or p99 > 2 s |

Probes: `/actuator/health/liveness` and `/actuator/health/readiness`. Logs are structured (ECS JSON) in the prod profile.


## Daily ledger snapshots and retry jitter

The V13 snapshot job captures the current UTC day's committed balance projection and journal reconstruction once, atomically across accounts. The database rejects updates and deletes. It is an observation, not a replacement for journal reconciliation. Alert when elepay.ledger.snapshot_age_seconds is -1 or exceeds 90000, or elepay.ledger.snapshot_drift is positive. Investigate drift using the reconciliation runbook; never edit snapshot or journal data. Missing days cannot be backdated as current balances. The recovery poll uses bounded exponential jitter for status queries and webhook retries only; it does not resubmit ambiguous financial requests.

Checkout fee configuration: for each wallet/momo/card method optionally set elepay.fees.customer.<method>-tiers (e.g. 10000:100,*:50) and -cap in minor units. Absent tiers preserve existing basis-point/fixed-fee rounding down. Active attempts retain their recorded fee. Domestic transfers are unchanged. Validate pricing against agreed fee policy before deployment.

## Audit sealing and chain verification

Audit events remain append-only. V14 seals them into a separate SHA-256 chain in bounded scheduled batches, including legacy events and events committed out of ID order. Monitor audit.unsealed_over_60s, audit.seal_errors, audit.verification_errors, audit.chain_violations and audit.last_verified_epoch_seconds (all under the elepay prefix). A chain violation or stale verification is a security incident. Use SQL SELECT position, audit_id FROM audit_chain_violations to locate failures without copying payloads. Restrict audit tables to approved operators. This is an internal tamper-evidence mechanism, not protection against a privileged database administrator rewriting the entire chain; externally retained signed chain checkpoints remain required. Never repair by rewriting historic rows.
