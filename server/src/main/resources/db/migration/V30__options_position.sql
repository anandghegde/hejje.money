-- M5.4 multi-leg options positions: one per executed options signal; the legs are placed as a basket and managed here.
CREATE TABLE options_position
(
    id                       UUID           PRIMARY KEY,
    mode                     TEXT           NOT NULL,
    client_id                UUID           NOT NULL,
    idempotency_key          TEXT           NOT NULL,
    source                   TEXT           NOT NULL,
    actor_id                 TEXT,
    strategy_id              UUID,
    version_id               UUID,
    deployment_id            UUID,
    signal_id                UUID,
    underlying               TEXT           NOT NULL,
    underlying_instrument_id UUID           NOT NULL,
    direction                TEXT           NOT NULL,
    underlying_stop          NUMERIC(18, 2),
    basket_id                UUID           NOT NULL,
    status                   TEXT           NOT NULL,   -- PENDING | OPEN | CLOSING | CLOSED | FAILED
    legs                     JSONB          NOT NULL,
    combined_stop_paise      BIGINT,
    combined_target_paise    BIGINT,
    force_exit_time          TEXT           NOT NULL,
    product                  TEXT           NOT NULL,
    close_reason             TEXT,
    realized_paise           BIGINT,
    detail                   TEXT,
    opened_at                TIMESTAMPTZ    NOT NULL,
    closed_at                TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ    NOT NULL,
    CONSTRAINT options_position_idempotency UNIQUE (client_id, idempotency_key)
);
CREATE INDEX options_position_active_idx ON options_position (mode, status) WHERE status IN ('PENDING', 'OPEN', 'CLOSING');
CREATE INDEX options_position_version_idx ON options_position (version_id, status);
