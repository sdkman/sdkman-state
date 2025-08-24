-- Drop and recreate the versions table with vendor column nullable and modern TEXT types
DROP TABLE IF EXISTS versions;

CREATE TABLE versions
(
    id              SERIAL PRIMARY KEY,
    candidate       TEXT    NOT NULL,
    version         TEXT    NOT NULL,
    vendor          TEXT NULL,
    platform        TEXT    NOT NULL,
    visible         BOOLEAN NOT NULL,
    url             TEXT    NOT NULL,
    md5_sum         TEXT,
    sha_256_sum     TEXT,
    sha_512_sum     TEXT,
    created_at      TIMESTAMP DEFAULT now(),
    last_updated_at TIMESTAMP DEFAULT now(),
    UNIQUE (candidate, version, vendor, platform)
);