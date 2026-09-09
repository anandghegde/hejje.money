package money.hejje.backtest;

import java.util.List;
import java.util.Map;
import money.hejje.common.Money;

/**
 * Regime-conditional statistics of one backtest (plan M3.1): trades grouped by the chosen regime dimensions, and the
 * group matching the current snapshot ("how has this strategy done on days like today?").
 *
 * @param dims      dimensions in the key, in order (for example {@code [trend, volatility]})
 * @param byRegime  one bucket per observed key; unlabelled sessions fall into {@code UNKNOWN × UNKNOWN}
 * @param similar   the bucket matching the current regime, or null (see {@code note})
 * @param note      why {@code similar} is absent (current regime unknown, no matching trades, ...)
 */
public record RegimeBreakdown(List<String> dims, List<Bucket> byRegime, Similar similar, String note) {

    public record Bucket(String key, int trades, double winRate, double expectancyR, Double profitFactor, Money netPnl) {
    }

    /** {@code current} is the key of the current regime along {@code dims}; the overall figures allow the comparison. */
    public record Similar(String current, int trades, double winRate, double expectancyR, Double profitFactor, Money netPnl, int overallTrades,
            double overallExpectancyR) {
    }

    public static final List<String> DEFAULT_DIMS = List.of("trend", "volatility");
}
