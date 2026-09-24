-- Plan M9.7: pace entries after a bad start instead of stopping outright (opt-in per mode), and optionally no trades-per-day
-- limit while the day is green. Defaults keep today's behaviour (BLOCK, LIMIT).
ALTER TABLE risk_limits ADD COLUMN loss_streak_mode          text    NOT NULL DEFAULT 'BLOCK';
ALTER TABLE risk_limits ADD COLUMN allowance_drawdown_paise  bigint  NOT NULL DEFAULT 50000;
ALTER TABLE risk_limits ADD COLUMN loss_streak_allowance     int     NOT NULL DEFAULT 4;
ALTER TABLE risk_limits ADD COLUMN trades_per_day_when_green text    NOT NULL DEFAULT 'LIMIT';
