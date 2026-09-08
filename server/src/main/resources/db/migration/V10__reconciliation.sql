CREATE TABLE executor_lease
(
    id          INT         PRIMARY KEY DEFAULT 1,
    owner       TEXT        NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT executor_lease_singleton CHECK (id = 1)
);

CREATE TABLE reconciliation_issue
(
    id            UUID        PRIMARY KEY,
    kind          TEXT        NOT NULL,
    severity      TEXT        NOT NULL,
    instrument_id UUID,
    order_id      UUID,
    expected      TEXT,
    observed      TEXT,
    detail        TEXT,
    detected_at   TIMESTAMPTZ NOT NULL,
    resolved_at   TIMESTAMPTZ
);
CREATE INDEX reconciliation_issue_open_idx ON reconciliation_issue (severity) WHERE resolved_at IS NULL;
