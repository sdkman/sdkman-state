DROP TABLE IF EXISTS audit;

CREATE TABLE audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    command TEXT NOT NULL,
    candidate TEXT NOT NULL,
    version TEXT NOT NULL,
    platform TEXT NOT NULL,
    dist TEXT NOT NULL,
    vendor TEXT NULL,
    host TEXT NULL,
    agent TEXT NULL,
    timestamp TIMESTAMP NOT NULL
);
