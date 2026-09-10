-- M5.3 smart, basket and split orders. Basket legs and split children are normal orders (hejje_order) placed through
-- the execution pipeline; these tables hold the grouping, policy and progress.
CREATE TABLE basket
(
    id                     UUID        PRIMARY KEY,
    mode                   TEXT        NOT NULL,
    name                   TEXT,
    client_id              UUID        NOT NULL,
    idempotency_key        TEXT        NOT NULL,
    source                 TEXT        NOT NULL,
    actor_id               TEXT,
    strategy_id            UUID,
    reason                 TEXT        NOT NULL,
    policy                 TEXT        NOT NULL,   -- ALL_OR_NOTHING | BEST_EFFORT
    rollback               TEXT        NOT NULL,   -- NONE | CLOSE_FILLED_LEGS
    deadline               TIMESTAMPTZ NOT NULL,
    status                 TEXT        NOT NULL,
    margin_required_paise  BIGINT,
    margin_available_paise BIGINT,
    detail                 TEXT,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    CONSTRAINT basket_idempotency UNIQUE (client_id, idempotency_key)
);
CREATE INDEX basket_created_idx ON basket (mode, created_at DESC);

CREATE TABLE basket_leg
(
    id                UUID           PRIMARY KEY,
    basket_id         UUID           NOT NULL REFERENCES basket (id),
    sequence          INT            NOT NULL,
    hedge_first       BOOLEAN        NOT NULL,
    execution_order   INT,                       -- position in which the leg was actually placed (hedges first)
    instrument_id     UUID           NOT NULL,
    side              TEXT           NOT NULL,
    quantity          INT            NOT NULL,
    order_type        TEXT           NOT NULL,
    product           TEXT           NOT NULL,
    limit_price       NUMERIC(18, 2),
    trigger_price     NUMERIC(18, 2),
    stop_price        NUMERIC(18, 2),
    target_price      NUMERIC(18, 2),
    order_id          UUID,
    status            TEXT           NOT NULL,
    detail            TEXT,
    rollback_order_id UUID,
    CONSTRAINT basket_leg_sequence UNIQUE (basket_id, sequence)
);

CREATE TABLE split_order
(
    id              UUID           PRIMARY KEY,
    mode            TEXT           NOT NULL,
    client_id       UUID           NOT NULL,
    idempotency_key TEXT           NOT NULL,
    source          TEXT           NOT NULL,
    actor_id        TEXT,
    strategy_id     UUID,
    instrument_id   UUID           NOT NULL,
    side            TEXT           NOT NULL,
    quantity        INT            NOT NULL,
    order_type      TEXT           NOT NULL,
    product         TEXT           NOT NULL,
    limit_price     NUMERIC(18, 2),
    trigger_price   NUMERIC(18, 2),
    stop_price      NUMERIC(18, 2),
    target_price    NUMERIC(18, 2),
    reason          TEXT           NOT NULL,
    policy          JSONB          NOT NULL,
    status          TEXT           NOT NULL,
    filled_quantity INT            NOT NULL DEFAULT 0,
    children        INT            NOT NULL DEFAULT 0,
    reference_price NUMERIC(18, 2),
    deadline        TIMESTAMPTZ    NOT NULL,
    detail          TEXT,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    CONSTRAINT split_order_idempotency UNIQUE (client_id, idempotency_key)
);
CREATE INDEX split_order_created_idx ON split_order (mode, created_at DESC);
CREATE INDEX hejje_order_parent_idx ON hejje_order (parent_order_id) WHERE parent_order_id IS NOT NULL;
