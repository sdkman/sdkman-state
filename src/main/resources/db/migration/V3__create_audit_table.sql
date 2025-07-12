CREATE TABLE audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    command TEXT NOT NULL,
    candidate TEXT NOT NULL,
    version TEXT NOT NULL,
    host TEXT NOT NULL,
    agent TEXT NOT NULL,
    platform TEXT NOT NULL,
    dist TEXT NOT NULL,
    timestamp TIMESTAMP NOT NULL
);

