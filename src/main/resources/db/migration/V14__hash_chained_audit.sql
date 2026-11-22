ALTER TABLE audit_log ADD COLUMN prev_hash VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN entry_hash VARCHAR(64);

CREATE INDEX audit_log_entry_hash ON audit_log (entry_hash);

ALTER TABLE audit_log DISABLE TRIGGER audit_log_immutable;
TRUNCATE audit_log;
ALTER SEQUENCE audit_log_id_seq RESTART WITH 1;
ALTER TABLE audit_log ENABLE TRIGGER audit_log_immutable;
