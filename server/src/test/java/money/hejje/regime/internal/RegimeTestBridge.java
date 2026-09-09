package money.hejje.regime.internal;

import java.time.LocalDate;
import java.util.List;
import money.hejje.market.indicators.Bar;
import money.hejje.regime.Breadth;
import money.hejje.regime.IntradayStructure;
import money.hejje.regime.Opening;
import money.hejje.regime.RegimeProperties;
import money.hejje.regime.RegimeTestSupport.Outcome;
import money.hejje.regime.Trend;
import money.hejje.regime.Volatility;

/** Exposes the package-private rules to tests in the public package. */
public final class RegimeTestBridge {

    private RegimeTestBridge() {
    }

    public record State(double close, double emaFast, double emaSlow, double emaFastBack, double adx, double atr, double prevClose, double vix,
            Double vixPercentile, Double atrRatioPercentile, int window, int sessions) {
        DailyState toDaily(double open) {
            return new DailyState(LocalDate.of(2026, 9, 8), sessions, open, close, emaFast, emaSlow, emaFastBack, adx, atr, prevClose, vix, vixPercentile,
                    atrRatioPercentile, window);
        }
    }

    public record Member(String symbol, double prevClose, double last, Double vwap) {}

    public static Outcome<Trend> trend(State s, RegimeProperties p) {
        return out(RegimeRules.trend(s.toDaily(Double.NaN), p.trend()));
    }

    public static Outcome<Volatility> volatility(State s, RegimeProperties p) {
        return out(RegimeRules.volatility(s.toDaily(Double.NaN), p.volatility(), p.lookbackSessions()));
    }

    public static Outcome<Opening> opening(State s, double open, List<Bar> bars, RegimeProperties p) {
        return out(RegimeRules.opening(s.toDaily(open), new IntradayInput(bars, false), p.opening()));
    }

    public static Outcome<Breadth> breadth(int universe, List<Member> members, RegimeProperties p) {
        return out(RegimeRules.breadth(new BreadthInput(universe, members.stream().map(m -> new BreadthInput.Constituent(m.symbol(), m.prevClose(), m.last(),
                m.vwap())).toList()), p.breadth()));
    }

    public static Outcome<IntradayStructure> structure(List<Bar> bars, boolean closed, double atr, RegimeProperties p) {
        return out(RegimeRules.structure(new IntradayInput(bars, closed), atr, p.structure()));
    }

    private static <L> Outcome<L> out(Labelled<L> l) {
        return new Outcome<>(l.label(), l.evidence(), l.features());
    }
}
