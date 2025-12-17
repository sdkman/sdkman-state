-- Rename indexes to reflect vendor -> distribution column rename
ALTER INDEX IF EXISTS idx_audit_candidate_platform_vendor_timestamp
    RENAME TO idx_audit_candidate_platform_distribution_timestamp;

ALTER INDEX IF EXISTS versions_candidate_version_vendor_platform_key
    RENAME TO versions_candidate_version_distribution_platform_key;
