-- TODO: The table doesn't hold any valuable data yet. Instead, drop the entire table and re-create with new schema
-- Make vendor column nullable and clear existing data as specified
DELETE FROM versions;
ALTER TABLE versions ALTER COLUMN vendor DROP NOT NULL;
ALTER TABLE versions ALTER COLUMN vendor DROP DEFAULT;