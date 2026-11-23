# Stage 3 offline POS design and safety contract

## What this change enables
- After the menu is loaded online, the merchant POS caches a menu snapshot in IndexedDB.
- New orders can be journaled *locally first* with stable UUIDs even without an internet connection.
- Offline cash is recorded as **locally collected**, never as verified card/MoMo.
- On network recovery, the POS submits unchanged transaction and replay IDs to `POST /v1/pos/sync`.
- The server reserves `(merchant_id, offline_tx_id)` atomically and persists its server order ID.
  Retrying with the same key or a different key for the same transaction returns the original result.
- Altered replays fail and conflicts remain on device until reviewed.
- The original order channel and table are retained. Accepted restaurant orders generate KDS tickets.

## Critical operating constraints
- **Do not use contactless NFC, chip, card, or MoMo without a connected certified payment rail.**
- A local cash receipt is provisional; the authoritative receipt is available after cloud reconciliation.
- Do not clear browser storage or uninstall the app while there are unreconciled orders.
- IndexedDB persistence depends on browser/device storage policies and available disk space;
  request persistent storage and enforce operational monitoring.
- A second device on the same Wi-Fi **does not** automatically receive local pending orders.
  Cross-device offline KDS still requires an authenticated local edge or peer relay.
- 8-hour endurance, low-bandwidth stress, power-loss recovery and actual multi-device tests
  remain required. This change is an auditable foundation, not proof of those benchmarks.

## Cloud reconciliation rules
- Cash tender only for offline requests; all electronic payments must use checkout verification.
- One merchant/offline transaction ID can create at most one cloud POS order.
- A conflicting total is flagged and not silently repriced.
- A table conflict is not silently assigned elsewhere; preserve the local draft for staff review.
- Payment state in the server overrides any local presentation status.
