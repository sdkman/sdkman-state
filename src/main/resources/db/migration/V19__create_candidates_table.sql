-- Candidate metadata, migrated from the MongoDB `candidates` collection.
-- `candidate` is the natural primary key. It is NOT a foreign-key target:
-- `versions.candidate` and `version_tags.candidate` stay plain columns, and the
-- registry is enforced in the application (docs/decisions/0008).
--
-- The column is still TEXT, matching them exactly -- `versions` was recreated
-- with modern TEXT types in V5, superseding V2's VARCHAR(20), and V12 declared
-- version_tags.candidate TEXT -- so that adding the constraint later stays a
-- one-line ALTER per table rather than a type migration. The CHECK carries the
-- shape the request validator enforces, so the two cannot drift.
--
-- This migration creates the table and stops. The rows arrive over
-- POST /admin/candidates after deploy; see the spec's Rollout section.
--
-- Note: the existing version repository maps `versions.candidate` as a
-- varchar of length 20. That is a pre-existing mismap of a TEXT column, and
-- not evidence of what the column actually is.
--
-- The timestamps are TIMESTAMPTZ, following `vendors` (V13) rather than
-- `versions` (V2) and `version_tags` (V12), which are zoneless. The two
-- conventions already disagree in this schema, and this table follows the one
-- whose output shape matches: CandidateAdminDto renders these as ISO-8601
-- instants exactly as VendorResponse does, and an instant cannot be rendered
-- from a zoneless column without silently assuming the server's zone.
--
-- Deliberately absent:
--   `default`      -- derived from version_tags at read time, never stored
--   `distribution` -- the Mongo platform classification (UNIVERSAL /
--                     PLATFORM_SPECIFIC); unreliable, and collides by name with
--                     the java vendor distribution. See docs/decisions/0007.

CREATE TABLE candidates
(
    candidate       TEXT         PRIMARY KEY CHECK (candidate ~ '^[a-z][a-z0-9]{0,19}$'),
    name            TEXT         NOT NULL,
    description     TEXT         NOT NULL,
    website_url     TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
