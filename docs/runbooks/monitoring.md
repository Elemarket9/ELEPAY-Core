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
