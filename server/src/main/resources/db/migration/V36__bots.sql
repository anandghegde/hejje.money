-- M7.3 bots: programs that send decisions (never orders). Each bot trades through a backing strategy (family bot, or an
-- existing strategy for a STRATEGY bot) and its deployments, so autonomy, budgets and drift apply unchanged.
CREATE TABLE bot
(
    id                     UUID        PRIMARY KEY,
    name                   TEXT        NOT NULL UNIQUE,
    version                TEXT        NOT NULL,
    kind                   TEXT        NOT NULL,   -- EXTERNAL | STRATEGY | LLM
    knowledge_cutoff       DATE,
    allowed_modes          JSONB       NOT NULL,
    strategy_id            UUID        NOT NULL,
    timeframe              TEXT        NOT NULL,
    decision_every_minutes INT,
    universe               JSONB       NOT NULL,
    enabled                BOOLEAN     NOT NULL,
    created_by             TEXT        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL
);

-- One row per (bot, decision point, instrument); '*' for a point-level row (a SKIPPED point).
CREATE TABLE bot_decision
(
    id          UUID           PRIMARY KEY,
    bot_id      UUID           NOT NULL REFERENCES bot (id),
    point_id    TEXT           NOT NULL,
    instrument  TEXT           NOT NULL,
    action      TEXT           NOT NULL,
    stop        NUMERIC(18, 2),
    target      NUMERIC(18, 2),
    confidence  NUMERIC(6, 4),
    thesis      TEXT,
    stage       TEXT,
    scores      JSONB,
    candidates  JSONB,
    latency_ms  BIGINT,
    outcome     TEXT           NOT NULL,   -- EXECUTED | APPROVAL | AUTO | EXITING | MOVED | NOTED | REFUSED | SKIPPED
    detail      TEXT,
    signal_id   UUID,
    order_id    UUID,
    mode        TEXT           NOT NULL,
    decided_at  TIMESTAMPTZ    NOT NULL,   -- simulation time in SIM
    CONSTRAINT bot_decision_point UNIQUE (bot_id, point_id, instrument)
);
CREATE INDEX bot_decision_bot_idx ON bot_decision (bot_id, decided_at DESC);
CREATE INDEX bot_decision_signal_idx ON bot_decision (signal_id);

ALTER TABLE sim_session ADD COLUMN warnings JSONB NOT NULL DEFAULT '[]'::jsonb;
