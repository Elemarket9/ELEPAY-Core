-- Preserve existing records, but withdraw unsupported certification claims.
ALTER TABLE partner_certification DROP CONSTRAINT partner_certification_certification_level_check;
ALTER TABLE partner_certification ADD CONSTRAINT partner_certification_certification_level_check
    CHECK (certification_level IN ('PENDING_REVIEW','SANDBOX_VERIFIED','STAGING_TESTED','PRODUCTION_CERTIFIED'));
ALTER TABLE partner_certification ALTER COLUMN certification_level SET DEFAULT 'PENDING_REVIEW';
ALTER TABLE partner_certification ADD COLUMN prior_certification jsonb;
UPDATE partner_certification SET prior_certification = jsonb_build_object(
    'level', certification_level, 'status', status, 'passed', test_runs_passed,
    'certifiedAt', certified_at, 'expiresAt', expires_at),
    certification_level = 'PENDING_REVIEW', status = 'IN_PROGRESS',
    test_runs_passed = 0, certified_at = NULL, expires_at = NULL;
