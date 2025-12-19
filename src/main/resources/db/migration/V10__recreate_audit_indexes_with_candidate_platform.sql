-- Drop old indexes that referenced 'platform' (now 'client_platform')
DROP INDEX IF EXISTS idx_audit_candidate_platform_timestamp;
DROP INDEX IF EXISTS idx_audit_candidate_platform_distribution_timestamp;

-- Recreate indexes referencing 'candidate_platform' (the resolved/installed platform)

-- Index for queries filtering by candidate and candidate platform with time-based ordering
CREATE INDEX IF NOT EXISTS idx_audit_candidate_candidate_platform_timestamp
    ON audit (candidate, candidate_platform, timestamp DESC);

-- Partial index for queries filtering by distribution and candidate platform (excludes NULL distributions for efficiency)
CREATE INDEX IF NOT EXISTS idx_audit_candidate_candidate_platform_distribution_timestamp
    ON audit (candidate, candidate_platform, distribution, timestamp DESC)
    WHERE distribution IS NOT NULL;
