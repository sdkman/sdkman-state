-- Index for queries filtering by candidate with time-based ordering
-- Use cases: download counts per candidate, recent activity for a candidate, time-series analysis
CREATE INDEX IF NOT EXISTS idx_audit_candidate_timestamp
    ON audit (candidate, timestamp DESC);

-- Index for queries filtering by candidate and version with time-based ordering
-- Use cases: version-specific download counts, version adoption trends, most downloaded versions per candidate
CREATE INDEX IF NOT EXISTS idx_audit_candidate_version_timestamp
    ON audit (candidate, version, timestamp DESC);

-- Index for queries filtering by candidate and command type with time-based ordering
-- Use cases: command-specific analytics (install vs use), event type filtering, command-specific trends
CREATE INDEX IF NOT EXISTS idx_audit_candidate_command_timestamp
    ON audit (candidate, command, timestamp DESC);

-- Index for queries filtering by time range with candidate grouping
-- Use cases: recent activity across all candidates, time-based filtering with candidate aggregation, last 30 days analytics
CREATE INDEX IF NOT EXISTS idx_audit_timestamp_candidate
    ON audit (timestamp DESC, candidate);

-- Index for queries filtering by candidate and platform with time-based ordering
-- Use cases: platform-specific download statistics, platform adoption trends, architecture/OS analysis
CREATE INDEX IF NOT EXISTS idx_audit_candidate_platform_timestamp
    ON audit (candidate, platform, timestamp DESC);

-- Partial index for queries filtering by vendor and platform (excludes NULL vendors for efficiency)
-- Use cases: platform and vendor popularity analysis, Java distribution comparisons (Zulu vs Temurin) per platform, vendor-specific trends
CREATE INDEX IF NOT EXISTS idx_audit_candidate_platform_vendor_timestamp
    ON audit (candidate, platform, vendor, timestamp DESC)
    WHERE vendor IS NOT NULL;