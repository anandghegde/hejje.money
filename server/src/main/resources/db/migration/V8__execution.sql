CREATE TABLE order_intent
(
    id                UUID          PRIMARY KEY,
    idempotency_key   TEXT          NOT NULL,
    client_id         UUID          NOT NULL,
    source            TEXT          NOT NULL,
    actor_id          TEXT,
    strategy_id       UUID,
    signal_id         UUID,
    instrument_id     UUID          NOT NULL,
    side              TEXT          NOT NULL,
    quantity          INT           NOT NULL,
    order_type        TEXT          NOT NULL,
    product           TEXT          NOT NULL,
    limit_price       NUMERIC(18, 2),
    trigger_price     NUMERIC(18, 2),
    stop_price        NUMERIC(18, 2),
    target_price      NUMERIC(18, 2),
    max_risk_paise    BIGINT,
    reason            TEXT          NOT NULL,
    mode              TEXT          NOT NULL,
    status            TEXT          NOT NULL,
    validation_errors JSONB         NOT NULL DEFAULT '[]'::jsonb,
    created_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT order_intent_idempotency UNIQUE (client_id, idempotency_key)
);

CREATE TABLE risk_decision
(
    id         UUID        PRIMARY KEY,
    intent_id  UUID        NOT NULL REFERENCES order_intent (id),
    outcome    TEXT        NOT NULL,
    checks     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    snapshot   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX risk_decision_intent_idx ON risk_decision (intent_id);

CREATE TABLE hejje_order
(
    id               UUID          PRIMARY KEY,
    intent_id        UUID          REFERENCES order_intent (id),
    mode             TEXT          NOT NULL,
    broker           TEXT          NOT NULL,
    broker_order_id  TEXT,
    tag              TEXT          NOT NULL,
    instrument_id    UUID          NOT NULL,
    side             TEXT          NOT NULL,
    quantity         INT           NOT NULL,
    filled_quantity  INT           NOT NULL DEFAULT 0,
    average_price    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    order_type       TEXT          NOT NULL,
    product          TEXT          NOT NULL,
    limit_price      NUMERIC(18, 2),
    trigger_price    NUMERIC(18, 2),
    state            TEXT          NOT NULL,
    last_broker_status TEXT,
    placed_at        TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ   NOT NULL,
    raw              JSONB         NOT NULL DEFAULT '{}'::jsonb,
    parent_order_id  UUID,
    role             TEXT,
    CONSTRAINT hejje_order_tag UNIQUE (mode, tag)
);
CREATE UNIQUE INDEX hejje_order_broker_id_idx ON hejje_order (broker, broker_order_id) WHERE broker_order_id IS NOT NULL;
CREATE INDEX hejje_order_state_idx ON hejje_order (state);
CREATE INDEX hejje_order_instrument_idx ON hejje_order (instrument_id);

CREATE TABLE order_event
(
    id         UUID        PRIMARY KEY,
    order_id   UUID        NOT NULL REFERENCES hejje_order (id),
    seq        BIGINT      NOT NULL,
    from_state TEXT,
    to_state   TEXT        NOT NULL,
    source     TEXT        NOT NULL,
    payload    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ts         TIMESTAMPTZ NOT NULL,
    CONSTRAINT order_event_seq UNIQUE (order_id, seq)
);

CREATE TABLE trade
(
    id              UUID          PRIMARY KEY,
    order_id        UUID          NOT NULL REFERENCES hejje_order (id),
    broker_trade_id TEXT,
    instrument_id   UUID          NOT NULL,
    side            TEXT          NOT NULL,
    quantity        INT           NOT NULL,
    price           NUMERIC(18, 2) NOT NULL,
    ts              TIMESTAMPTZ   NOT NULL,
    mode            TEXT          NOT NULL,
    strategy_id     UUID,
    CONSTRAINT trade_broker_id UNIQUE (order_id, broker_trade_id)
);
CREATE INDEX trade_ts_idx ON trade (ts);
CREATE INDEX trade_instrument_idx ON trade (instrument_id);

CREATE TABLE position
(
    id                 UUID          PRIMARY KEY,
    mode               TEXT          NOT NULL,
    instrument_id      UUID          NOT NULL,
    product            TEXT          NOT NULL,
    strategy_id        UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    net_quantity       INT           NOT NULL DEFAULT 0,
    average_price      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    realized_pnl_paise BIGINT        NOT NULL DEFAULT 0,
    day_buy_qty        INT           NOT NULL DEFAULT 0,
    day_sell_qty       INT           NOT NULL DEFAULT 0,
    opened_at          TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT position_key UNIQUE (mode, instrument_id, product, strategy_id)
);

CREATE TABLE idempotency_record
(
    client_id       UUID        NOT NULL,
    key             TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_id, key)
);
CREATE INDEX idempotency_record_created_idx ON idempotency_record (created_at);
