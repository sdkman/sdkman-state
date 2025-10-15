-- flyway:no-transaction

-- Index for queries filtering by candidate with time-based ordering
-- Use cases: download counts per candidate, recent activity for a candidate, time-series analysis
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_candidate_timestamp
ON audit(candidate, timestamp DESC);