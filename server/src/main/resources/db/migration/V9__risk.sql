CREATE TABLE risk_limits
(
    mode                             TEXT          PRIMARY KEY,
    max_loss_per_day_paise           BIGINT        NOT NULL,
    max_realized_loss_paise          BIGINT        NOT NULL,
    max_total_loss_paise             BIGINT        NOT NULL,
    max_capital_deployed_paise       BIGINT        NOT NULL,
    max_margin_utilization_pct       NUMERIC(6, 2) NOT NULL,
    max_open_positions               INT           NOT NULL,
    max_gross_exposure_paise         BIGINT        NOT NULL,
    max_trades_per_day               INT           NOT NULL,
    max_risk_per_trade_paise         BIGINT        NOT NULL,
    max_quantity                     INT           NOT NULL,
    max_notional_paise               BIGINT        NOT NULL,
    min_reward_risk                  NUMERIC(6, 2) NOT NULL,
    mandatory_stop                   BOOLEAN       NOT NULL,
    max_stop_distance_pct            NUMERIC(6, 2) NOT NULL,
    no_new_trades_after              TIME          NOT NULL,
    no_averaging_down                BOOLEAN       NOT NULL,
    no_reentry_minutes               INT           NOT NULL,
    max_consecutive_losses           INT           NOT NULL,
    updated_at                       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Sensible defaults for a personal intraday account (rupees shown in comments).
INSERT INTO risk_limits (mode, max_loss_per_day_paise, max_realized_loss_paise, max_total_loss_paise, max_capital_deployed_paise,
    max_margin_utilization_pct, max_open_positions, max_gross_exposure_paise, max_trades_per_day, max_risk_per_trade_paise,
    max_quantity, max_notional_paise, min_reward_risk, mandatory_stop, max_stop_distance_pct, no_new_trades_after,
    no_averaging_down, no_reentry_minutes, max_consecutive_losses)
SELECT m,
    500000,        -- max loss/day  5,000
    500000,        -- max realized loss 5,000
    750000,        -- max total loss incl unrealized 7,500
    50000000,      -- max capital deployed 5,00,000
    80.00,         -- max margin utilization %
    5,             -- max open positions
    100000000,     -- max gross exposure 10,00,000
    20,            -- max trades/day
    200000,        -- max risk/trade 2,000
    1000,          -- max quantity
    50000000,      -- max notional 5,00,000
    1.00,          -- min reward:risk
    TRUE,          -- mandatory stop
    5.00,          -- max stop distance %
    '14:45',       -- no new trades after
    TRUE,          -- no averaging down
    10,            -- no re-entry minutes
    3              -- max consecutive losses
FROM (VALUES ('PAPER'), ('CONFIRM'), ('AUTO')) AS modes(m);

CREATE TABLE kill_switch
(
    mode            TEXT        PRIMARY KEY,
    stop_new_orders BOOLEAN     NOT NULL DEFAULT FALSE,
    set_at          TIMESTAMPTZ,
    set_by          TEXT,
    reason          TEXT
);
INSERT INTO kill_switch (mode, stop_new_orders) VALUES ('PAPER', FALSE), ('CONFIRM', FALSE), ('AUTO', FALSE);
