-- M3.2: Technical and Market Pulse snapshots (PRD section 16).
CREATE TABLE market_pulse
(
    id             UUID             PRIMARY KEY,
    session_date   DATE             NOT NULL,
    as_of          TIMESTAMPTZ      NOT NULL,
    direction      TEXT             NOT NULL,
    strength       TEXT             NOT NULL,
    score          INT              NOT NULL,
    coverage       DOUBLE PRECISION NOT NULL,
    regime         TEXT             NOT NULL,
    volatility     TEXT             NOT NULL,
    breadth        TEXT             NOT NULL,
    sectors        JSONB            NOT NULL,
    global_context TEXT             NOT NULL,
    components     JSONB            NOT NULL,
    evidence       JSONB            NOT NULL
);
CREATE INDEX market_pulse_session_idx ON market_pulse (session_date, as_of);
