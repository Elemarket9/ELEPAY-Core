#!/usr/bin/env bash
# Encrypted PostgreSQL backup. No plaintext dump is written to disk.
set -euo pipefail
umask 077
: "${ELEPAY_BACKUP_DIR:?Set the encrypted backup destination}"
: "${ELEPAY_BACKUP_AGE_RECIPIENT:?Set the age public recipient}"
: "${PGDATABASE:?Set the real database name via libpq env}"
: "${PGHOST:?Set the database server via libpq env}"
command -v pg_dump >/dev/null || { echo 'pg_dump is required' >&2; exit 1; }
command -v age >/dev/null || { echo 'age encryption is required' >&2; exit 1; }
command -v sha256sum >/dev/null || { echo 'sha256sum is required' >&2; exit 1; }
if [[ ! "${ELEPAY_BACKUP_RETENTION_DAYS:-30}" =~ ^[0-9]+$ ]]; then
  echo "Retention days must be a positive integer" >&2; exit 1
fi
mkdir -p -- "$ELEPAY_BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$ELEPAY_BACKUP_DIR/elepay-postgres-$stamp.dump.age"
temporary="$(mktemp "$ELEPAY_BACKUP_DIR/.elepay-backup.XXXXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
# pg_dump reads PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE from environment;
# credentials are never passed in command-line flags or printed in logs.
pg_dump --format=custom --no-owner --no-acl | age -r "$ELEPAY_BACKUP_AGE_RECIPIENT" > "$temporary"
test -s "$temporary" || { echo "Encrypted dump is empty" >&2; exit 1; }
chmod 600 "$temporary"
mv -n -- "$temporary" "$target"
sha256sum "$target" > "$target.sha256"
chmod 600 "$target.sha256"
# Prune only previously encrypted backups; never delete the currently written file.
find "$ELEPAY_BACKUP_DIR" -maxdepth 1 -type f -name 'elepay-postgres-*.dump.age'   -mtime +"$ELEPAY_BACKUP_RETENTION_DAYS" -delete
find "$ELEPAY_BACKUP_DIR" -maxdepth 1 -type f -name 'elepay-postgres-*.dump.age.sha256'   -mtime +"$ELEPAY_BACKUP_RETENTION_DAYS" -delete
echo "Encrypted database archive written: $target"
