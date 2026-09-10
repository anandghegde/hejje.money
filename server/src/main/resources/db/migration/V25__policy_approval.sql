-- M4.4: approval policy rules (PRD 49) and approvals of agent proposals (PRD 27 Level 3).
CREATE TABLE policy_rule
(
    id          UUID        PRIMARY KEY,
    name        TEXT        NOT NULL UNIQUE,
    priority    INT         NOT NULL,
    condition   TEXT        NOT NULL,
    actions     JSONB       NOT NULL,   -- PolicyAction names; empty = every action
    decision    TEXT        NOT NULL,   -- ALLOW | REQUIRE_APPROVAL | DENY
    params      JSONB       NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    description TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  TEXT        NOT NULL
);

INSERT INTO policy_rule (id, name, priority, condition, actions, decision, params, enabled, description, updated_at, updated_by) VALUES
    (gen_random_uuid(), 'daily_loss_block', 10, 'DAILY_LOSS_EXCEEDED', '["ORDER_NEW"]', 'DENY', '{"lossLimitPct": 100}', TRUE,
     'Daily loss beyond the threshold blocks new exposure (closes stay allowed)', now(), 'seed'),
    (gen_random_uuid(), 'autonomy_above_phase', 20, 'AUTONOMY_ABOVE_PHASE', '[]', 'DENY', '{"maxLevel": 3}', TRUE,
     'Autonomy levels 4-5 arrive with AUTO mode (Phase 5)', now(), 'seed'),
    (gen_random_uuid(), 'agent_needs_prepare_level', 30, 'AUTONOMY_BELOW_PREPARE', '["ORDER_NEW"]', 'DENY', '{"minLevel": 2}', TRUE,
     'At autonomy level 0 (research) or 1 (recommend) agents may not prepare orders', now(), 'seed'),
    (gen_random_uuid(), 'event_risk_high', 40, 'EVENT_RISK_HIGH', '["ORDER_NEW"]', 'REQUIRE_APPROVAL', '{}', TRUE,
     'Event risk HIGH needs human confirmation', now(), 'seed'),
    (gen_random_uuid(), 'new_strategy_version', 50, 'NEW_STRATEGY_VERSION', '["ORDER_NEW"]', 'REQUIRE_APPROVAL', '{}', TRUE,
     'A strategy version that is not LIVE is never automatic', now(), 'seed'),
    (gen_random_uuid(), 'agent_actions', 60, 'ACTOR_AGENT', '[]', 'REQUIRE_APPROVAL', '{}', TRUE,
     'Agent-prepared actions need human confirmation (Automation Level 3)', now(), 'seed'),
    (gen_random_uuid(), 'manual_orders', 70, 'ACTOR_USER', '[]', 'REQUIRE_APPROVAL', '{}', TRUE,
     'Manual orders need human confirmation (the order form is the confirmation)', now(), 'seed'),
    (gen_random_uuid(), 'score_below_80', 80, 'SCORE_BELOW', '["ORDER_NEW"]', 'REQUIRE_APPROVAL', '{"threshold": 80}', TRUE,
     'Strategy signals scoring below 80 need human confirmation', now(), 'seed'),
    (gen_random_uuid(), 'strategy_signals', 90, 'ACTOR_STRATEGY', '["ORDER_NEW"]', 'REQUIRE_APPROVAL', '{}', TRUE,
     'Strategy signals scoring 80 or more need human confirmation; approved AUTO strategies arrive in Phase 5', now(), 'seed');

CREATE TABLE approval
(
    id                     UUID        PRIMARY KEY,
    kind                   TEXT        NOT NULL,   -- ORDER_NEW | ORDER_MODIFY | ORDER_CANCEL | POSITION_CLOSE
    status                 TEXT        NOT NULL,   -- PENDING | APPROVED | REJECTED | EXPIRED | FAILED
    mode                   TEXT        NOT NULL,
    intent_id              UUID,                   -- the PROPOSED order_intent (ORDER_NEW)
    signal_id              UUID,
    strategy_id            UUID,
    instrument_id          UUID,
    instrument             TEXT,
    order_id               UUID,                   -- the order acted on (modify/cancel)
    requested_by_session   UUID        NOT NULL REFERENCES agent_session (id),
    requested_by           TEXT        NOT NULL,
    requested_by_principal UUID        NOT NULL,
    requested_by_type      TEXT        NOT NULL,   -- USER | CLIENT
    request_key            TEXT,                   -- the requester's Idempotency-Key, when given
    summary                TEXT        NOT NULL,
    rationale              TEXT,
    proposal               JSONB       NOT NULL,
    risk                   JSONB,
    policy                 JSONB,
    created_at             TIMESTAMPTZ NOT NULL,
    expires_at             TIMESTAMPTZ NOT NULL,
    decided_by             TEXT,
    decided_at             TIMESTAMPTZ,
    decision_key           TEXT,                   -- the approver's Idempotency-Key
    decision_note          TEXT,
    result                 JSONB
);
CREATE INDEX approval_status_idx ON approval (status, created_at DESC);
CREATE UNIQUE INDEX approval_request_key_idx ON approval (requested_by_principal, request_key) WHERE request_key IS NOT NULL;
