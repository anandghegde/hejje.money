-- M5.1 live-vs-backtest drift: a per-deployment size multiplier, the current drift state and the assessment history.
ALTER TABLE strategy_deployment ADD COLUMN size_multiplier NUMERIC(4, 2) NOT NULL DEFAULT 1.00;

CREATE TABLE drift_state
(
    deployment_id   UUID        PRIMARY KEY REFERENCES strategy_deployment (id),
    version_id      UUID        NOT NULL,
    strategy_id     UUID        NOT NULL,
    status          TEXT        NOT NULL,
    acted_status    TEXT        NOT NULL,   -- the worst status whose actions have been taken (lowered when the status improves)
    triggered       JSONB       NOT NULL,   -- the criteria behind the status
    override_status TEXT,                   -- actions are suppressed while the status is no worse than this
    override_reason TEXT,
    override_by     TEXT,
    override_at     TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE drift_assessment
(
    id            UUID        PRIMARY KEY,
    deployment_id UUID        NOT NULL REFERENCES strategy_deployment (id),
    version_id    UUID        NOT NULL,
    strategy_id   UUID        NOT NULL,
    mode          TEXT        NOT NULL,
    status        TEXT        NOT NULL,
    report        JSONB       NOT NULL,
    actions       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX drift_assessment_deployment_idx ON drift_assessment (deployment_id, created_at DESC);
