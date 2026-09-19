-- Candidate metadata, migrated from the MongoDB `candidates` collection.
--
-- `candidate` is the natural primary key but NOT a foreign-key target: `versions.candidate` and
-- `version_tags.candidate` stay plain columns and the registry is enforced in the application
-- (docs/decisions/0008). TEXT matches both of them exactly, so taking that trade later is a
-- one-line ALTER per table rather than a type migration. The CHECK carries the shape the request
-- validator enforces, so the two cannot drift.
--
-- TIMESTAMPTZ follows `vendors` (V13) rather than the zoneless `versions` (V2) and `version_tags`
-- (V12). The conventions already disagree here; this table follows the one whose output matches,
-- since an instant cannot be rendered from a zoneless column without assuming the server's zone.
--
-- The table ships empty -- rows arrive over POST /admin/candidates after deploy.
--
-- Deliberately absent:
--   `default`      -- derived from version_tags at read time, never stored
--   `distribution` -- the Mongo platform classification; unreliable, and collides by name with
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
