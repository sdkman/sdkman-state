CREATE TABLE version
(
    id          SERIAL PRIMARY KEY,
    candidate   VARCHAR(20)  NOT NULL,
    version     VARCHAR(25)  NOT NULL,
    platform    VARCHAR(15)  NOT NULL,
    visible     BOOLEAN      NOT NULL,
    url         VARCHAR(500) NOT NULL,
    vendor      VARCHAR(10),
    md5_sum     VARCHAR(32),
    sha_256_sum VARCHAR(64),
    sha_512_sum VARCHAR(128),
    UNIQUE (candidate, version, platform)
)