-- M4.7: strategy experiments. Variants are definitions derived from a base version by a delta; they are backtested but never
-- stored as versions unless promoted (which creates a normal DRAFT version).
CREATE TABLE experiment
(
    id                 UUID        PRIMARY KEY,
    base_version_id    UUID        NOT NULL REFERENCES strategy_version (id),
    strategy_id        UUID        NOT NULL,
    goal               TEXT,
    dataset            JSONB       NOT NULL,   -- instrumentIds, from, to, timeframe, slippageBps
    splits             JSONB       NOT NULL,
    status             TEXT        NOT NULL,   -- QUEUED | RUNNING | DONE | FAILED
    created_by         TEXT        NOT NULL,
    created_by_session UUID,                   -- the agent session when an agent started it
    created_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ,
    error              TEXT,
    notes              JSONB       NOT NULL
);
CREATE INDEX experiment_version_idx ON experiment (base_version_id, created_at DESC);

CREATE TABLE experiment_variant
(
    id                  UUID             PRIMARY KEY,
    experiment_id       UUID             NOT NULL REFERENCES experiment (id),
    ordinal             INT              NOT NULL,
    name                TEXT             NOT NULL,
    description         TEXT,
    delta               JSONB            NOT NULL,
    definition_yaml     TEXT,
    status              TEXT             NOT NULL,   -- QUEUED | DONE | FAILED | INVALID
    metrics             JSONB,
    rank                INT,
    score               DOUBLE PRECISION,
    verdict             TEXT,
    warnings            JSONB            NOT NULL,
    parameter_count     INT              NOT NULL,
    condition_count     INT              NOT NULL,
    error               TEXT,
    promoted_version_id UUID,
    UNIQUE (experiment_id, name)
);
