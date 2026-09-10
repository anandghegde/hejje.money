-- M5.2 AUTO mode: autonomy levels 4-5 are decided by explicit rules instead of being denied outright.
DELETE FROM policy_rule WHERE name = 'autonomy_above_phase';

INSERT INTO policy_rule (id, name, priority, condition, actions, decision, params, enabled, description, updated_at, updated_by) VALUES
    (gen_random_uuid(), 'deployment_budget', 15, 'DEPLOYMENT_BUDGET_EXCEEDED', '["ORDER_NEW"]', 'DENY', '{}', TRUE,
     'A deployment''s daily budget (max trades, max realized loss) blocks its new entries', now(), 'seed'),
    (gen_random_uuid(), 'auto_strategy', 85, 'AUTO_ELIGIBLE', '["ORDER_NEW"]', 'ALLOW', '{"minLevel": 4}', TRUE,
     'Approved AUTO strategy: a scored signal of a qualified deployment at autonomy 4-5 executes automatically', now(), 'seed');

UPDATE policy_rule SET description = 'A version not yet promoted for this mode (LIVE for live trading) is never automatic'
WHERE name = 'new_strategy_version';
UPDATE policy_rule SET description = 'Strategy signals need human confirmation unless auto_strategy allowed them'
WHERE name = 'strategy_signals';

-- approvals for strategy signals held by the policy have no agent session
ALTER TABLE approval ALTER COLUMN requested_by_session DROP NOT NULL;
