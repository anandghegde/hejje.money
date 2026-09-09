package money.hejje.scoring.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestResult;
import money.hejje.backtest.BacktestService;
import money.hejje.backtest.BacktestSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Re-runs the base backtest with twice the slippage and measures the drop in overall expectancy. Cached per backtest
 * id (the input never changes), so the five-minute rescoring never re-runs it.
 */
@Component
public class SlippageSensitivity {

    private static final Logger log = LoggerFactory.getLogger(SlippageSensitivity.class);

    /** @param dropPct percentage drop in expectancy (0 when it did not drop; 100 when it fell to zero or below) */
    public record Result(double baseExpectancyR, double doubledExpectancyR, double dropPct) {}

    private final BacktestService backtests;
    private final Map<UUID, Result> cache = new ConcurrentHashMap<>();

    SlippageSensitivity(BacktestService backtests) {
        this.backtests = backtests;
    }

    public Result of(Backtest base) {
        if (base.metrics() == null || base.metrics().totalTrades() == 0) {
            return null;
        }
        return cache.computeIfAbsent(base.id(), id -> run(base));
    }

    private Result run(Backtest base) {
        try {
            BacktestSpec spec = base.spec();
            int doubled = Math.max(spec.slippageBps() * 2, spec.slippageBps() + 5);
            BacktestSpec sensitive = new BacktestSpec(spec.versionId(), spec.instrumentIds(), spec.timeframe(), spec.from(), spec.to(), spec.fillModel(),
                    doubled, spec.costModelVersion(), spec.splits(), spec.initialCapital(), spec.riskPerTrade());
            BacktestResult result = backtests.evaluate(sensitive);
            double before = base.metrics().expectancyR();
            double after = result.overall().expectancyR();
            return new Result(before, after, dropPct(before, after));
        } catch (RuntimeException e) {
            log.warn("Slippage sensitivity run failed for backtest {}: {}", base.id(), e.getMessage());
            return null;
        }
    }

    static double dropPct(double before, double after) {
        if (before <= 0) {
            return after < before ? 100 : 0;
        }
        if (after >= before) {
            return 0;
        }
        return Math.min(100, (before - after) / before * 100.0);
    }

    /** Test hook. */
    public void prime(UUID backtestId, Result result) {
        cache.put(backtestId, result);
    }
}
