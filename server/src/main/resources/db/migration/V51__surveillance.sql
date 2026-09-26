-- NSE surveillance lists (ASM long/short term, GSM) per fetch day (docs/ratings.md, "Surveillance"). Display only.
-- One snapshot per session the lists were fetched for; a re-fetch the same day replaces it.
CREATE TABLE surveillance_snapshot (
    session_date  date         PRIMARY KEY,
    asm_date      date,                          -- the date NSE prints on the ASM report
    gsm_date      date,                          -- ... and on the GSM report
    fetched_at    timestamptz  NOT NULL
);

CREATE TABLE surveillance_flag (
    session_date  date  NOT NULL REFERENCES surveillance_snapshot (session_date) ON DELETE CASCADE,
    symbol        text  NOT NULL,                -- NSE:<symbol>
    flag          text  NOT NULL,                -- ASM_LT_<n>, ASM_ST_<n>, GSM_<n>
    code          text  NOT NULL,                -- NSE's code, e.g. 'LTASM - I (13)', 'IBC - Receipt & GSM 0 (62)'
    PRIMARY KEY (session_date, symbol)
);
