# ELEPAY operator runbooks

| Runbook | When |
| --- | --- |
| [deploy-and-migrate.md](deploy-and-migrate.md) | Releases, schema migrations, rollback |
| [exceptions-queue.md](exceptions-queue.md) | Triage of every operations queue item kind |
| [rail-recovery.md](rail-recovery.md) | Pending or unknown charges, payouts and refunds |
| [settlement-and-payouts.md](settlement-and-payouts.md) | Settlement runs, withdrawals, failed and returned payouts |
| [reconciliation.md](reconciliation.md) | Ledger integrity, rail and FX partner statements |
| [outbox-and-webhooks.md](outbox-and-webhooks.md) | Event backlog, dead webhooks, merchant event recovery |
| [disputes-and-refunds.md](disputes-and-refunds.md) | Opening and resolving disputes, failed refunds |
| [access-and-keys.md](access-and-keys.md) | Roles, API keys, delegations, USSD PIN lockouts, secrets |
| [monitoring.md](monitoring.md) | Metrics and alert thresholds |

Ground rules: never edit `journal_entry`, `journal_transaction` or `ledger_account.balance` (the database rejects it). Correct money only with a new, balanced, idempotent posting or a linked reversal through the application. Every operator action is recorded in `audit_log`. Operations APIs require a token with the `ops` role.
