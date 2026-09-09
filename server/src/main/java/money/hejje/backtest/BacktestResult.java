package money.hejje.backtest;

import java.util.List;
import java.util.Map;

/**
 * The full outcome of a backtest: overall metrics, metrics per split, walk-forward windows, warnings and the trades.
 *
 * @param sessionsExpected trading days in range according to the holiday calendar
 * @param sessionsWithData sessions for which at least one instrument had candles
 * @param skippedSignals   entry signals that produced no trade (risk budget below one lot, no next bar)
 */
public record BacktestResult(BacktestMetrics overall, Map<Split, BacktestMetrics> bySplit, List<WalkForwardWindow> windows,
        List<QualityWarning> warnings, List<BacktestTrade> trades, int sessionsExpected, int sessionsWithData, int skippedSignals,
        String resultHash) {

    public BacktestResult {
        bySplit = Map.copyOf(bySplit);
        windows = List.copyOf(windows);
        warnings = List.copyOf(warnings);
        trades = List.copyOf(trades);
    }

    public boolean hasFailure() {
        return warnings.stream().anyMatch(w -> w.severity() == QualityWarning.Severity.FAIL);
    }
}
