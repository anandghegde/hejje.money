package money.hejje.backtest;

import java.util.List;
import java.util.Map;
import money.hejje.common.Money;

/**
 * PRD section 12.1 metrics for one set of trades plus the series the UI renders. Ratios are doubles; money is
 * {@link Money}. {@code null} where a metric is not meaningful (for example CAGR under one year, Sharpe with one day).
 */
public record BacktestMetrics(
        int totalTrades,
        int winningTrades,
        int losingTrades,
        double winRate,
        double lossRate,
        Money averageWin,
        Money averageLoss,
        Double winLossRatio,
        double expectancyR,
        Money expectancyMoney,
        Double profitFactor,
        double totalReturnPct,
        Double cagrPct,
        Double sharpe,
        Double sortino,
        Money maxDrawdown,
        double maxDrawdownR,
        double maxDrawdownPct,
        int maxDrawdownDurationDays,
        int maxConsecutiveWins,
        int maxConsecutiveLosses,
        double averageHoldingMinutes,
        Money largestWin,
        Money largestLoss,
        Money grossPnl,
        Money totalCosts,
        Money netPnl,
        int sessions,
        Map<String, Integer> rDistribution,
        Map<String, BucketStats> monthly,
        Map<String, BucketStats> dayOfWeek,
        Map<String, BucketStats> hourOfDay,
        List<EquityPoint> equityCurve,
        List<EquityPoint> drawdownCurve) {

    /** Net P&L and trade count for one bucket of a breakdown table. */
    public record BucketStats(int trades, Money netPnl, double winRate) {}

    /** A point on the equity (or drawdown) curve after a trade closed. */
    public record EquityPoint(java.time.Instant time, Money value) {}
}
