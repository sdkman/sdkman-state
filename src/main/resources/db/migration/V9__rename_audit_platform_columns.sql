-- Rename audit table columns to better reflect their purpose

ALTER TABLE audit RENAME COLUMN platform TO client_platform;
ALTER TABLE audit RENAME COLUMN dist TO candidate_platform;
