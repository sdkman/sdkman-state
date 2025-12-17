ALTER TABLE versions RENAME COLUMN vendor TO distribution;
ALTER TABLE audit RENAME COLUMN vendor TO distribution;
