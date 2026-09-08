CREATE TABLE broker_session
(
    id               UUID        PRIMARY KEY,
    broker           TEXT        NOT NULL UNIQUE,
    broker_user_id   TEXT,
    access_token_enc TEXT,
    public_token     TEXT,
    established_at   TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    status           TEXT        NOT NULL,
    detail           TEXT,
    last_checked_at  TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL
);
