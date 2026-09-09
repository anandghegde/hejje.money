package money.hejje.scoring.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.Split;
import money.hejje.backtest.WalkForwardWindow;
import money.hejje.scoring.ScoreBreakdown.Component;

/**
 * The documented base formula (docs/hejje-score.md): per-split components mapped to 0-100 through piecewise-linear
 * functions and combined with split weights (OOS 60 %, validation 25 %, IS 15 %, renormalised over the splits present),
 * plus two whole-backtest components (walk-forward stability, slippage sensitivity), all weighted and clipped to 0-100.
 * A base score cannot exceed 50 when the out-of-sample slice has fewer than 30 trades.
 */
public final class BaseScoreCalculator {

    public static final int MIN_OOS_TRADES = 30;
    public static final double SMALL_SAMPLE_CAP = 50;

    static final Map<Split, Double> SPLIT_WEIGHTS = Map.of(Split.OUT_OF_SAMPLE, 0.60, Split.VALIDATION, 0.25, Split.IN_SAMPLE, 0.15);

    static final PiecewiseLinear EXPECTANCY = new PiecewiseLinear(new double[]{0, 0.1, 0.25, 0.5, 1.0}, new double[]{0, 30, 60, 85, 100});
    static final PiecewiseLinear PROFIT_FACTOR = new PiecewiseLinear(new double[]{1.0, 1.2, 1.5, 2.0, 3.0}, new double[]{0, 40, 70, 90, 100});
    static final PiecewiseLinear DRAWDOWN_R = new PiecewiseLinear(new double[]{0, 5, 10, 20, 40}, new double[]{100, 80, 60, 30, 0});
    static final PiecewiseLinear CONSISTENCY = new PiecewiseLinear(new double[]{0, 1}, new double[]{0, 100});
    static final PiecewiseLinear SAMPLE = new PiecewiseLinear(new double[]{0, 30, 100, 300, 1000}, new double[]{0, 30, 60, 85, 100});
    static final PiecewiseLinear STABILITY = new PiecewiseLinear(new double[]{0, 0.25, 0.5, 1.0}, new double[]{100, 70, 40, 0});
    static final PiecewiseLinear SLIPPAGE = new PiecewiseLinear(new double[]{0, 25, 50, 100}, new double[]{100, 70, 40, 0});

    /** Result: the base (after the cap), the cap note and the components (whose contributions sum to the uncapped base). */
    public record BaseScore(double base, String cap, List<Component> components) {}

    private BaseScoreCalculator() {
    }

    public static BaseScore none() {
        return new BaseScore(0, "no completed backtest", List.of());
    }

    public static BaseScore compute(Backtest backtest, SlippageSensitivity.Result slippage) {
        Map<Split, BacktestMetrics> bySplit = new LinkedHashMap<>();
        for (Split split : Split.values()) {
            BacktestMetrics m = backtest.bySplit().get(split);
            if (m != null && m.totalTrades() > 0) {
                bySplit.put(split, m);
            }
        }
        if (bySplit.isEmpty() && backtest.metrics() != null && backtest.metrics().totalTrades() > 0) {
            bySplit.put(Split.IN_SAMPLE, backtest.metrics());
        }
        List<Component> components = new ArrayList<>();
        components.add(splitComponent("Expectancy (R)", 0.25, bySplit, m -> m.expectancyR(), EXPECTANCY));
        components.add(splitComponent("Profit factor", 0.20, bySplit, BaseScoreCalculator::profitFactorOf, PROFIT_FACTOR));
        components.add(splitComponent("Max drawdown (R)", 0.15, bySplit, m -> m.maxDrawdownR(), DRAWDOWN_R));
        components.add(splitComponent("Consistency (profitable months)", 0.15, bySplit, BaseScoreCalculator::profitableMonthShare, CONSISTENCY));
        components.add(splitComponent("Sample size", 0.10, bySplit, m -> (double) m.totalTrades(), SAMPLE));
        components.add(stability(backtest.windows()));
        components.add(slippageComponent(slippage));
        double raw = components.stream().mapToDouble(Component::contribution).sum();
        double base = Math.max(0, Math.min(100, raw));
        String cap = null;
        BacktestMetrics oos = backtest.bySplit().get(Split.OUT_OF_SAMPLE);
        int oosTrades = oos == null ? 0 : oos.totalTrades();
        if (oosTrades < MIN_OOS_TRADES && base > SMALL_SAMPLE_CAP) {
            cap = "capped at " + (int) SMALL_SAMPLE_CAP + ": only " + oosTrades + " out-of-sample trades (need " + MIN_OOS_TRADES + ")";
            base = SMALL_SAMPLE_CAP;
        }
        return new BaseScore(round1(base), cap, components);
    }

    interface Metric {
        Double of(BacktestMetrics m);
    }

    private static Component splitComponent(String name, double weight, Map<Split, BacktestMetrics> bySplit, Metric metric, PiecewiseLinear map) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        double weighted = 0;
        double totalWeight = 0;
        for (Map.Entry<Split, BacktestMetrics> e : bySplit.entrySet()) {
            Double value = metric.of(e.getValue());
            double mapped = value == null ? 100 : map.apply(value); // null profit factor = no losing trade
            double w = SPLIT_WEIGHTS.get(e.getKey());
            weighted += w * mapped;
            totalWeight += w;
            evidence.put(e.getKey().name().toLowerCase(), value == null ? "n/a" : round(value, 3));
        }
        double score = totalWeight == 0 ? 0 : weighted / totalWeight;
        if (bySplit.isEmpty()) {
            evidence.put("note", "no trades");
        }
        return new Component(name, weight, round1(score), round1(weight * score), evidence);
    }

    private static Component stability(List<WalkForwardWindow> windows) {
        List<Double> expectancies = windows.stream().filter(w -> w.trades() > 0).map(WalkForwardWindow::expectancyR).toList();
        Map<String, Object> evidence = new LinkedHashMap<>();
        double score;
        if (expectancies.size() < 2) {
            score = 50;
            evidence.put("note", windows.isEmpty() ? "no walk-forward windows (neutral 50)" : "fewer than two windows with trades (neutral 50)");
        } else {
            double mean = expectancies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double var = expectancies.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (expectancies.size() - 1);
            double sd = Math.sqrt(var);
            score = STABILITY.apply(sd);
            evidence.put("windows", expectancies.size());
            evidence.put("stdDevExpectancyR", round(sd, 3));
        }
        return new Component("Walk-forward stability", 0.10, round1(score), round1(0.10 * score), evidence);
    }

    private static Component slippageComponent(SlippageSensitivity.Result slippage) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        double score;
        if (slippage == null) {
            score = 50;
            evidence.put("note", "sensitivity not available (neutral 50)");
        } else {
            score = SLIPPAGE.apply(slippage.dropPct());
            evidence.put("baseExpectancyR", round(slippage.baseExpectancyR(), 3));
            evidence.put("doubledSlippageExpectancyR", round(slippage.doubledExpectancyR(), 3));
            evidence.put("dropPct", round(slippage.dropPct(), 1));
        }
        return new Component("Slippage sensitivity", 0.05, round1(score), round1(0.05 * score), evidence);
    }

    static Double profitFactorOf(BacktestMetrics m) {
        return m.profitFactor();
    }

    static Double profitableMonthShare(BacktestMetrics m) {
        if (m.monthly().isEmpty()) {
            return 0.0;
        }
        long profitable = m.monthly().values().stream().filter(b -> b.netPnl().paise() > 0).count();
        return (double) profitable / m.monthly().size();
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double round(double v, int places) {
        double p = Math.pow(10, places);
        return Math.round(v * p) / p;
    }
}
