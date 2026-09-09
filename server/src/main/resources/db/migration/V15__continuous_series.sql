-- Stitched continuous futures series (docs/data.md). Candles live in the historical store under the series id.
CREATE TABLE continuous_series
(
    id         UUID           PRIMARY KEY,
    underlying TEXT           NOT NULL,
    exchange   TEXT           NOT NULL,
    symbol     TEXT           NOT NULL UNIQUE,
    lot_size   INT            NOT NULL,
    tick_size  NUMERIC(10, 4) NOT NULL,
    segments   JSONB          NOT NULL,
    built_at   TIMESTAMPTZ    NOT NULL
);
