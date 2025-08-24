-- Make vendor column nullable and clear existing data as specified
DELETE FROM versions;
ALTER TABLE versions ALTER COLUMN vendor DROP NOT NULL;
ALTER TABLE versions ALTER COLUMN vendor DROP DEFAULT;