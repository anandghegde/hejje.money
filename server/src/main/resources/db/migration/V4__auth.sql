CREATE TABLE app_user
(
    id            UUID        PRIMARY KEY,
    username      TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE refresh_token
(
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_user (id),
    token_hash TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE TABLE client_credential
(
    id           UUID        PRIMARY KEY,
    name         TEXT        NOT NULL,
    key_prefix   TEXT        NOT NULL UNIQUE,
    secret_hash  TEXT        NOT NULL,
    scopes       TEXT[]      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ
);
