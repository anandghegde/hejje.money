-- M3.4: LLM call log (prompts hashed, never stored) and the news pipeline.
CREATE TABLE llm_call
(
    id                  UUID        PRIMARY KEY,
    at                  TIMESTAMPTZ NOT NULL,
    profile             TEXT        NOT NULL,
    provider            TEXT        NOT NULL,
    model               TEXT,
    purpose             TEXT        NOT NULL,
    prompt_version      TEXT        NOT NULL,
    prompt_hash         TEXT        NOT NULL,
    input_tokens        INT,
    output_tokens       INT,
    cost_estimate_paise BIGINT,
    latency_ms          BIGINT      NOT NULL,
    correlation_id      TEXT,
    status              TEXT        NOT NULL,
    error               TEXT
);
CREATE INDEX llm_call_at_idx ON llm_call (at DESC);

CREATE TABLE news_source
(
    id          UUID             PRIMARY KEY,
    name        TEXT             NOT NULL,
    url         TEXT             NOT NULL UNIQUE,
    kind        TEXT             NOT NULL,
    reliability DOUBLE PRECISION NOT NULL,
    enabled     BOOLEAN          NOT NULL,
    last_polled_at TIMESTAMPTZ,
    last_error  TEXT
);

CREATE TABLE news_item
(
    id           UUID        PRIMARY KEY,
    source_id    UUID        NOT NULL REFERENCES news_source (id),
    url          TEXT        NOT NULL UNIQUE,
    title        TEXT        NOT NULL,
    summary      TEXT,
    body         TEXT,
    published_at TIMESTAMPTZ NOT NULL,
    fetched_at   TIMESTAMPTZ NOT NULL,
    hash         TEXT        NOT NULL UNIQUE,
    norm_title   TEXT        NOT NULL
);
CREATE INDEX news_item_published_idx ON news_item (published_at DESC);

CREATE TABLE news_assessment
(
    id             UUID             PRIMARY KEY,
    item_id        UUID             NOT NULL REFERENCES news_item (id),
    instrument_id  UUID             REFERENCES instrument (id),
    sector         TEXT,
    relevance      DOUBLE PRECISION NOT NULL,
    direction      DOUBLE PRECISION NOT NULL,
    materiality    DOUBLE PRECISION NOT NULL,
    novelty        DOUBLE PRECISION NOT NULL,
    confidence     DOUBLE PRECISION NOT NULL,
    event_type     TEXT,
    summary        TEXT,
    model          TEXT             NOT NULL,
    prompt_version TEXT             NOT NULL,
    created_at     TIMESTAMPTZ      NOT NULL
);
CREATE INDEX news_assessment_instrument_idx ON news_assessment (instrument_id, created_at DESC);
CREATE INDEX news_assessment_item_idx ON news_assessment (item_id);

CREATE TABLE news_bias
(
    id            UUID             PRIMARY KEY,
    instrument_id UUID             NOT NULL REFERENCES instrument (id),
    computed_at   TIMESTAMPTZ      NOT NULL,
    score         DOUBLE PRECISION NOT NULL,
    label         TEXT             NOT NULL,
    items         INT              NOT NULL,
    evidence      JSONB            NOT NULL
);
CREATE INDEX news_bias_instrument_idx ON news_bias (instrument_id, computed_at DESC);
