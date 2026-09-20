CREATE TABLE candidates
(
    candidate       TEXT         PRIMARY KEY CHECK (candidate ~ '^[a-z][a-z0-9]{0,19}$'),
    name            TEXT         NOT NULL,
    description     TEXT         NOT NULL,
    website_url     TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
