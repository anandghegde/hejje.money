package money.hejje.regime;

import java.util.ArrayList;
import java.util.List;
import money.hejje.market.indicators.Bar;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Test-side access to the internal rules and default thresholds. */
public final class RegimeTestSupport {

    private RegimeTestSupport() {
    }

    /** {@link RegimeProperties} bound from an empty source, i.e. the code defaults (identical to config/regime.yaml). */
    public static RegimeProperties defaults() {
        return new Binder(new MapConfigurationPropertySource(java.util.Map.of())).bindOrCreate("hejje.regime", RegimeProperties.class);
    }

    public record Outcome<L>(L label, List<String> evidence, java.util.Map<String, Object> features) {}

    public static Outcome<Trend> trend(double close, double emaFast, double emaSlow, double emaFastBack, double adx) {
        var s = new money.hejje.regime.internal.RegimeTestBridge.State(close, emaFast, emaSlow, emaFastBack, adx, Double.NaN, Double.NaN, Double.NaN, null, null, 0,
                Double.isNaN(emaSlow) ? 10 : 100);
        return money.hejje.regime.internal.RegimeTestBridge.trend(s, defaults());
    }

    public static Outcome<Volatility> volatility(Double vixPct, Double atrPct) {
        var s = new money.hejje.regime.internal.RegimeTestBridge.State(20_000, 20_000, 20_000, 20_000, 20, 160, 19_900, 13.5, vixPct, atrPct, 250, 250);
        return money.hejje.regime.internal.RegimeTestBridge.volatility(s, defaults());
    }

    public static Outcome<Opening> opening(double prevClose, double open, List<Bar> bars) {
        var s = new money.hejje.regime.internal.RegimeTestBridge.State(open, 20_000, 20_000, 20_000, 20, 160, prevClose, 13.5, 50.0, 50.0, 250, 250);
        return money.hejje.regime.internal.RegimeTestBridge.opening(s, open, bars, defaults());
    }

    /** {@code advances}/{@code declines} constituents of {@code universe}; {@code aboveVwap} of the counted ones carry a VWAP (0 = no VWAP data). */
    public static Outcome<Breadth> breadth(int universe, int advances, int declines, int aboveVwap) {
        List<money.hejje.regime.internal.RegimeTestBridge.Member> members = new ArrayList<>();
        int counted = advances + declines;
        for (int i = 0; i < counted; i++) {
            boolean up = i < advances;
            Double vwap = aboveVwap == 0 ? null : (i < aboveVwap ? 99.0 : 101.5);
            members.add(new money.hejje.regime.internal.RegimeTestBridge.Member("S" + i, 100.0, up ? 101.0 : 99.0, vwap));
        }
        return money.hejje.regime.internal.RegimeTestBridge.breadth(universe, members, defaults());
    }

    public static Outcome<IntradayStructure> structure(List<Bar> bars, double dailyAtr, boolean closed) {
        return money.hejje.regime.internal.RegimeTestBridge.structure(bars, closed, dailyAtr, defaults());
    }
}
