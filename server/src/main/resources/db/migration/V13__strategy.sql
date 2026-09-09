CREATE TABLE strategy
(
    id         UUID        PRIMARY KEY,
    slug       TEXT        NOT NULL UNIQUE,
    family     TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    retired_at TIMESTAMPTZ
);

CREATE TABLE strategy_version
(
    id                UUID        PRIMARY KEY,
    strategy_id       UUID        NOT NULL REFERENCES strategy (id),
    version           INT         NOT NULL,
    definition_yaml   TEXT        NOT NULL,
    definition_hash   TEXT        NOT NULL,
    change_note       TEXT        NOT NULL,
    parent_version_id UUID        REFERENCES strategy_version (id),
    created_by        TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    status            TEXT        NOT NULL,
    UNIQUE (strategy_id, version)
);
CREATE INDEX strategy_version_hash_idx ON strategy_version (strategy_id, definition_hash);

-- Versions are immutable: only the lifecycle status may change after insert, and rows are never deleted.
CREATE OR REPLACE FUNCTION strategy_version_immutable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'strategy_version rows are immutable (delete of %)', OLD.id;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.strategy_id IS DISTINCT FROM OLD.strategy_id
       OR NEW.version IS DISTINCT FROM OLD.version
       OR NEW.definition_yaml IS DISTINCT FROM OLD.definition_yaml
       OR NEW.definition_hash IS DISTINCT FROM OLD.definition_hash
       OR NEW.change_note IS DISTINCT FROM OLD.change_note
       OR NEW.parent_version_id IS DISTINCT FROM OLD.parent_version_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'strategy_version rows are immutable (update of %)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER strategy_version_immutable_trg
    BEFORE UPDATE OR DELETE ON strategy_version
    FOR EACH ROW EXECUTE FUNCTION strategy_version_immutable();

CREATE TABLE strategy_deployment
(
    id             UUID        PRIMARY KEY,
    version_id     UUID        NOT NULL REFERENCES strategy_version (id),
    mode           TEXT        NOT NULL,
    instrument_ids JSONB       NOT NULL,
    autonomy_level INT         NOT NULL,
    enabled        BOOLEAN     NOT NULL,
    params         JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    paused_at      TIMESTAMPTZ,
    pause_reason   TEXT
);
CREATE INDEX strategy_deployment_version_idx ON strategy_deployment (version_id);
