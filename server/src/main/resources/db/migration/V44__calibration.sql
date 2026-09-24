-- Plan M9.2: probabilistic predictions (Jev answers, bot confidence) and their outcome labels (docs/calibration.md).
-- A row is written PENDING when the prediction is made and labelled once (HIT, MISS or NONE); a label never changes.
CREATE TABLE calibration_label (
    source         text              NOT NULL,   -- jev | bot
    source_id      text              NOT NULL,   -- jev_call id | bot_decision id
    key            text              NOT NULL,   -- question key | confidence
    horizon        text              NOT NULL,   -- 30m, 60m, 15m, next_close
    purpose        text              NOT NULL,
    version        text              NOT NULL,
    probability    double precision  NOT NULL,
    instrument_id  uuid              NOT NULL,
    rule           text              NOT NULL,   -- ENTRY_1R | DIRECTION | DIRECTION_NEXT_CLOSE | EXIT
    side           text              NOT NULL,
    stop           numeric(18,2),
    decided_at     timestamptz       NOT NULL,
    session_date   date              NOT NULL,
    outcome        text              NOT NULL,   -- PENDING | HIT | MISS | NONE
    label          smallint,                     -- 1 HIT, 0 MISS, null otherwise
    evidence       jsonb,
    labelled_at    timestamptz,
    PRIMARY KEY (source, source_id, key, horizon)
);

CREATE INDEX calibration_label_purpose ON calibration_label (purpose, version, session_date);
CREATE INDEX calibration_label_pending ON calibration_label (decided_at) WHERE outcome = 'PENDING';

-- M9.2 task 5: the bot's confidence calibration in the session (buckets, Brier, counts), kept with the report.
ALTER TABLE sim_report ADD COLUMN confidence_calibration jsonb;
