package money.hejje.backtest.experiments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Deterministic ranking of experiment variants (plan M4.7, PRD 24: out-of-sample improvement, robustness, simplicity,
 * adequate trade count, lower drawdown — never in-sample return). Each criterion ranks the variants from best (1 point)
 * to worst (0 points, ties share); the score is the weighted mean × 100. Criteria without data for every variant are
 * skipped. Warnings flag likely overfitting. Pure.
 */
public final class ExperimentRanking {

    public static final double MIN_TRADES = 100;

    /** @param numbers the variant's numeric parameters by path ({@link VariantDelta#numbers}) */
    public record Candidate(String name, boolean baseline, VariantMetrics metrics, int parameterCount, int conditionCount, Map<String, Double> numbers) {}

    public record Ranked(String name, int rank, double score, String verdict, List<String> warnings, Map<String, Double> points) {}

    public record Result(List<Ranked> variants, List<String> notes) {}

    record Criterion(String name, double weight, boolean higherIsBetter, Function<Candidate, Double> value) {}

    static final List<Criterion> CRITERIA = List.of(
            new Criterion("oosExpectancyR", 0.35, true, c -> c.metrics().basis().expectancyR()),
            new Criterion("oosProfitFactor", 0.15, true, c -> c.metrics().basis().profitFactor() == null ? 0.0 : c.metrics().basis().profitFactor()),
            new Criterion("maxDrawdownR", 0.15, false, c -> c.metrics().overall().maxDrawdownR()),
            new Criterion("tradeCount", 0.10, true, c -> Math.min(c.metrics().overall().trades(), MIN_TRADES)),
            new Criterion("simplicity", 0.15, false, c -> (double) (c.parameterCount() + c.conditionCount())),
            new Criterion("walkForwardStability", 0.10, false, c -> c.metrics().walkForwardStdR()));

    private ExperimentRanking() {
    }

    public static Result rank(List<Candidate> candidates) {
        List<Candidate> sorted = candidates.stream().sorted(Comparator.comparing(Candidate::name)).toList();
        Map<String, Map<String, Double>> points = new LinkedHashMap<>();
        sorted.forEach(c -> points.put(c.name(), new LinkedHashMap<>()));
        double weights = 0;
        for (Criterion criterion : CRITERIA) {
            if (sorted.size() < 2 || sorted.stream().anyMatch(c -> criterion.value().apply(c) == null)) {
                continue;
            }
            weights += criterion.weight();
            for (Candidate c : sorted) {
                points.get(c.name()).put(criterion.name(), position(sorted, c, criterion));
            }
        }
        double totalWeight = weights;
        Candidate baseline = sorted.stream().filter(Candidate::baseline).findFirst().orElse(null);
        List<Ranked> ranked = new ArrayList<>();
        for (Candidate c : sorted) {
            double score = 0;
            for (Criterion criterion : CRITERIA) {
                Double p = points.get(c.name()).get(criterion.name());
                if (p != null) {
                    score += criterion.weight() * p;
                }
            }
            score = totalWeight == 0 ? 0 : round1(score / totalWeight * 100);
            ranked.add(new Ranked(c.name(), 0, score, null, warnings(c, sorted), points.get(c.name())));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(Ranked::name));
        List<Ranked> out = new ArrayList<>();
        boolean recommended = false;
        for (int i = 0; i < ranked.size(); i++) {
            Ranked r = ranked.get(i);
            Candidate c = sorted.stream().filter(x -> x.name().equals(r.name())).findFirst().orElseThrow();
            String verdict;
            if (c.baseline()) {
                verdict = "BASELINE";
            } else if (baseline != null && c.metrics().basis().expectancyR() <= baseline.metrics().basis().expectancyR()) {
                verdict = "NOT_BETTER";
            } else if (!r.warnings().isEmpty()) {
                verdict = "BETTER_BUT_FRAGILE";
            } else if (!recommended) {
                verdict = "RECOMMENDED";
                recommended = true;
            } else {
                verdict = "BETTER_OUT_OF_SAMPLE";
            }
            out.add(new Ranked(r.name(), i + 1, r.score(), verdict, r.warnings(), r.points()));
        }
        List<String> notes = new ArrayList<>();
        if (candidates.size() >= 3) {
            notes.add("MULTIPLE_COMPARISONS: " + candidates.size() + " variants were tested on the same data; the best of " + candidates.size()
                    + " looks better by chance alone. Prefer simple changes that also hold out of sample and across walk-forward windows.");
        }
        if (candidates.stream().anyMatch(c -> c.metrics().outOfSample() == null)) {
            notes.add("NO_OUT_OF_SAMPLE: some variants were ranked on validation or overall results; run with an out-of-sample split before trusting a winner.");
        }
        return new Result(out, notes);
    }

    /** 1 for the best, 0 for the worst, ties share the average position. */
    private static double position(List<Candidate> all, Candidate c, Criterion criterion) {
        double v = criterion.value().apply(c);
        long better = all.stream().filter(o -> criterion.higherIsBetter() ? criterion.value().apply(o) > v : criterion.value().apply(o) < v).count();
        long equal = all.stream().filter(o -> criterion.value().apply(o) == v).count();
        double averageRank = better + (equal - 1) / 2.0;
        return round4(1 - averageRank / (all.size() - 1));
    }

    static List<String> warnings(Candidate c, List<Candidate> all) {
        List<String> w = new ArrayList<>();
        SplitSummary is = c.metrics().inSample();
        SplitSummary oos = c.metrics().outOfSample();
        if (is != null && oos != null && is.expectancyR() - oos.expectancyR() > Math.max(0.2, 0.5 * Math.abs(is.expectancyR()))) {
            w.add("IS_OOS_GAP: in-sample expectancy " + round2(is.expectancyR()) + "R vs out-of-sample " + round2(oos.expectancyR())
                    + "R; the edge may not survive new data");
        }
        if (c.metrics().overall().trades() < MIN_TRADES) {
            w.add("LOW_TRADES: " + c.metrics().overall().trades() + " trades (< " + (int) MIN_TRADES + "); too few to trust the difference");
        }
        for (Map.Entry<String, Double> e : c.numbers().entrySet()) {
            List<Double> tried = all.stream().map(o -> o.numbers().get(e.getKey())).filter(x -> x != null).distinct().sorted().toList();
            if (tried.size() >= 3 && (e.getValue().equals(tried.get(0)) || e.getValue().equals(tried.get(tried.size() - 1)))) {
                w.add("PARAMETER_AT_EDGE: " + e.getKey() + " = " + plain(e.getValue()) + " is at the edge of the tested range [" + plain(tried.get(0)) + ", "
                        + plain(tried.get(tried.size() - 1)) + "]; the optimum may lie outside it");
            }
        }
        c.metrics().qualityWarnings().stream().filter(q -> q.startsWith("FAIL")).forEach(q -> w.add("BACKTEST " + q));
        return w;
    }

    private static String plain(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
