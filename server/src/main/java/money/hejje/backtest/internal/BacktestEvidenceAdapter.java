package money.hejje.backtest.internal;

import java.util.UUID;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestStatus;
import money.hejje.backtest.Split;
import money.hejje.strategy.StrategyEvidence;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Supplies the strategy lifecycle's evidence: BACKTESTED needs any completed backtest; VALIDATED needs a completed
 * backtest with an out-of-sample slice of at least {@link QualityChecker#MIN_TRADES_FAIL} trades and no FAIL warning.
 * A research run with a session filter (plan M8.8) is never evidence: its entries were restricted from outside the strategy.
 */
@Component
@Primary
public class BacktestEvidenceAdapter implements StrategyEvidence {

    private final BacktestStore store;

    BacktestEvidenceAdapter(BacktestStore store) {
        this.store = store;
    }

    @Override
    public boolean hasBacktest(UUID versionId) {
        return store.findByVersion(versionId).stream().anyMatch(b -> b.status() == BacktestStatus.DONE && b.spec().sessionFilter() == null);
    }

    @Override
    public boolean hasValidatedBacktest(UUID versionId) {
        return store.findByVersion(versionId).stream().anyMatch(BacktestEvidenceAdapter::validates);
    }

    public static boolean validates(Backtest b) {
        if (b.status() != BacktestStatus.DONE || b.spec().sessionFilter() != null || !b.spec().splits().hasOutOfSample()) {
            return false;
        }
        if (b.warnings().stream().anyMatch(w -> w.severity() == money.hejje.backtest.QualityWarning.Severity.FAIL)) {
            return false;
        }
        BacktestMetrics oos = b.bySplit().get(Split.OUT_OF_SAMPLE);
        return oos != null && oos.totalTrades() >= QualityChecker.MIN_TRADES_FAIL;
    }
}
