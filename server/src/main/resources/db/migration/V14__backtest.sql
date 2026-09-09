CREATE TABLE backtest
(
    id                UUID        PRIMARY KEY,
    version_id        UUID        NOT NULL REFERENCES strategy_version (id),
    spec              JSONB       NOT NULL,
    status            TEXT        NOT NULL,
    progress_pct      INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL,
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    metrics           JSONB,
    by_split          JSONB,
    windows           JSONB,
    warnings          JSONB,
    sessions_expected INT         NOT NULL DEFAULT 0,
    sessions_with_data INT        NOT NULL DEFAULT 0,
    skipped_signals   INT         NOT NULL DEFAULT 0,
    result_hash       TEXT,
    engine            TEXT        NOT NULL,
    error             TEXT,
    created_by        TEXT        NOT NULL
);
CREATE INDEX backtest_version_idx ON backtest (version_id, created_at DESC);

CREATE TABLE backtest_trade
(
    id            UUID           PRIMARY KEY,
    backtest_id   UUID           NOT NULL REFERENCES backtest (id) ON DELETE CASCADE,
    instrument_id UUID           NOT NULL,
    split         TEXT           NOT NULL,
    entry_time    TIMESTAMPTZ    NOT NULL,
    exit_time     TIMESTAMPTZ    NOT NULL,
    side          TEXT           NOT NULL,
    qty           INT            NOT NULL,
    entry_price   NUMERIC(18, 2) NOT NULL,
    exit_price    NUMERIC(18, 2) NOT NULL,
    stop          NUMERIC(18, 2),
    target        NUMERIC(18, 2),
    gross_paise   BIGINT         NOT NULL,
    costs_paise   BIGINT         NOT NULL,
    net_paise     BIGINT         NOT NULL,
    r_multiple    DOUBLE PRECISION NOT NULL,
    exit_reason   TEXT           NOT NULL,
    evidence      JSONB          NOT NULL
);
CREATE INDEX backtest_trade_backtest_idx ON backtest_trade (backtest_id, entry_time);
