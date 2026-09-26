-- Plan M11.6: the swing book's notifications, seeded with in-app rules (docs/notifications.md).
INSERT INTO notification_rule (id, event_type, channel, min_severity, enabled, updated_at, updated_by)
SELECT gen_random_uuid(), t.type, 'IN_APP', 'INFO', TRUE, now(), 'seed'
FROM (VALUES ('GTT_PLACED'), ('SWING_ENTRY_FILLED'), ('SWING_STOP_HIT'), ('SWING_GOAL_HIT'), ('SWING_TIME_EXIT_DUE')) AS t(type);
