-- Replace the 'NA' distribution sentinel with SQL NULL on both tables.
-- The sentinel (versions: V15, version_tags: V12) only existed to keep ON CONFLICT
-- dedup working under Postgres' default NULLS DISTINCT uniqueness. Postgres 15's
-- UNIQUE NULLS NOT DISTINCT preserves that dedup with a nullable column, so the
-- sentinel is no longer needed. Constraints are dropped by discovered name because
-- V7 renamed versions.vendor -> distribution without renaming the constraint.

-- versions ---------------------------------------------------------------------
DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'versions'::regclass AND contype = 'u';
    EXECUTE format('ALTER TABLE versions DROP CONSTRAINT %I', constraint_name);
END $$;

ALTER TABLE versions ALTER COLUMN distribution DROP DEFAULT;
ALTER TABLE versions ALTER COLUMN distribution DROP NOT NULL;
UPDATE versions SET distribution = NULL WHERE distribution = 'NA';

ALTER TABLE versions
    ADD CONSTRAINT versions_candidate_version_distribution_platform_key
    UNIQUE NULLS NOT DISTINCT (candidate, version, distribution, platform);

-- version_tags -----------------------------------------------------------------
DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'version_tags'::regclass AND contype = 'u';
    EXECUTE format('ALTER TABLE version_tags DROP CONSTRAINT %I', constraint_name);
END $$;

ALTER TABLE version_tags ALTER COLUMN distribution DROP DEFAULT;
ALTER TABLE version_tags ALTER COLUMN distribution DROP NOT NULL;
UPDATE version_tags SET distribution = NULL WHERE distribution = 'NA';

ALTER TABLE version_tags
    ADD CONSTRAINT version_tags_candidate_tag_distribution_platform_key
    UNIQUE NULLS NOT DISTINCT (candidate, tag, distribution, platform);
