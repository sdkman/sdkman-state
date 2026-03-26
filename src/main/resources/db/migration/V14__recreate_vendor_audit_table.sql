-- Drop existing vendor_audit table (created by V11) and recreate with
-- vendor_id UUID + email TEXT replacing the old username TEXT column.
-- Service is not yet live, so no data loss concern.

DROP TABLE IF EXISTS vendor_audit;

CREATE TABLE vendor_audit (
    id           BIGSERIAL PRIMARY KEY,
    vendor_id    UUID NOT NULL,
    email        TEXT NOT NULL,
    timestamp    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    operation    TEXT NOT NULL,
    version_data JSONB NOT NULL
);

-- Indexes for common query patterns
CREATE INDEX idx_vendor_audit_vendor_id ON vendor_audit(vendor_id);
CREATE INDEX idx_vendor_audit_email ON vendor_audit(email);
CREATE INDEX idx_vendor_audit_timestamp ON vendor_audit(timestamp DESC);
CREATE INDEX idx_vendor_audit_operation ON vendor_audit(operation);

-- GIN index on jsonb column for efficient JSON querying
CREATE INDEX idx_vendor_audit_version_data ON vendor_audit USING GIN(version_data);
