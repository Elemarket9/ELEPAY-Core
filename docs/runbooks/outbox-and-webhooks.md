# Outbox and webhooks

Events are written in the same transaction as the money change and delivered at least once. Consumers record processed event ids (`outbox_consumer_inbox`), so redelivery after a restart is harmless (`RestartRecoveryIT`).

- Backlog: `elepay_outbox_backlog`, `elepay_outbox_oldest_pending_seconds`. Failing events retry with backoff (`outbox_event.last_error`).
- Webhooks: one `webhook_delivery` per endpoint, up to 12 attempts with backoff, then `dead`. To redeliver: `UPDATE webhook_delivery SET status='pending', attempts=0, next_attempt_at=now() WHERE event_id=… AND endpoint_id=…` (delivery state only, never money tables).
- Merchants recover missed events with `GET /v1/merchants/{id}/events?after={sequence}` and must deduplicate by `eventId`.
