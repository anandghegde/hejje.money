CREATE TABLE instrument
(
    id          UUID          PRIMARY KEY,
    symbol      TEXT          NOT NULL,
    name        TEXT,
    exchange    TEXT          NOT NULL,
    type        TEXT          NOT NULL,
    underlying  TEXT,
    expiry      DATE,
    strike      NUMERIC(14, 2),
    option_type TEXT,
    lot_size    INT           NOT NULL,
    tick_size   NUMERIC(10, 4) NOT NULL,
    isin        TEXT,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT instrument_natural_key UNIQUE NULLS NOT DISTINCT (exchange, symbol, type, expiry, strike, option_type)
);

CREATE INDEX instrument_underlying_idx ON instrument (underlying, type, expiry) WHERE underlying IS NOT NULL;
CREATE INDEX instrument_symbol_idx ON instrument (upper(symbol) text_pattern_ops);
CREATE INDEX instrument_name_idx ON instrument (upper(name));

CREATE TABLE broker_instrument_mapping
(
    id               UUID        PRIMARY KEY,
    instrument_id    UUID        NOT NULL REFERENCES instrument (id),
    broker           TEXT        NOT NULL,
    broker_token     TEXT        NOT NULL,
    trading_symbol   TEXT        NOT NULL,
    exchange_segment TEXT        NOT NULL,
    raw              JSONB       NOT NULL DEFAULT '{}'::jsonb,
    synced_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT broker_instrument_mapping_token UNIQUE (broker, broker_token)
);

CREATE INDEX broker_instrument_mapping_instrument_idx ON broker_instrument_mapping (instrument_id, broker);
CREATE INDEX broker_instrument_mapping_symbol_idx ON broker_instrument_mapping (broker, exchange_segment, trading_symbol);
CREATE INDEX broker_instrument_mapping_synced_idx ON broker_instrument_mapping (broker, synced_at);
