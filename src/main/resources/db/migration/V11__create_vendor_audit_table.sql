CREATE TABLE IF NOT EXISTS vendor_audit (
    id BIGSERIAL PRIMARY KEY,
    username TEXT NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    operation TEXT NOT NULL,
    version_data JSONB NOT NULL
);

-- Indexes for common query patterns
CREATE INDEX idx_vendor_audit_username ON vendor_audit(username);
CREATE INDEX idx_vendor_audit_timestamp ON vendor_audit(timestamp DESC);
CREATE INDEX idx_vendor_audit_operation ON vendor_audit(operation);

-- GIN index on jsonb column for efficient JSON querying
CREATE INDEX idx_vendor_audit_version_data ON vendor_audit USING GIN(version_data);
