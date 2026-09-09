package money.hejje.scoring.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import money.hejje.backtest.BacktestService;
import money.hejje.backtest.RegimeBreakdown;
import money.hejje.regime.RegimeProperties;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoreAdjuster;
import money.hejje.scoring.ScoreContext;
import money.hejje.strategy.StrategyDefinition;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * PRD section 14 "current regime" (plan M3.1, docs/hejje-score.md): the strategy's {@code regime_preferences} against
 * the current labels (preferred +3, avoid −6 each, that part clipped to [−6, +3]) plus the base backtest's expectancy in
 * similar regimes relative to its overall expectancy (±7 at ±0.5R difference, linear, needs 10 similar trades). Bounded
 * −10..+10; zero with an "unknown" evidence line when the regime engine has no labels.
 */
@Component
@Order(15)
public class RegimeCompatibilityAdjuster implements ScoreAdjuster {

    private final RegimeService regime;
    private final BacktestService backtests;
    private final RegimeProperties props;

    RegimeCompatibilityAdjuster(RegimeService regime, BacktestService backtests, RegimeProperties props) {
        this.regime = regime;
        this.backtests = backtests;
        this.props = props;
    }

    @Override
    public String name() {
        return "Current regime";
    }

    @Override
    public int min() {
        return -10;
    }

    @Override
    public int max() {
        return 10;
    }

    @Override
    public Adjustment adjust(ScoreContext ctx) {
        RegimeSnapshot current;
        try {
            current = regime.current();
        } catch (RuntimeException e) {
            return Adjustment.none(name(), min(), max(), "regime unavailable: " + e.getMessage());
        }
        if (current.isUnknown()) {
            return Adjustment.none(name(), min(), max(), "regime unknown: " + String.join("; ", current.evidence()));
        }
        RegimeProperties.AdjusterPoints points = props.adjuster();
        List<String> evidence = new ArrayList<>();
        evidence.add("Current regime " + current.key() + ", opening " + current.opening() + ", breadth " + current.breadth() + ", structure "
                + current.intradayStructure());

        int preference = 0;
        StrategyDefinition def = ctx.version().definition();
        for (Map.Entry<String, StrategyDefinition.RegimePreference> e : def.regimePreferences().entrySet()) {
            boolean matches = current.matches(e.getKey());
            switch (e.getValue()) {
                case PREFERRED -> {
                    if (matches) {
                        preference += points.preferredPoints();
                        evidence.add("Preferred regime '" + e.getKey() + "' is current: +" + points.preferredPoints());
                    } else {
                        evidence.add("Preferred regime '" + e.getKey() + "' is not current");
                    }
                }
                case AVOID -> {
                    if (matches) {
                        preference -= points.avoidPoints();
                        evidence.add("Avoided regime '" + e.getKey() + "' is current: -" + points.avoidPoints());
                    } else {
                        evidence.add("Avoided regime '" + e.getKey() + "' is not current");
                    }
                }
                case NEUTRAL -> { }
            }
        }
        if (def.regimePreferences().isEmpty()) {
            evidence.add("No regime preferences declared");
        }
        preference = Math.max(-points.avoidPoints(), Math.min(points.preferredPoints(), preference));

        int similar = 0;
        if (ctx.baseBacktest().isPresent()) {
            RegimeBreakdown regimes = backtests.regimeBreakdown(ctx.baseBacktest().get().id(), null);
            if (regimes.similar() == null) {
                evidence.add("Similar-regime performance: " + regimes.note());
            } else if (regimes.similar().trades() < points.minSimilarTrades()) {
                evidence.add(String.format(Locale.ROOT, "Similar-regime performance: only %d trades in %s (need %d)", regimes.similar().trades(),
                        regimes.similar().current(), points.minSimilarTrades()));
            } else {
                RegimeBreakdown.Similar s = regimes.similar();
                double diff = s.expectancyR() - s.overallExpectancyR();
                similar = (int) Math.round(points.similarFullPoints() * diff / points.similarFullDiffR());
                similar = Math.max(-points.similarFullPoints(), Math.min(points.similarFullPoints(), similar));
                evidence.add(String.format(Locale.ROOT, "Similar regime %s: %d trades, expectancy %.2fR vs %.2fR overall (%+.2fR): %+d", s.current(), s.trades(),
                        s.expectancyR(), s.overallExpectancyR(), diff, similar));
            }
        } else {
            evidence.add("Similar-regime performance: no base backtest");
        }
        return new Adjustment(name(), preference + similar, min(), max(), evidence);
    }
}
