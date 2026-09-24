package money.hejje.risk.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.risk.RiskLimits;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RiskLimitsStore {

    private final JdbcClient jdbc;

    RiskLimitsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RiskLimits find(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM risk_limits WHERE mode = :mode").param("mode", mode.name()).query(this::map).single();
    }

    public void update(RiskLimits l) {
        jdbc.sql("""
                UPDATE risk_limits SET max_loss_per_day_paise = :maxLossPerDay, max_realized_loss_paise = :maxRealizedLoss,
                    max_total_loss_paise = :maxTotalLoss, max_capital_deployed_paise = :maxCapital, max_margin_utilization_pct = :maxMargin,
                    max_open_positions = :maxOpen, max_gross_exposure_paise = :maxGross, max_trades_per_day = :maxTrades,
                    max_risk_per_trade_paise = :maxRiskPerTrade, max_quantity = :maxQty, max_notional_paise = :maxNotional,
                    min_reward_risk = :minRr, mandatory_stop = :mandatoryStop, max_stop_distance_pct = :maxStopDist,
                    no_new_trades_after = :noNewAfter, no_averaging_down = :noAvg, no_reentry_minutes = :noReentry,
                    max_consecutive_losses = :maxConsecutive, loss_streak_mode = :lossStreakMode, allowance_drawdown_paise = :allowanceDrawdown,
                    loss_streak_allowance = :allowance, trades_per_day_when_green = :whenGreen, updated_at = now() WHERE mode = :mode
                """).param("lossStreakMode", l.lossStreakMode().name()).param("allowanceDrawdown", l.allowanceDrawdown().paise())
                .param("allowance", l.lossStreakAllowance()).param("whenGreen", l.tradesPerDayWhenGreen().name())
                .param("mode", l.mode().name()).param("maxLossPerDay", l.maxLossPerDay().paise())
                .param("maxRealizedLoss", l.maxRealizedLoss().paise()).param("maxTotalLoss", l.maxTotalLossInclUnrealized().paise())
                .param("maxCapital", l.maxCapitalDeployed().paise()).param("maxMargin", l.maxMarginUtilizationPct())
                .param("maxOpen", l.maxOpenPositions()).param("maxGross", l.maxGrossExposure().paise())
                .param("maxTrades", l.maxTradesPerDay()).param("maxRiskPerTrade", l.maxRiskPerTrade().paise())
                .param("maxQty", l.maxQuantity()).param("maxNotional", l.maxNotional().paise()).param("minRr", l.minRewardRisk())
                .param("mandatoryStop", l.mandatoryStop()).param("maxStopDist", l.maxStopDistancePct())
                .param("noNewAfter", l.noNewTradesAfter()).param("noAvg", l.noAveragingDown()).param("noReentry", l.noReentryMinutes())
                .param("maxConsecutive", l.maxConsecutiveLosses()).update();
    }

    private RiskLimits map(ResultSet rs, int i) throws SQLException {
        return new RiskLimits(
                ExecutionMode.valueOf(rs.getString("mode")),
                Money.ofPaise(rs.getLong("max_loss_per_day_paise")),
                Money.ofPaise(rs.getLong("max_realized_loss_paise")),
                Money.ofPaise(rs.getLong("max_total_loss_paise")),
                Money.ofPaise(rs.getLong("max_capital_deployed_paise")),
                rs.getBigDecimal("max_margin_utilization_pct"),
                rs.getInt("max_open_positions"),
                Money.ofPaise(rs.getLong("max_gross_exposure_paise")),
                rs.getInt("max_trades_per_day"),
                Money.ofPaise(rs.getLong("max_risk_per_trade_paise")),
                rs.getInt("max_quantity"),
                Money.ofPaise(rs.getLong("max_notional_paise")),
                rs.getBigDecimal("min_reward_risk"),
                rs.getBoolean("mandatory_stop"),
                rs.getBigDecimal("max_stop_distance_pct"),
                rs.getObject("no_new_trades_after", LocalTime.class),
                rs.getBoolean("no_averaging_down"),
                rs.getInt("no_reentry_minutes"),
                rs.getInt("max_consecutive_losses"),
                RiskLimits.LossStreakMode.valueOf(rs.getString("loss_streak_mode")),
                Money.ofPaise(rs.getLong("allowance_drawdown_paise")),
                rs.getInt("loss_streak_allowance"),
                RiskLimits.TradesWhenGreen.valueOf(rs.getString("trades_per_day_when_green")));
    }
}
