#!/usr/bin/env bash
# Recovery drill against a disposable, pre-created PostgreSQL database only.
set -euo pipefail
umask 077
: "${ELEPAY_BACKUP_FILE:?Point at a .dump.age encrypted backup}"
: "${ELEPAY_BACKUP_AGE_IDENTITY:?Private age identity for recovery drill}"
: "${PGDATABASE:?Explicitly select a disposable test database}"
if [[ "$PGDATABASE" != elepay_restore_* ]]; then
  echo "Refusing to restore into any database other than elepay_restore_*" >&2; exit 1
fi
if [[ "${I_CONFIRM_DISPOSABLE_DATABASE:-}" != "yes" ]]; then
  echo "Set I_CONFIRM_DISPOSABLE_DATABASE=yes to acknowledge the destructive test" >&2; exit 1
fi
for exe in age pg_restore psql sha256sum; do
  command -v "$exe" >/dev/null || { echo "$exe is required" >&2; exit 1; }
done
if [[ ! "$ELEPAY_BACKUP_FILE" == *.dump.age ]]; then
  echo "Expected an encrypted .dump.age file" >&2; exit 1
fi
if [[ -f "$ELEPAY_BACKUP_FILE.sha256" ]]; then
  (cd "$(dirname "$ELEPAY_BACKUP_FILE")" &&
     sha256sum -c "$(basename "$ELEPAY_BACKUP_FILE").sha256")
fi
# Do not create a plaintext archive, even in /tmp.
age -d -i "$ELEPAY_BACKUP_AGE_IDENTITY" "$ELEPAY_BACKUP_FILE"   | pg_restore --dbname="$PGDATABASE" --clean --if-exists       --no-owner --no-acl --exit-on-error --single-transaction
psql -X -v ON_ERROR_STOP=1 -Atc   "SELECT 'restored_pos_orders=' || count(*) FROM pos_order;"
echo 'Encrypted archive restored successfully to disposable database'
