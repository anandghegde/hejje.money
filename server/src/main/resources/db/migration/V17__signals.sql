CREATE TABLE signal
(
    id              UUID           PRIMARY KEY,
    version_id      UUID           NOT NULL REFERENCES strategy_version (id),
    strategy_id     UUID           NOT NULL REFERENCES strategy (id),
    deployment_id   UUID           REFERENCES strategy_deployment (id),
    instrument_id   UUID           NOT NULL,
    mode            TEXT           NOT NULL,
    side            TEXT           NOT NULL,
    reference_price NUMERIC(18, 2) NOT NULL,
    stop            NUMERIC(18, 2) NOT NULL,
    target          NUMERIC(18, 2),
    risk_per_unit   NUMERIC(18, 2) NOT NULL,
    bar_time        TIMESTAMPTZ    NOT NULL,
    valid_until     TIMESTAMPTZ    NOT NULL,
    evidence        JSONB          NOT NULL,
    status          TEXT           NOT NULL,
    note            TEXT,
    intent_id       UUID,
    order_id        UUID,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL
);
CREATE INDEX signal_status_idx ON signal (mode, status, created_at DESC);
CREATE INDEX signal_version_idx ON signal (version_id, instrument_id, created_at DESC);

CREATE TABLE strategy_position
(
    id             UUID           PRIMARY KEY,
    signal_id      UUID           NOT NULL REFERENCES signal (id),
    deployment_id  UUID           REFERENCES strategy_deployment (id),
    version_id     UUID           NOT NULL REFERENCES strategy_version (id),
    strategy_id    UUID           NOT NULL REFERENCES strategy (id),
    instrument_id  UUID           NOT NULL,
    mode           TEXT           NOT NULL,
    side           TEXT           NOT NULL,
    quantity       INT            NOT NULL,
    entry_price    NUMERIC(18, 2),
    initial_stop   NUMERIC(18, 2) NOT NULL,
    stop           NUMERIC(18, 2) NOT NULL,
    target         NUMERIC(18, 2),
    entry_order_id UUID,
    stop_order_id  UUID,
    exit_order_id  UUID,
    status         TEXT           NOT NULL,
    close_reason   TEXT,
    exit_price     NUMERIC(18, 2),
    opened_at      TIMESTAMPTZ    NOT NULL,
    closed_at      TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ    NOT NULL
);
CREATE INDEX strategy_position_live_idx ON strategy_position (mode, status) WHERE status <> 'CLOSED';
CREATE INDEX strategy_position_orders_idx ON strategy_position (entry_order_id, stop_order_id, exit_order_id);
