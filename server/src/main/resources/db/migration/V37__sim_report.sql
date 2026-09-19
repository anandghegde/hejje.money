-- M7.5 one report per (finished SIM session, bot): what the bot did and how it scored, kept after the next session
-- clears the simulated ledger. Keyed by the bot's name and version (ids differ between the SIM and live databases), so
-- reports exported from a SIM instance can be imported where the bot is promoted.
CREATE TABLE sim_report
(
    id             UUID           PRIMARY KEY,
    session_id     UUID           NOT NULL,
    bot_name       TEXT           NOT NULL,
    bot_version    TEXT           NOT NULL,
    bot_kind       TEXT           NOT NULL,
    session_dates  JSONB          NOT NULL,
    capital_paise  BIGINT         NOT NULL,
    trades         INT            NOT NULL,
    wins           INT            NOT NULL,
    expectancy_r   DOUBLE PRECISION,
    profit_factor  DOUBLE PRECISION,
    max_drawdown_paise BIGINT     NOT NULL,
    net_pnl_paise  BIGINT         NOT NULL,
    win_paise      BIGINT         NOT NULL,   -- sum of winning trades' net P&L
    loss_paise     BIGINT         NOT NULL,   -- sum of losing trades' net P&L (negative or zero)
    friction_paise BIGINT         NOT NULL,
    trade_rs       JSONB          NOT NULL,   -- every trade's R net of costs, for aggregation
    decisions_hash TEXT           NOT NULL,
    result_hash    TEXT,
    snapshot       JSONB          NOT NULL,   -- the harness snapshot at the end (equity, trades, decisions, costs)
    created_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT sim_report_session_bot UNIQUE (session_id, bot_name)
);
CREATE INDEX sim_report_bot_idx ON sim_report (bot_name, bot_version);
