package money.hejje.scoring.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.scoring.ComparisonRow;
import money.hejje.scoring.VersionComparison;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyVersion;

/** Builds the PRD 21 rows and the PRD 11 two-version comparison with its templated verdict. */
public final class Comparisons {

    private Comparisons() {
    }

    /** "0.42R over 34 trades in UP × NORMAL (0.25R overall)" or the reason there is no similar-regime figure. */
    public static String similarRegime(money.hejje.backtest.RegimeBreakdown regimes) {
        if (regimes.similar() == null) {
            return regimes.note() == null ? "n/a" : regimes.note();
        }
        var s = regimes.similar();
        return String.format(java.util.Locale.ROOT, "%.2fR over %d trades in %s (%.2fR overall)", s.expectancyR(), s.trades(), s.current(), s.overallExpectancyR());
    }

    public static ComparisonRow row(Strategy strategy, StrategyVersion version, Optional<Backtest> base, String similarRegime, Integer score) {
        BacktestMetrics m = base.map(Backtest::metrics).orElse(null);
        return new ComparisonRow(version.id(), strategy.id(), strategy.slug(), version.version(), version.status().name(), base.map(Backtest::id).orElse(null),
                m == null ? null : m.totalTrades(), m == null ? null : m.winRate(), m == null ? null : m.profitFactor(), m == null ? null : m.expectancyR(),
                m == null ? null : m.maxDrawdownR(), similarRegime, score);
    }

    public static VersionComparison versions(ComparisonRow a, ComparisonRow b) {
        List<Map<String, Object>> deltas = new ArrayList<>();
        deltas.add(delta("Profit factor", a.profitFactor(), b.profitFactor(), true));
        deltas.add(delta("Expectancy (R)", a.expectancyR(), b.expectancyR(), true));
        deltas.add(delta("Max drawdown (R)", a.maxDrawdownR(), b.maxDrawdownR(), false));
        deltas.add(delta("Trades", a.trades() == null ? null : a.trades().doubleValue(), b.trades() == null ? null : b.trades().doubleValue(), true));
        deltas.add(delta("Win rate", a.winRate(), b.winRate(), true));
        deltas.add(delta("Hejje Score", a.hejjeScore() == null ? null : a.hejjeScore().doubleValue(), b.hejjeScore() == null ? null : b.hejjeScore().doubleValue(), true));
        return new VersionComparison(a, b, deltas, verdict(a, b, deltas));
    }

    private static Map<String, Object> delta(String metric, Double a, Double b, boolean higherIsBetter) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", metric);
        out.put("a", a);
        out.put("b", b);
        Double changePct = a == null || b == null || a == 0 ? null : (b - a) / Math.abs(a) * 100.0;
        out.put("changePct", changePct == null ? null : Math.round(changePct * 10) / 10.0);
        out.put("better", changePct == null ? null : (higherIsBetter ? changePct > 0 : changePct < 0));
        return out;
    }

    /** "v3 improved profit factor by 17.6% but reduced trades by 8.4%." built from the largest improvement and regression. */
    static String verdict(ComparisonRow a, ComparisonRow b, List<Map<String, Object>> deltas) {
        if (a.backtestId() == null || b.backtestId() == null) {
            return "Both versions need a completed backtest before they can be compared.";
        }
        Map<String, Object> bestUp = null;
        Map<String, Object> worstDown = null;
        for (Map<String, Object> d : deltas) {
            if (d.get("changePct") == null || "Hejje Score".equals(d.get("metric"))) {
                continue;
            }
            double magnitude = Math.abs((Double) d.get("changePct"));
            boolean better = (Boolean) d.get("better");
            if (better && (bestUp == null || magnitude > Math.abs((Double) bestUp.get("changePct")))) {
                bestUp = d;
            }
            if (!better && magnitude > 0 && (worstDown == null || magnitude > Math.abs((Double) worstDown.get("changePct")))) {
                worstDown = d;
            }
        }
        String vb = "v" + b.version();
        Function<Map<String, Object>, String> phrase = d -> {
            double pct = Math.abs((Double) d.get("changePct"));
            String metric = ((String) d.get("metric")).toLowerCase();
            boolean up = ((Double) d.get("changePct")) > 0;
            return (up ? "increased " : "reduced ") + metric + " by " + String.format("%.1f%%", pct);
        };
        if (bestUp == null && worstDown == null) {
            return vb + " performs the same as v" + a.version() + " on every metric.";
        }
        if (bestUp != null && worstDown != null) {
            return vb + " improved: " + phrase.apply(bestUp) + ", but " + phrase.apply(worstDown) + ".";
        }
        if (bestUp != null) {
            return vb + " improved on every changed metric; largest gain: " + phrase.apply(bestUp) + ".";
        }
        return vb + " regressed on every changed metric; largest loss: " + phrase.apply(worstDown) + ".";
    }
}
