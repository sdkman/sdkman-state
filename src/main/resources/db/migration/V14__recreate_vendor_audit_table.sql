-- Drop existing vendor_audit table and its indexes (service not yet live — no data to preserve)
DROP INDEX IF EXISTS idx_vendor_audit_username;
DROP INDEX IF EXISTS idx_vendor_audit_timestamp;
DROP INDEX IF EXISTS idx_vendor_audit_operation;
DROP INDEX IF EXISTS idx_vendor_audit_version_data;
DROP TABLE IF EXISTS vendor_audit;

-- Recreate with vendor_id (UUID) and email (TEXT) replacing username
CREATE TABLE vendor_audit (
    id            BIGSERIAL PRIMARY KEY,
    vendor_id     UUID NOT NULL,
    email         TEXT NOT NULL,
    timestamp     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    operation     TEXT NOT NULL,
    version_data  JSONB NOT NULL
);

CREATE INDEX idx_vendor_audit_vendor_id ON vendor_audit(vendor_id);
CREATE INDEX idx_vendor_audit_email ON vendor_audit(email);
CREATE INDEX idx_vendor_audit_timestamp ON vendor_audit(timestamp DESC);
CREATE INDEX idx_vendor_audit_operation ON vendor_audit(operation);
CREATE INDEX idx_vendor_audit_version_data ON vendor_audit USING GIN(version_data);
