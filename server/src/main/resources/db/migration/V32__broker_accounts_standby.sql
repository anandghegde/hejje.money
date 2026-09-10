-- M5.6: broker accounts (one active transactional account at a time), the executor lease as the active/standby
-- ownership primitive (epoch = fencing token, incremented whenever ownership changes), broker on reconciliation issues.
CREATE TABLE broker_account
(
    id           UUID        PRIMARY KEY,
    broker       TEXT        NOT NULL,
    account_id   TEXT        NOT NULL,
    label        TEXT,
    active       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    activated_by TEXT,
    CONSTRAINT broker_account_unique UNIQUE (broker, account_id)
);
-- at most one active transactional account
CREATE UNIQUE INDEX broker_account_one_active ON broker_account (active) WHERE active;

ALTER TABLE executor_lease ADD COLUMN epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE executor_lease ADD COLUMN instance TEXT;

ALTER TABLE reconciliation_issue ADD COLUMN broker TEXT;
