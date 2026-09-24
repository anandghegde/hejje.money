-- Plan M8.4: detected bases with their trade plan (immutable) and the status history (append-only). The status of a base
-- as of a session is its newest history row on or before that session, so reads can be capped at any date.
CREATE TABLE base (
    id              uuid            PRIMARY KEY,
    instrument_id   uuid            NOT NULL,
    symbol          text            NOT NULL,
    type            text            NOT NULL,
    engine_version  text            NOT NULL,
    start_date      date            NOT NULL,
    detected_date   date            NOT NULL,
    depth_pct       double precision NOT NULL,
    base_low        numeric(14, 2)  NOT NULL,
    pivot           numeric(14, 2)  NOT NULL,
    buy_low         numeric(14, 2)  NOT NULL,
    buy_high        numeric(14, 2)  NOT NULL,
    stop            numeric(14, 2)  NOT NULL,
    goal            numeric(14, 2)  NOT NULL,
    evidence        jsonb           NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT base_unique UNIQUE (instrument_id, type, start_date, engine_version)
);
CREATE INDEX base_symbol_idx ON base (symbol, engine_version, detected_date);
CREATE INDEX base_detected_idx ON base (engine_version, detected_date);

CREATE TABLE base_status_history (
    base_id           uuid            NOT NULL REFERENCES base (id) ON DELETE CASCADE,
    seq               int             NOT NULL,
    status            text            NOT NULL,
    status_date       date            NOT NULL,
    trigger_date      date,
    entry             numeric(14, 2),
    volume_confirmed  boolean,
    exit              numeric(14, 2),
    outcome_pct       double precision,
    outcome_r         double precision,
    PRIMARY KEY (base_id, seq)
);
CREATE INDEX base_status_history_date_idx ON base_status_history (status_date);

-- The last session the lifecycle and the detectors have processed per instrument: a re-run continues after it.
CREATE TABLE base_progress (
    instrument_id      uuid  NOT NULL,
    engine_version     text  NOT NULL,
    processed_through  date  NOT NULL,
    PRIMARY KEY (instrument_id, engine_version)
);

INSERT INTO notification_rule (id, event_type, channel, min_severity, enabled, updated_at, updated_by)
SELECT gen_random_uuid(), t.type, 'IN_APP', 'INFO', TRUE, now(), 'seed'
FROM (VALUES ('ENTERED_BUY_ZONE'), ('NEAR_PIVOT'), ('SETUP_STOPPED'), ('SETUP_HIT_GOAL')) AS t(type);
