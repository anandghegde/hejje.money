-- Plan M8.5 / M8.6: analog summaries (kept forever, written once per key) and their matches (pruned after
-- hejje.analogs.match-retention-days). checkpoint is '' for DAILY and HH:mm for SESSION.
CREATE TABLE analog_summary (
    session_date    date   NOT NULL,
    instrument_id   uuid   NOT NULL,
    kind            text   NOT NULL,
    lookback        int    NOT NULL,
    checkpoint      text   NOT NULL DEFAULT '',
    engine_version  text   NOT NULL,
    symbol          text   NOT NULL,
    summary         jsonb  NOT NULL,
    PRIMARY KEY (session_date, instrument_id, kind, lookback, checkpoint, engine_version)
);
CREATE INDEX analog_summary_symbol_idx ON analog_summary (symbol, kind, engine_version, session_date);

CREATE TABLE analog_match (
    session_date    date   NOT NULL,
    instrument_id   uuid   NOT NULL,
    kind            text   NOT NULL,
    lookback        int    NOT NULL,
    checkpoint      text   NOT NULL DEFAULT '',
    engine_version  text   NOT NULL,
    matches         jsonb  NOT NULL,
    PRIMARY KEY (session_date, instrument_id, kind, lookback, checkpoint, engine_version)
);
CREATE INDEX analog_match_date_idx ON analog_match (session_date);
