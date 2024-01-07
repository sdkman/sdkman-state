DROP TABLE version;
CREATE TABLE versions
(
    id              SERIAL PRIMARY KEY,
    candidate       VARCHAR(20)  NOT NULL,
    version         VARCHAR(25)  NOT NULL,
    vendor          VARCHAR(10)  NOT NULL DEFAULT 'NONE',
    platform        VARCHAR(15)  NOT NULL,
    visible         BOOLEAN      NOT NULL,
    url             VARCHAR(500) NOT NULL,
    md5_sum         VARCHAR(32),
    sha_256_sum     VARCHAR(64),
    sha_512_sum     VARCHAR(128),
    created_at      TIMESTAMP             DEFAULT now(),
    last_updated_at TIMESTAMP             DEFAULT now(),
    UNIQUE (candidate, version, vendor, platform)
)
