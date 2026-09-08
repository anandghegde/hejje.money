CREATE TABLE market_candle
(
    instrument_id UUID          NOT NULL,
    timeframe     TEXT          NOT NULL,
    open_time     TIMESTAMPTZ   NOT NULL,
    open          NUMERIC(18, 2) NOT NULL,
    high          NUMERIC(18, 2) NOT NULL,
    low           NUMERIC(18, 2) NOT NULL,
    close         NUMERIC(18, 2) NOT NULL,
    volume        BIGINT        NOT NULL DEFAULT 0,
    oi            BIGINT        NOT NULL DEFAULT 0,
    synthetic     BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (instrument_id, timeframe, open_time)
);

CREATE INDEX market_candle_open_time_idx ON market_candle (open_time);
