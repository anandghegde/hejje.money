-- M3.3: event calendar (PRD section 18). One row per event; sources upsert by (source, external_key).
CREATE TABLE market_event
(
    id            UUID             PRIMARY KEY,
    type          TEXT             NOT NULL,
    scope         TEXT             NOT NULL,
    instrument_id UUID             REFERENCES instrument (id),
    symbol        TEXT,
    title         TEXT             NOT NULL,
    starts_at     TIMESTAMPTZ      NOT NULL,
    ends_at       TIMESTAMPTZ,
    all_day       BOOLEAN          NOT NULL,
    source        TEXT             NOT NULL,
    external_key  TEXT             NOT NULL,
    confidence    DOUBLE PRECISION NOT NULL,
    raw           JSONB            NOT NULL,
    imported_at   TIMESTAMPTZ      NOT NULL,
    UNIQUE (source, external_key)
);
CREATE INDEX market_event_time_idx ON market_event (starts_at);
CREATE INDEX market_event_instrument_idx ON market_event (instrument_id, starts_at);
