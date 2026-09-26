-- Plan M11.3: the swing book's own limits, one row per execution mode, editable like risk_limits.
CREATE TABLE swing_limits
(
    mode                        text         PRIMARY KEY,
    swing_capital_paise         bigint       NOT NULL,
    max_open_positions          int          NOT NULL,
    max_risk_per_position_paise bigint       NOT NULL,
    gap_allowance_pct           numeric(6, 2) NOT NULL,
    max_overnight_risk_paise    bigint       NOT NULL,
    max_positions_per_industry  int          NOT NULL,
    block_before_events         boolean      NOT NULL,
    block_surveillance          boolean      NOT NULL,
    updated_at                  timestamptz  NOT NULL,
    updated_by                  text
);

INSERT INTO swing_limits (mode, swing_capital_paise, max_open_positions, max_risk_per_position_paise, gap_allowance_pct, max_overnight_risk_paise,
    max_positions_per_industry, block_before_events, block_surveillance, updated_at, updated_by)
SELECT m, 50000000,   -- swing capital: 5,00,000 rupees
       6,             -- open swing positions
       250000,        -- risk per position (stop distance + gap allowance): 2,500 rupees
       3.00,          -- gap allowance, % of the price
       1000000,       -- total overnight risk: 10,000 rupees
       2,             -- positions per industry
       TRUE, TRUE, now(), 'seed'
FROM (VALUES ('PAPER'), ('CONFIRM'), ('AUTO'), ('SIM')) AS modes(m);
