-- Plan M9.1: every Jev call and its typed answers (docs/jev.md). Answers are kept for calibration (M9.2); the state
-- sent is kept for hejje.jev.state-retention-days for debugging and the SIM cache.
CREATE TABLE jev_call (
    id             uuid          PRIMARY KEY,
    at             timestamptz   NOT NULL,
    purpose        text          NOT NULL,
    subject        text,
    set_name       text          NOT NULL,
    set_version    text          NOT NULL,
    model          text,
    state_hash     text          NOT NULL,
    latency_ms     bigint        NOT NULL,
    input_tokens   integer,
    cost_paise     numeric(14,4),
    outcome        text          NOT NULL,
    error          text,
    correlation_id text
);

CREATE INDEX jev_call_at ON jev_call (at);
CREATE INDEX jev_call_purpose_at ON jev_call (purpose, at);
CREATE INDEX jev_call_cache ON jev_call (state_hash, set_name, set_version) WHERE outcome = 'OK';

CREATE TABLE jev_answer (
    call_id        uuid              NOT NULL REFERENCES jev_call (id) ON DELETE CASCADE,
    key            text              NOT NULL,
    type           text              NOT NULL,
    choice         text,
    score          double precision,
    noul           double precision,
    probabilities  jsonb,
    confidence     double precision,
    PRIMARY KEY (call_id, key)
);

CREATE TABLE jev_state (
    call_id  uuid   PRIMARY KEY REFERENCES jev_call (id) ON DELETE CASCADE,
    state    jsonb  NOT NULL
);
