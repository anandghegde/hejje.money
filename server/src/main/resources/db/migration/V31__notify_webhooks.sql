-- M5.5 notifications (inbox, rules, delivery log) and external webhooks.
CREATE TABLE notification
(
    id         UUID        PRIMARY KEY,
    type       TEXT        NOT NULL,
    severity   TEXT        NOT NULL,
    title      TEXT        NOT NULL,
    body       TEXT        NOT NULL,
    data       JSONB       NOT NULL,
    dedupe_key TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    read_at    TIMESTAMPTZ
);
CREATE INDEX notification_created_idx ON notification (created_at DESC);
CREATE INDEX notification_dedupe_idx ON notification (dedupe_key, created_at DESC) WHERE dedupe_key IS NOT NULL;

CREATE TABLE notification_rule
(
    id           UUID        PRIMARY KEY,
    event_type   TEXT        NOT NULL,
    channel      TEXT        NOT NULL,   -- IN_APP | EMAIL | TELEGRAM
    min_severity TEXT        NOT NULL,   -- INFO | WARNING | CRITICAL
    enabled      BOOLEAN     NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    updated_by   TEXT        NOT NULL,
    CONSTRAINT notification_rule_unique UNIQUE (event_type, channel)
);

-- defaults (PRD 58): everything in-app; the important ones also by email and Telegram
INSERT INTO notification_rule (id, event_type, channel, min_severity, enabled, updated_at, updated_by)
SELECT gen_random_uuid(), t.type, 'IN_APP', 'INFO', TRUE, now(), 'seed'
FROM (VALUES ('SIGNAL_GENERATED'), ('HIGH_SCORE_SETUP'), ('ORDER_REJECTED'), ('STOP_TRIGGERED'), ('POSITION_CLOSED'), ('DAILY_RISK_THRESHOLD'),
             ('KILL_SWITCH'), ('BROKER_DISCONNECTED'), ('SERVER_UNHEALTHY'), ('STATIC_IP_MISMATCH'), ('STRATEGY_DRIFT'), ('MAJOR_EVENT_APPROACHING'),
             ('NEWS_CONTEXT_CHANGED'), ('APPROVAL_REQUESTED'), ('LLM_BUDGET_EXCEEDED'), ('TEST')) AS t(type);
INSERT INTO notification_rule (id, event_type, channel, min_severity, enabled, updated_at, updated_by)
SELECT gen_random_uuid(), t.type, c.channel, 'INFO', TRUE, now(), 'seed'
FROM (VALUES ('HIGH_SCORE_SETUP'), ('ORDER_REJECTED'), ('STOP_TRIGGERED'), ('DAILY_RISK_THRESHOLD'), ('KILL_SWITCH'), ('BROKER_DISCONNECTED'),
             ('SERVER_UNHEALTHY'), ('STATIC_IP_MISMATCH'), ('STRATEGY_DRIFT'), ('MAJOR_EVENT_APPROACHING'), ('APPROVAL_REQUESTED'),
             ('LLM_BUDGET_EXCEEDED'), ('TEST')) AS t(type)
CROSS JOIN (VALUES ('EMAIL'), ('TELEGRAM')) AS c(channel);

CREATE TABLE notification_delivery
(
    id              UUID        PRIMARY KEY,
    notification_id UUID        NOT NULL REFERENCES notification (id),
    channel         TEXT        NOT NULL,
    status          TEXT        NOT NULL,   -- QUEUED | SENT | FAILED | SKIPPED | DIGESTED | DIGEST_SENT
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    sent_at         TIMESTAMPTZ
);
CREATE INDEX notification_delivery_channel_idx ON notification_delivery (channel, status, sent_at DESC);
CREATE INDEX notification_delivery_notification_idx ON notification_delivery (notification_id);

CREATE TABLE webhook
(
    id                  UUID        PRIMARY KEY,
    name                TEXT        NOT NULL UNIQUE,
    secret_enc          TEXT        NOT NULL,   -- AES-GCM ciphertext (HEJJE_ENCRYPTION_KEY)
    auth_mode           TEXT        NOT NULL,   -- HMAC | PASSPHRASE
    strategy_version_id UUID        REFERENCES strategy_version (id),   -- null: MANUAL_EXTERNAL
    enabled             BOOLEAN     NOT NULL,
    allowed_instruments JSONB       NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    created_by          TEXT        NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    last_received_at    TIMESTAMPTZ
);

CREATE TABLE webhook_delivery
(
    id          UUID        PRIMARY KEY,
    webhook_id  UUID        NOT NULL REFERENCES webhook (id),
    received_at TIMESTAMPTZ NOT NULL,
    replay_key  TEXT,
    status      TEXT        NOT NULL,   -- ACCEPTED | REJECTED | REPLAYED
    detail      TEXT,
    signal_id   UUID,
    approval_id UUID,
    payload     JSONB
);
CREATE INDEX webhook_delivery_webhook_idx ON webhook_delivery (webhook_id, received_at DESC);
CREATE UNIQUE INDEX webhook_delivery_replay_idx ON webhook_delivery (webhook_id, replay_key) WHERE status = 'ACCEPTED';
