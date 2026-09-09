CREATE TABLE recommendation
(
    id            UUID           PRIMARY KEY,
    computed_at   TIMESTAMPTZ    NOT NULL,
    mode          TEXT           NOT NULL,
    version_id    UUID           NOT NULL REFERENCES strategy_version (id),
    instrument_id UUID           NOT NULL,
    signal_id     UUID           REFERENCES signal (id),
    score         INT,
    decision      TEXT           NOT NULL,
    direction     TEXT,
    entry         NUMERIC(18, 2),
    stop          NUMERIC(18, 2),
    target        NUMERIC(18, 2),
    risk_paise    BIGINT,
    hard_blocks   JSONB          NOT NULL,
    evidence      JSONB          NOT NULL,
    risks         JSONB          NOT NULL
);
CREATE INDEX recommendation_signal_idx ON recommendation (signal_id, computed_at DESC);
CREATE INDEX recommendation_time_idx ON recommendation (mode, computed_at DESC);

CREATE TABLE trade_review
(
    id                   UUID             PRIMARY KEY,
    mode                 TEXT             NOT NULL,
    position_id          UUID,
    strategy_position_id UUID,
    strategy_id          UUID,
    strategy_version_id  UUID,
    signal_id            UUID,
    instrument_id        UUID             NOT NULL,
    entry_order_id       UUID             NOT NULL UNIQUE,
    side                 TEXT             NOT NULL,
    quantity             INT              NOT NULL,
    entry_price          NUMERIC(18, 2)   NOT NULL,
    exit_price           NUMERIC(18, 2)   NOT NULL,
    opened_at            TIMESTAMPTZ      NOT NULL,
    closed_at            TIMESTAMPTZ      NOT NULL,
    gross_paise          BIGINT           NOT NULL,
    fees_paise           BIGINT           NOT NULL,
    net_paise            BIGINT           NOT NULL,
    outcome_r            DOUBLE PRECISION,
    expected_setup_valid BOOLEAN,
    entry_slippage_bps   DOUBLE PRECISION,
    exit_slippage_bps    DOUBLE PRECISION,
    rule_adherence_pct   INT,
    close_reason         TEXT,
    context              JSONB            NOT NULL,
    notes                TEXT,
    created_at           TIMESTAMPTZ      NOT NULL
);
CREATE INDEX trade_review_time_idx ON trade_review (mode, closed_at DESC);
