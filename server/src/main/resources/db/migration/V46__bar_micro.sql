-- Plan M9.4: order-book and trade-flow features per bar, built from live or recorded ticks (docs/market-data.md). Kept
-- for the operational window like market_candle (pruned with it); never written to the Parquet history.
CREATE TABLE bar_micro (
    instrument_id    uuid              NOT NULL,
    timeframe        text              NOT NULL,
    open_time        timestamptz       NOT NULL,
    imbalance_close  double precision,
    imbalance_mean   double precision,
    buy_sell_ratio   double precision,
    up_volume_share  double precision,
    up_volume        bigint            NOT NULL,
    down_volume      bigint            NOT NULL,
    ticks            integer           NOT NULL,
    depth_ticks      integer           NOT NULL,
    PRIMARY KEY (instrument_id, timeframe, open_time)
);
