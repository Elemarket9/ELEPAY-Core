-- V44: Enforce GhIPSS Unique RRN & STAN and Geo-Velocity Checkpoint Schema Synchronization

DROP INDEX IF EXISTS idx_ghipss_rrn_stan;

ALTER TABLE ghipss_clearing_record
    ADD CONSTRAINT uq_ghipss_rrn_stan UNIQUE (rrn, stan);

ALTER TABLE geo_velocity_checkpoint
    ADD COLUMN IF NOT EXISTS accuracy_radius_meters DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS is_vpn BOOLEAN NOT NULL DEFAULT false;
