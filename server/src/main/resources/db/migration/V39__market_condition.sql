-- Plan M8.3: the market condition as a seventh regime dimension. Existing rows stay UNKNOWN; bumping
-- hejje.regime.classifier-version relabels history with it.
ALTER TABLE market_regime ADD COLUMN market_condition text NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE market_regime_intraday ADD COLUMN market_condition text NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE market_pulse ADD COLUMN market_condition text NOT NULL DEFAULT 'Unknown';
ALTER TABLE market_pulse ADD COLUMN market_condition_evidence text;

INSERT INTO notification_rule (id, event_type, channel, min_severity, enabled, updated_at, updated_by)
VALUES (gen_random_uuid(), 'MARKET_CONDITION_CHANGED', 'IN_APP', 'INFO', TRUE, now(), 'seed');
