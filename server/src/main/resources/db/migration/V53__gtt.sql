-- Plan M11.2: the broker-side stop (GTT) of each delivery position. One ACTIVE (or MISSING) row per position.
CREATE TABLE gtt
(
    id                 uuid           PRIMARY KEY,
    mode               text           NOT NULL,
    broker             text           NOT NULL,
    broker_gtt_id      text           NOT NULL,
    position_id        uuid           NOT NULL,
    instrument_id      uuid           NOT NULL,
    quantity           int            NOT NULL,
    stop_trigger       numeric(18, 2) NOT NULL,
    goal_trigger       numeric(18, 2),
    status             text           NOT NULL,   -- ACTIVE | MISSING | TRIGGERED | CANCELLED
    triggered_order_id text,
    created_at         timestamptz    NOT NULL,
    updated_at         timestamptz    NOT NULL,
    confirmed_at       timestamptz               -- last time the broker listed it as active
);
CREATE UNIQUE INDEX gtt_live_idx ON gtt (position_id) WHERE status IN ('ACTIVE', 'MISSING');
CREATE INDEX gtt_mode_idx ON gtt (mode, status);
CREATE INDEX gtt_broker_idx ON gtt (broker, broker_gtt_id);

-- A GTT incident is notified in the app (docs/notifications.md).
INSERT INTO notification_rule (id, event_type, channel, min_severity, enabled, updated_at, updated_by)
SELECT gen_random_uuid(), t.type, 'IN_APP', 'INFO', TRUE, now(), 'seed'
FROM (VALUES ('GTT_MISSING'), ('GTT_MISMATCH')) AS t(type);

-- Trailing is optional per position (a swing deployment's setting, M11.4): breakeven at +1R, then under the 20-day low.
ALTER TABLE swing_position ADD COLUMN trail boolean NOT NULL DEFAULT false;
