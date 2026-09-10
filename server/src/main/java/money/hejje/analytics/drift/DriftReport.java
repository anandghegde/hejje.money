package money.hejje.analytics.drift;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One deployment's live-vs-backtest comparison (PRD section 25 table).
 *
 * @param live      the trailing paper/live trades (R multiples from the post-trade reviews)
 * @param backtest  the version's base backtest, out-of-sample slice when it has one ({@code split})
 * @param stats     the statistical signals; null when not assessed
 * @param triggered the criteria met by the status's level, as templated sentences
 * @param evidence  every comparison, as templated sentences
 */
public record DriftReport(UUID deploymentId, UUID versionId, int version, UUID strategyId, String mode, boolean enabled, BigDecimal sizeMultiplier,
        DriftStatus status, Window window, Side live, Side backtest, Stats stats, List<String> triggered, List<String> evidence, Instant assessedAt) {

    public DriftReport {
        triggered = List.copyOf(triggered);
        evidence = List.copyOf(evidence);
    }

    /** The trailing window actually used: {@code from} is the first session counted. */
    public record Window(int maxTrades, int sessions, LocalDate from, LocalDate to) {}

    /** Win rate, expectancy (R), profit factor (on R) and max drawdown (R) of one side. {@code backtestId}/{@code split} only on the backtest side. */
    public record Side(int trades, double winRate, double expectancyR, Double profitFactor, double maxDrawdownR, UUID backtestId, String split) {}

    /** @param winRatePValue one-sided binomial p-value of the live wins under the backtest win rate */
    public record Stats(double winRatePValue, double expectancyLow, double expectancyHigh, double confidence, Double expectancyRatio,
            Double drawdownMultiple) {}
}
