package money.hejje.analytics.drift;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/** Pure statistics and classification of the drift monitor (deterministic: the bootstrap uses a fixed seed). */
public final class DriftMath {

    static final long SEED = 20260910L;

    private DriftMath() {
    }

    /** P(X ≤ k) for X ~ Binomial(n, p). */
    public static double binomialCdf(int k, int n, double p) {
        if (k >= n) {
            return 1.0;
        }
        if (k < 0) {
            return 0.0;
        }
        if (p <= 0) {
            return 1.0;
        }
        if (p >= 1) {
            return 0.0;
        }
        double pmf = Math.pow(1 - p, n);
        double sum = pmf;
        for (int i = 0; i < k; i++) {
            pmf *= (double) (n - i) / (i + 1) * p / (1 - p);
            sum += pmf;
        }
        return Math.min(1.0, sum);
    }

    /** Percentile bootstrap interval of the mean at {@code confidence} (two-sided), with a fixed seed. */
    public static double[] bootstrapMeanCi(List<Double> values, int samples, double confidence) {
        int n = values.size();
        if (n == 0) {
            return new double[]{0, 0};
        }
        SplittableRandom random = new SplittableRandom(SEED);
        double[] means = new double[samples];
        for (int s = 0; s < samples; s++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += values.get(random.nextInt(n));
            }
            means[s] = sum / n;
        }
        Arrays.sort(means);
        double tail = (1 - confidence) / 2;
        int lo = (int) Math.floor(tail * (samples - 1));
        int hi = (int) Math.ceil((1 - tail) * (samples - 1));
        return new double[]{means[lo], means[hi]};
    }

    public static double mean(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Sum of winning R over the absolute sum of losing R; null without losses. */
    public static Double profitFactor(List<Double> rs) {
        double wins = rs.stream().filter(r -> r > 0).mapToDouble(Double::doubleValue).sum();
        double losses = -rs.stream().filter(r -> r < 0).mapToDouble(Double::doubleValue).sum();
        return losses == 0 ? null : wins / losses;
    }

    /** Largest peak-to-trough fall of the cumulative R curve (a positive number). */
    public static double maxDrawdownR(List<Double> rs) {
        double equity = 0;
        double peak = 0;
        double dd = 0;
        for (double r : rs) {
            equity += r;
            peak = Math.max(peak, equity);
            dd = Math.max(dd, peak - equity);
        }
        return dd;
    }

    /** The backtest side of the comparison. */
    public record Baseline(int trades, double winRate, double expectancyR, Double profitFactor, double maxDrawdownR) {}

    /** The status reached plus the statistics and the criteria that fired. */
    public record Assessment(DriftStatus status, DriftReport.Side live, DriftReport.Stats stats, List<String> triggered, List<String> evidence) {}

    /**
     * Classifies the trailing trades (R multiples, oldest first; wins are trades with positive net P&L) against the
     * baseline. Levels are checked from FAILED down; the first level with a met criterion is the status.
     */
    public static Assessment assess(List<Double> rs, int wins, Baseline baseline, DriftProperties p) {
        int n = rs.size();
        double winRate = n == 0 ? 0 : (double) wins / n;
        DriftReport.Side live = new DriftReport.Side(n, winRate, mean(rs), profitFactor(rs), maxDrawdownR(rs), null, null);
        List<String> evidence = new ArrayList<>();
        if (baseline == null) {
            evidence.add("no completed backtest of this version to compare against");
            return new Assessment(DriftStatus.INSUFFICIENT_DATA, live, null, List.of(), evidence);
        }
        if (n < p.minTrades()) {
            evidence.add(String.format("%d trade(s) in the trailing window; drift is assessed from %d", n, p.minTrades()));
            return new Assessment(DriftStatus.INSUFFICIENT_DATA, live, null, List.of(), evidence);
        }
        double pValue = binomialCdf(wins, n, baseline.winRate());
        double[] ci = bootstrapMeanCi(rs, p.bootstrapSamples(), p.confidence());
        Double expRatio = baseline.expectancyR() > 0 ? live.expectancyR() / baseline.expectancyR() : null;
        Double ddMultiple = baseline.maxDrawdownR() > 0 ? live.maxDrawdownR() / baseline.maxDrawdownR() : null;
        DriftReport.Stats stats = new DriftReport.Stats(pValue, ci[0], ci[1], p.confidence(), expRatio, ddMultiple);
        evidence.add(String.format("win rate %s over %d trades vs %s backtest (binomial p = %.3f)", pct(winRate), n, pct(baseline.winRate()), pValue));
        evidence.add(String.format("expectancy %s vs %s backtest (%.0f%% interval %s to %s)", r(live.expectancyR()), r(baseline.expectancyR()),
                p.confidence() * 100, r(ci[0]), r(ci[1])));
        evidence.add(String.format("profit factor %s vs %s backtest", pf(live.profitFactor()), pf(baseline.profitFactor())));
        evidence.add(String.format("max drawdown %.1fR vs %.1fR backtest", live.maxDrawdownR(), baseline.maxDrawdownR()));
        DriftProperties.Thresholds t = p.thresholds();
        for (var level : List.of(new Object[]{DriftStatus.FAILED, t.failed()}, new Object[]{DriftStatus.DEGRADING, t.degrading()},
                new Object[]{DriftStatus.WATCH, t.watch()})) {
            List<String> hits = criteria((DriftProperties.Level) level[1], live, baseline, stats);
            if (!hits.isEmpty()) {
                return new Assessment((DriftStatus) level[0], live, stats, hits, evidence);
            }
        }
        return new Assessment(DriftStatus.HEALTHY, live, stats, List.of(), evidence);
    }

    private static List<String> criteria(DriftProperties.Level level, DriftReport.Side live, Baseline b, DriftReport.Stats s) {
        List<String> hits = new ArrayList<>();
        if (level == null) {
            return hits;
        }
        if (level.winRatePValue() != null && s.winRatePValue() < level.winRatePValue()) {
            hits.add(String.format("win rate %s vs %s backtest is unlikely by chance (p = %.3f < %s)", pct(live.winRate()), pct(b.winRate()),
                    s.winRatePValue(), strip(level.winRatePValue())));
        }
        if (level.expectancyRatio() != null && s.expectancyRatio() != null && s.expectancyRatio() < level.expectancyRatio()) {
            hits.add(String.format("expectancy %s is %.0f%% of the backtest's %s (below %.0f%%)", r(live.expectancyR()), s.expectancyRatio() * 100,
                    r(b.expectancyR()), level.expectancyRatio() * 100));
        }
        if (level.expectancyUpperRatio() != null && b.expectancyR() > 0 && s.expectancyHigh() < b.expectancyR() * level.expectancyUpperRatio()) {
            hits.add(String.format("even the upper %.0f%% bound of the expectancy (%s) is below %s of the backtest (%s)", s.confidence() * 100,
                    r(s.expectancyHigh()), level.expectancyUpperRatio() == 1.0 ? "that" : String.format("%.0f%%", level.expectancyUpperRatio() * 100),
                    r(b.expectancyR())));
        }
        if (level.expectancyUpperR() != null && s.expectancyHigh() < level.expectancyUpperR()) {
            hits.add(String.format("the upper %.0f%% bound of the expectancy (%s) is below %s: losing with confidence", s.confidence() * 100,
                    r(s.expectancyHigh()), r(level.expectancyUpperR())));
        }
        if (level.drawdownMultiple() != null && s.drawdownMultiple() != null && s.drawdownMultiple() >= level.drawdownMultiple()) {
            hits.add(String.format("max drawdown %.1fR is %.1f× the backtest's %.1fR (limit %s×)", live.maxDrawdownR(), s.drawdownMultiple(),
                    b.maxDrawdownR(), strip(level.drawdownMultiple())));
        }
        return hits;
    }

    private static String pct(double v) {
        return String.format("%.0f%%", v * 100);
    }

    private static String r(double v) {
        return String.format("%+.2fR", v);
    }

    private static String pf(Double v) {
        return v == null ? "n/a" : String.format("%.2f", v);
    }

    private static String strip(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
