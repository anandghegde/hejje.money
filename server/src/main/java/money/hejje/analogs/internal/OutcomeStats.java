package money.hejje.analogs.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import money.hejje.analogs.AnalogSummary;
import money.hejje.analogs.AnalogsProperties;

/** What followed a set of matches: the distribution per forward window and its tags (docs/analogs.md, "Outcomes" and "Tags"). */
final class OutcomeStats {

    /**
     * What followed one match.
     *
     * @param date       last session of the matched window (the matched session for session analogs)
     * @param cumulative cumulative return in percent after 1, 2, ... steps from the end of the matched window
     */
    record Followed(String symbol, java.time.LocalDate date, double[] cumulative) {
    }

    private OutcomeStats() {
    }

    /**
     * @param steps steps of the forward window (the outcome is {@code cumulative[steps - 1]})
     * @param scale multiplier of the percent thresholds (daily: the square root of the sessions ahead)
     */
    static AnalogSummary.Outcome outcome(String label, int steps, double scale, List<Followed> matches, AnalogsProperties props) {
        int n = matches.size();
        if (n == 0) {
            return new AnalogSummary.Outcome(label, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), 0, 0, "INSUFFICIENT", "NONE",
                    "INSUFFICIENT", "NONE", false);
        }
        double[] returns = new double[n];
        double[] mae = new double[n];
        double[] mfe = new double[n];
        for (int m = 0; m < n; m++) {
            double[] path = matches.get(m).cumulative();
            returns[m] = path[steps - 1];
            double worst = 0;
            double best = 0;
            for (int k = 0; k < steps; k++) {
                worst = Math.min(worst, path[k]);
                best = Math.max(best, path[k]);
            }
            mae[m] = worst;
            mfe[m] = best;
        }
        double[] sorted = returns.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(returns).average().orElse(0);
        double median = AnalogMath.percentile(sorted, 0.5);
        double p25 = AnalogMath.percentile(sorted, 0.25);
        double p75 = AnalogMath.percentile(sorted, 0.75);
        double winRate = (double) Arrays.stream(returns).filter(r -> r > 0).count() / n;
        List<Double> avgPath = new ArrayList<>();
        List<Double> p25Path = new ArrayList<>();
        List<Double> p75Path = new ArrayList<>();
        for (int k = 0; k < steps; k++) {
            final int step = k;
            double[] at = matches.stream().mapToDouble(f -> f.cumulative()[step]).sorted().toArray();
            avgPath.add(AnalogMath.round4(Arrays.stream(at).average().orElse(0)));
            p25Path.add(AnalogMath.round4(AnalogMath.percentile(at, 0.25)));
            p75Path.add(AnalogMath.round4(AnalogMath.percentile(at, 0.75)));
        }
        double[] maeSorted = mae.clone();
        Arrays.sort(maeSorted);
        double[] mfeSorted = mfe.clone();
        Arrays.sort(mfeSorted);
        int symbols = (int) matches.stream().map(Followed::symbol).distinct().count();
        int years = (int) matches.stream().map(f -> f.date().getYear()).distinct().count();
        AnalogsProperties.Tags t = props.tags();
        double maeMedian = AnalogMath.percentile(maeSorted, 0.5);
        return new AnalogSummary.Outcome(label, n, AnalogMath.round4(winRate), AnalogMath.round4(mean), AnalogMath.round4(median), AnalogMath.round4(p25),
                AnalogMath.round4(p75), AnalogMath.round4(sorted[n - 1]), AnalogMath.round4(sorted[0]), AnalogMath.round4(maeMedian),
                AnalogMath.round4(AnalogMath.percentile(maeSorted, 0.25)), AnalogMath.round4(AnalogMath.percentile(mfeSorted, 0.5)),
                AnalogMath.round4(AnalogMath.percentile(mfeSorted, 0.75)), avgPath, p25Path, p75Path, symbols, years,
                direction(n, winRate, median, props), consistency(n, p75 - p25, scale, props), reliability(n, symbols, years, props),
                n < props.minEvidence() ? "NONE" : maeMedian <= -t.highRiskMaePct() * scale ? "HIGH" : maeMedian <= -t.moderateRiskMaePct() * scale ? "MODERATE" : "LOW",
                outlier(returns, mean, median, t.outlierShare()));
    }

    /** Win rate and median together: both must point the same way. */
    static String direction(int n, double winRate, double median, AnalogsProperties props) {
        AnalogsProperties.Tags t = props.tags();
        if (n < props.minEvidence()) {
            return "INSUFFICIENT";
        }
        if (median > 0 && winRate >= t.leanWinRate()) {
            return winRate >= t.strongWinRate() ? "BULLISH_STRONG" : "BULLISH";
        }
        if (median < 0 && winRate <= 1 - t.leanWinRate()) {
            return winRate <= 1 - t.strongWinRate() ? "BEARISH_STRONG" : "BEARISH";
        }
        return "MIXED";
    }

    static String consistency(int n, double iqr, double scale, AnalogsProperties props) {
        if (n < props.minEvidence()) {
            return "NONE";
        }
        return iqr <= props.tags().tightIqrPct() * scale ? "TIGHT" : iqr >= props.tags().wideIqrPct() * scale ? "WIDE" : "NORMAL";
    }

    static String reliability(int n, int symbols, int years, AnalogsProperties props) {
        AnalogsProperties.Tags t = props.tags();
        if (n < props.minEvidence()) {
            return "INSUFFICIENT";
        }
        if (n >= t.highReliabilityCount() && symbols >= t.highReliabilitySymbols() && years >= t.highReliabilityYears()) {
            return "HIGH";
        }
        return n >= t.mediumReliabilityCount() && symbols >= t.mediumReliabilitySymbols() && years >= t.mediumReliabilityYears() ? "MEDIUM" : "LOW";
    }

    /** True when dropping the two matches farthest from the median moves the mean by more than {@code share} of itself (and by at least 0.1 %). */
    static boolean outlier(double[] returns, double mean, double median, double share) {
        if (returns.length < 5) {
            return false;
        }
        double[] byDistance = Arrays.stream(returns).boxed().sorted(Comparator.comparingDouble((Double r) -> Math.abs(r - median)).reversed())
                .mapToDouble(Double::doubleValue).toArray();
        double trimmed = Arrays.stream(byDistance, 2, byDistance.length).average().orElse(mean);
        double moved = Math.abs(mean - trimmed);
        return moved >= 0.1 && moved > share * Math.abs(mean);
    }

    /** The matches split by {@code inGroup}, each side with its count. */
    static AnalogSummary.Split split(String name, String label, int steps, List<Followed> matches, Predicate<Followed> inGroup) {
        List<Followed> in = matches.stream().filter(inGroup).toList();
        List<Followed> out = matches.stream().filter(inGroup.negate()).toList();
        return new AnalogSummary.Split(name, label, in.size(), winRate(in, steps), medianOf(in, steps), out.size(), winRate(out, steps), medianOf(out, steps));
    }

    private static double winRate(List<Followed> matches, int steps) {
        return matches.isEmpty() ? 0 : AnalogMath.round4((double) matches.stream().filter(f -> f.cumulative()[steps - 1] > 0).count() / matches.size());
    }

    private static double medianOf(List<Followed> matches, int steps) {
        return matches.isEmpty() ? 0 : AnalogMath.round4(AnalogMath.median(matches.stream().mapToDouble(f -> f.cumulative()[steps - 1]).toArray()));
    }
}
