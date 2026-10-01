-- Widen versions.version from VARCHAR(25) (V1, V2) to TEXT. The semverish
-- grammar bounds neither component nor identifier length, and the mandatory
-- fourth core component lengthens every java version, so a grammatically
-- valid version must never fail on column length. See
-- specs/semverish-four-component-core.md, section Storage.

ALTER TABLE versions ALTER COLUMN version TYPE TEXT;
