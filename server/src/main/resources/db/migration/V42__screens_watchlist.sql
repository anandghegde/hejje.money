-- Plan M8.7: saved screens (a screen is a stored screener request) and the watchlist.
CREATE TABLE screen (
    id          uuid         PRIMARY KEY,
    name        text         NOT NULL UNIQUE,
    definition  jsonb        NOT NULL,
    seeded      boolean      NOT NULL DEFAULT FALSE,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE watchlist_item (
    symbol         text         PRIMARY KEY,
    instrument_id  uuid         NOT NULL,
    note           text,
    added_at       timestamptz  NOT NULL DEFAULT now()
);

-- The M8.4 lists as screens (docs/ratings.md, "Screener").
INSERT INTO screen (id, name, definition, seeded) VALUES
(gen_random_uuid(), 'Leaders', '{"filters":[{"field":"techComposite","op":"gte","value":85},{"field":"rsRating","op":"gte","value":80},{"field":"avgTurnoverCr","op":"gte","value":10}],"sort":"-techComposite","limit":100}', TRUE),
(gen_random_uuid(), 'In buy zone', '{"filters":[{"field":"baseStatus","op":"eq","value":"IN_BUY_ZONE"}],"sort":"-techComposite","limit":100}', TRUE),
(gen_random_uuid(), 'Near pivot', '{"filters":[{"field":"baseStatus","op":"eq","value":"NEAR_PIVOT"}],"sort":"-techComposite","limit":100}', TRUE),
(gen_random_uuid(), 'On the move: up', '{"filters":[{"field":"changePct","op":"gte","value":2},{"field":"volVsAvg50Pct","op":"gte","value":50}],"sort":"-changePct","limit":100}', TRUE),
(gen_random_uuid(), 'On the move: down', '{"filters":[{"field":"changePct","op":"lte","value":-2},{"field":"volVsAvg50Pct","op":"gte","value":50}],"sort":"changePct","limit":100}', TRUE),
(gen_random_uuid(), 'Top groups', '{"filters":[{"field":"groupRank","op":"lte","value":5},{"field":"rsRating","op":"gte","value":70}],"sort":"groupRank","limit":100}', TRUE);

INSERT INTO notification_rule (id, event_type, channel, min_severity, enabled, updated_at, updated_by)
VALUES (gen_random_uuid(), 'DAILY_CONTEXT_DIGEST', 'IN_APP', 'INFO', TRUE, now(), 'seed');
