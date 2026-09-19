-- M7.1 SIM mode: a replay instance keeps its own risk limits and kill switch like every other mode (the risk pipeline
-- stays on in SIM). Starts from the PAPER limits as they are when the migration runs.
INSERT INTO risk_limits (mode, max_loss_per_day_paise, max_realized_loss_paise, max_total_loss_paise, max_capital_deployed_paise,
    max_margin_utilization_pct, max_open_positions, max_gross_exposure_paise, max_trades_per_day, max_risk_per_trade_paise,
    max_quantity, max_notional_paise, min_reward_risk, mandatory_stop, max_stop_distance_pct, no_new_trades_after,
    no_averaging_down, no_reentry_minutes, max_consecutive_losses)
SELECT 'SIM', max_loss_per_day_paise, max_realized_loss_paise, max_total_loss_paise, max_capital_deployed_paise,
    max_margin_utilization_pct, max_open_positions, max_gross_exposure_paise, max_trades_per_day, max_risk_per_trade_paise,
    max_quantity, max_notional_paise, min_reward_risk, mandatory_stop, max_stop_distance_pct, no_new_trades_after,
    no_averaging_down, no_reentry_minutes, max_consecutive_losses
FROM risk_limits WHERE mode = 'PAPER'
ON CONFLICT (mode) DO NOTHING;

INSERT INTO kill_switch (mode, stop_new_orders) VALUES ('SIM', FALSE) ON CONFLICT (mode) DO NOTHING;
