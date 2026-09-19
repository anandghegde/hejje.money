-- M7.2 replay sessions of a SIM instance: what was asked (spec), where the replay is, and the result once done.
CREATE TABLE sim_session
(
    id             UUID        PRIMARY KEY,
    spec           JSONB       NOT NULL,
    state          TEXT        NOT NULL,   -- PAUSED | PLAYING | DONE | FAILED | CANCELLED
    speed          TEXT        NOT NULL,   -- 1 | 10 | 60 | 300 | MAX
    day_index      INT         NOT NULL,
    days           INT         NOT NULL,
    session_date   DATE,
    step           INT         NOT NULL,   -- replay steps (minutes) done in the current day, 0..375
    fills          INT         NOT NULL DEFAULT 0,
    friction_paise BIGINT      NOT NULL DEFAULT 0,
    net_pnl_paise  BIGINT      NOT NULL DEFAULT 0,
    result_hash    TEXT,
    error          TEXT,
    created_by     TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX sim_session_created_idx ON sim_session (created_at DESC);
