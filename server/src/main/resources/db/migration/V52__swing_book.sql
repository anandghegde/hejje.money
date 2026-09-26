-- Plan M11.1: delivery (CNC) positions that carry overnight.

-- The PAPER adapter's simulated delivery book (holdings by session), one JSON document per key, so it survives a restart.
CREATE TABLE paper_state
(
    key        text        PRIMARY KEY,
    state      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL
);

-- The swing book: one row per delivery round trip (open until the CNC position returns to zero).
CREATE TABLE swing_position
(
    id             uuid          PRIMARY KEY,
    mode           text          NOT NULL,
    position_id    uuid          NOT NULL,
    instrument_id  uuid          NOT NULL,
    strategy_id    uuid,
    entry_order_id uuid          NOT NULL,
    opened_at      timestamptz   NOT NULL,
    entry_date     date          NOT NULL,
    quantity       int           NOT NULL,
    entry_price    numeric(18, 2) NOT NULL,
    initial_stop   numeric(18, 2),
    goal           numeric(18, 2),
    status         text          NOT NULL,
    closed_at      timestamptz,
    exit_date      date,
    exit_price     numeric(18, 2),
    holding_days   int,
    updated_at     timestamptz   NOT NULL
);
CREATE UNIQUE INDEX swing_position_open_idx ON swing_position (position_id) WHERE status = 'OPEN';
CREATE INDEX swing_position_mode_idx ON swing_position (mode, status, opened_at DESC);

-- Reviews carry their horizon and the sessions held.
ALTER TABLE trade_review ADD COLUMN horizon      text NOT NULL DEFAULT 'INTRADAY';
ALTER TABLE trade_review ADD COLUMN holding_days int  NOT NULL DEFAULT 0;
