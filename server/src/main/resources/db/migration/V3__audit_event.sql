CREATE TABLE audit_event
(
    id              UUID        PRIMARY KEY,
    ts              TIMESTAMPTZ NOT NULL,
    type            TEXT        NOT NULL,
    actor_type      TEXT        NOT NULL,
    actor_id        TEXT,
    correlation_id  UUID        NOT NULL,
    strategy_id     UUID,
    signal_id       UUID,
    order_intent_id UUID,
    order_id        UUID,
    broker_ref      TEXT,
    client_source   TEXT,
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX audit_event_ts_idx ON audit_event (ts DESC);
CREATE INDEX audit_event_type_ts_idx ON audit_event (type, ts DESC);
CREATE INDEX audit_event_order_id_idx ON audit_event (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX audit_event_correlation_id_idx ON audit_event (correlation_id);

-- Audit rows are immutable: any UPDATE or DELETE fails.
CREATE FUNCTION audit_event_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_update_or_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();
