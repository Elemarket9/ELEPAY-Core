# ELEPAY restaurant POS + KDS operations

## Encrypted database backups
The `scripts/pos-backup.sh` job dumps the complete PostgreSQL instance (POS, kitchen, loyalty, customer and financial records) through `age` encryption without writing an unencrypted copy. Supply libpq variables `PGHOST`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` via an owner-only root-managed environment file, plus `ELEPAY_BACKUP_DIR`, `ELEPAY_BACKUP_AGE_RECIPIENT` and retention days. A private backup key must remain **outside** the production server. Do not embed credentials in Git.

The optional `ops/systemd/elepay-pos-backup.service` and `.timer` units run daily at 03:00 UTC after being installed and **explicitly enabled by an operator**. Mirror encrypted archives off-site using a separately configured backup provider. Alert on missing daily files and rejected encryption.

## Recovery drill
1. Create a **disposable** isolated database named `elepay_restore_...` with a trusted DBA account.
2. Set `PGHOST/PGDATABASE/PGUSER/PGPASSWORD`, `ELEPAY_BACKUP_FILE`, `ELEPAY_BACKUP_AGE_IDENTITY`, and `I_CONFIRM_DISPOSABLE_DATABASE=yes`.
3. Run `scripts/pos-restore-verify.sh` to decrypt straight into `pg_restore` and check the POS table.
4. Record elapsed time, integrity and order counts. Test on the actual PostgreSQL major version before production.
5. Never treat unverified backup existence as confirmed recoverability.

## Hardware-agnostic restaurant printing
A kitchen station may target `tcp://192.168.x.x:9100`, `bt://...` or `usb://...`. The Node.js 24 agent in `scripts/kitchen-printer-agent.mjs` currently implements **allowlisted TCP ESC/POS** only and rejects unimplemented Bluetooth/USB transports explicitly; it never marks them printed. Use separately paired `kd_live_...` kitchen credentials, signing secret and permitted LAN printer IPv4 destinations. It signs all POST requests with the canonical HMAC v2 format. Network delivery does **not** prove paper came out: monitor printer health and failed jobs, replace rolls, and perform field tests. Browser printing remains a vendor-independent fallback.

For USB/Bluetooth drivers, integrate a certified device adapter and report physical delivery acknowledgements; do not send RAW ESC/POS to unauthorized hosts or expose printer connection endpoints to QR customers.

## Cash and card safety
Offline POS only acknowledges locally held physical cash. A local screen and LAN relay cannot approve card, NFC, MoMo or wallet payments. Cloud reconciliation requires authenticated merchant-scoped `/v1/pos/sync` and preserves original replay keys. `posReceipt` public links are expiring SHA256-token-backed and carry no bank-card numbers. Public table QR requests are awaiting manager approval until an authenticated order and KDS ticket are created.

## Acceptance work requiring connected devices
Full 8-hour internet outage with two POS clients and kitchen tablets; client retries during intermittent Wi-Fi; device power-off during journaling; IndexedDB quota failure; trusted local TLS certificate validation; physical printer-paper-out; 100+ order stress; acquirer-certified NFC/chip transactions; mobile cross-device order mirroring; restore with actual production-sized data. These cannot be proven by source code or CI alone.
