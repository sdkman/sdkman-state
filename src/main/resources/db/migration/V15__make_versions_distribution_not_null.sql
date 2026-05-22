-- Make versions.distribution NOT NULL with an 'NA' sentinel, mirroring the
-- version_tags shape introduced in V12. Removing the NULL state lets
-- PostgresVersionRepository.createOrUpdate fold its two code paths into a
-- single UPSERT: Postgres' default NULLS DISTINCT semantics no longer carve
-- out a deduplication-blind hole for distribution-less rows, so
-- INSERT … ON CONFLICT (candidate, version, distribution, platform) DO UPDATE
-- now fires on every row.

UPDATE versions SET distribution = 'NA' WHERE distribution IS NULL;

ALTER TABLE versions ALTER COLUMN distribution SET DEFAULT 'NA';
ALTER TABLE versions ALTER COLUMN distribution SET NOT NULL;
