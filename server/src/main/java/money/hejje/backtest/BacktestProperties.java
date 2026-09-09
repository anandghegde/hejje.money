package money.hejje.backtest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param workers            concurrent backtests
 * @param warmupDays         calendar days of candles loaded before {@code from} to warm indicators up
 * @param defaultRiskRupees  money risked per trade when neither the spec nor the definition says
 * @param maxTradesPerBacktest safety cap on persisted trades
 */
@ConfigurationProperties("hejje.backtest")
public record BacktestProperties(
        @DefaultValue("2") int workers,
        @DefaultValue("20") int warmupDays,
        @DefaultValue("2000") long defaultRiskRupees,
        @DefaultValue("200000") int maxTradesPerBacktest) {
}
