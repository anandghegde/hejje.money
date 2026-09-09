CREATE TABLE strategy_score
(
    id               UUID          PRIMARY KEY,
    version_id       UUID          NOT NULL REFERENCES strategy_version (id),
    instrument_id    UUID,
    computed_at      TIMESTAMPTZ   NOT NULL,
    base_backtest_id UUID,
    base             NUMERIC(5, 1) NOT NULL,
    cap              TEXT,
    components       JSONB         NOT NULL,
    adjustments      JSONB         NOT NULL,
    final            INT           NOT NULL
);
CREATE INDEX strategy_score_version_idx ON strategy_score (version_id, instrument_id, computed_at DESC);
