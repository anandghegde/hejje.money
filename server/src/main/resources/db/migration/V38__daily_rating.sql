-- Plan M8.2: daily price/volume ratings per session, instrument and engine version. Rows are written once; a rule or
-- threshold change bumps the engine version and writes new rows. symbol is denormalised so lists need no join.
CREATE TABLE daily_rating (
    session_date       date             NOT NULL,
    instrument_id      uuid             NOT NULL,
    engine_version     text             NOT NULL,
    symbol             text             NOT NULL,
    rs_raw             double precision,
    rs_rating          smallint,
    ad_raw             double precision,
    ad_grade           text,
    off_high_pct       double precision,
    off_low_pct        double precision,
    vol_vs_avg50_pct   double precision,
    up_down_vol_ratio  double precision,
    avg_turnover_cr    double precision,
    close              numeric(14, 2)   NOT NULL,
    change_pct         double precision,
    group_id           text,
    group_rank         smallint,
    tech_composite     smallint,
    evidence           jsonb            NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (session_date, instrument_id, engine_version)
);
CREATE INDEX daily_rating_symbol_idx ON daily_rating (symbol, engine_version, session_date);

CREATE TABLE industry_group_rank (
    session_date    date             NOT NULL,
    group_id        text             NOT NULL,
    engine_version  text             NOT NULL,
    name            text             NOT NULL,
    rank            smallint         NOT NULL,
    strength        double precision NOT NULL,
    members         smallint         NOT NULL,
    PRIMARY KEY (session_date, group_id, engine_version)
);
