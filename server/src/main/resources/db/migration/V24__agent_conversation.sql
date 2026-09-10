-- M4.3: Hejje AI conversations. Each conversation runs in one agent_session (purpose chat); tool calls stay in agent_action.
CREATE TABLE agent_conversation
(
    id           UUID        PRIMARY KEY,
    session_id   UUID        NOT NULL REFERENCES agent_session (id),
    principal_id UUID        NOT NULL,
    title        TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX agent_conversation_principal_idx ON agent_conversation (principal_id, updated_at DESC);

CREATE TABLE agent_message
(
    id              UUID        PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES agent_conversation (id),
    seq             INT         NOT NULL,
    role            TEXT        NOT NULL,   -- USER | ASSISTANT
    content         TEXT        NOT NULL,
    flow            TEXT,
    profile         TEXT,
    grounding       JSONB,
    trace           JSONB,
    steps           INT,
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (conversation_id, seq)
);
