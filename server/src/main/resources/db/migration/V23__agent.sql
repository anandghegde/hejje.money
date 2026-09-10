-- M4.2: agent sessions and every tool call made in them.
CREATE TABLE agent_session
(
    id                   UUID        PRIMARY KEY,
    client_credential_id UUID,                      -- the API key (null for the local user's JWT)
    principal_type       TEXT        NOT NULL,      -- USER | CLIENT
    principal_id         UUID        NOT NULL,
    principal_name       TEXT        NOT NULL,
    profile              TEXT,                      -- LLM profile when the session is a Hejje AI conversation
    purpose              TEXT        NOT NULL,      -- direct | mcp | chat | ...
    started_at           TIMESTAMPTZ NOT NULL,
    ended_at             TIMESTAMPTZ
);
CREATE INDEX agent_session_principal_idx ON agent_session (principal_id, purpose, started_at DESC);
CREATE INDEX agent_session_started_idx ON agent_session (started_at DESC);

CREATE TABLE agent_action
(
    id             UUID        PRIMARY KEY,
    session_id     UUID        NOT NULL REFERENCES agent_session (id),
    tool           TEXT        NOT NULL,
    input          JSONB       NOT NULL,
    output_summary JSONB,
    scope_ok       BOOLEAN     NOT NULL,
    status         TEXT        NOT NULL,
    error          TEXT,
    latency_ms     BIGINT      NOT NULL,
    correlation_id TEXT,
    ts             TIMESTAMPTZ NOT NULL
);
CREATE INDEX agent_action_session_idx ON agent_action (session_id, ts);
