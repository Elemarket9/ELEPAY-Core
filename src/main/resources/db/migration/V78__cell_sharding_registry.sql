-- Stage 4A: Cell-based sharding registry, merchant cell assignments, and cell migration jobs.

CREATE TABLE IF NOT EXISTS cell_registry (
    cell_id          TEXT        PRIMARY KEY,
    display_name     TEXT        NOT NULL,
    jdbc_url         TEXT,
    replica_jdbc_url TEXT,
    status           TEXT        NOT NULL DEFAULT 'ACTIVE'
                                 CHECK (status IN ('ACTIVE', 'DRAINING', 'READ_ONLY', 'OFFLINE')),
    weight           INT         NOT NULL DEFAULT 100
                                 CHECK (weight > 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO cell_registry (cell_id, display_name, status, weight)
VALUES ('cell-0', 'Primary Default Cell', 'ACTIVE', 100)
ON CONFLICT (cell_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS merchant_cell_assignment (
    merchant_id       UUID        PRIMARY KEY,
    cell_id           TEXT        NOT NULL REFERENCES cell_registry (cell_id),
    affinity_group_id UUID,
    pinned            BOOLEAN     NOT NULL DEFAULT false,
    assigned_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_merchant_cell_assignment_cell_id
    ON merchant_cell_assignment (cell_id);

CREATE INDEX IF NOT EXISTS idx_merchant_cell_assignment_affinity_group_id
    ON merchant_cell_assignment (affinity_group_id);

CREATE TABLE IF NOT EXISTS cell_migration_job (
    id                UUID        PRIMARY KEY,
    merchant_id       UUID        NOT NULL,
    source_cell_id    TEXT        NOT NULL REFERENCES cell_registry (cell_id),
    target_cell_id    TEXT        NOT NULL REFERENCES cell_registry (cell_id),
    state             TEXT        NOT NULL DEFAULT 'PLANNED'
                                  CHECK (state IN (
                                      'PLANNED',
                                      'DUAL_WRITE',
                                      'BACKFILLING',
                                      'VERIFYING',
                                      'CUTOVER',
                                      'COMPLETED',
                                      'ROLLED_BACK',
                                      'FAILED'
                                  )),
    created_by        TEXT        NOT NULL,
    reason            TEXT,
    rows_copied       BIGINT      NOT NULL DEFAULT 0,
    verification_hash TEXT,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_cell_migration_job_merchant_state
    ON cell_migration_job (merchant_id, state);
