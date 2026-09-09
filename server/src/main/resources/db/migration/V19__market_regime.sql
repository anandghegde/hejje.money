-- M3.1: market regime labels (PRD section 13). One final row per session and classifier version; intraday snapshots kept separately.
CREATE TABLE market_regime
(
    session_date       DATE        NOT NULL,
    classifier_version TEXT        NOT NULL,
    as_of              TIMESTAMPTZ NOT NULL,
    trend              TEXT        NOT NULL,
    volatility         TEXT        NOT NULL,
    opening            TEXT        NOT NULL,
    breadth            TEXT        NOT NULL,
    intraday_structure TEXT        NOT NULL,
    event_environment  TEXT        NOT NULL,
    features           JSONB       NOT NULL,
    evidence           JSONB       NOT NULL,
    computed_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_date, classifier_version)
);

CREATE TABLE market_regime_intraday
(
    id                 UUID        PRIMARY KEY,
    session_date       DATE        NOT NULL,
    classifier_version TEXT        NOT NULL,
    as_of              TIMESTAMPTZ NOT NULL,
    trend              TEXT        NOT NULL,
    volatility         TEXT        NOT NULL,
    opening            TEXT        NOT NULL,
    breadth            TEXT        NOT NULL,
    intraday_structure TEXT        NOT NULL,
    event_environment  TEXT        NOT NULL,
    features           JSONB       NOT NULL,
    evidence           JSONB       NOT NULL,
    computed_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX market_regime_intraday_session_idx ON market_regime_intraday (session_date, classifier_version, as_of);
