package money.hejje.regime.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import money.hejje.regime.Breadth;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.IntradayStructure;
import money.hejje.regime.Opening;
import money.hejje.regime.RegimeProperties;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.regime.Trend;
import money.hejje.regime.Volatility;

/** Composes the per-dimension rules into one snapshot. Pure and deterministic. */
final class RegimeClassifier {

    private final RegimeProperties props;

    RegimeClassifier(RegimeProperties props) {
        this.props = props;
    }

    RegimeSnapshot classify(LocalDate date, Instant asOf, DailyState daily, IntradayInput intraday, BreadthInput breadth, EventEnvironment environment) {
        Labelled<Trend> trend = RegimeRules.trend(daily, props.trend());
        Labelled<Volatility> volatility = RegimeRules.volatility(daily, props.volatility(), props.lookbackSessions());
        Labelled<Opening> opening = RegimeRules.opening(daily, intraday, props.opening());
        Labelled<Breadth> breadthLabel = RegimeRules.breadth(breadth, props.breadth());
        Labelled<IntradayStructure> structure = RegimeRules.structure(intraday, daily == null ? Double.NaN : daily.atr(), props.structure());
        Map<String, Object> features = new LinkedHashMap<>();
        List<String> evidence = new ArrayList<>();
        for (Labelled<?> l : List.of(trend, volatility, opening, breadthLabel, structure)) {
            features.putAll(l.features());
            evidence.addAll(l.evidence());
        }
        evidence.add("Event environment " + environment);
        return new RegimeSnapshot(date, asOf, trend.label(), volatility.label(), opening.label(), breadthLabel.label(), structure.label(), environment,
                features, evidence, props.classifierVersion(), intraday.sessionClosed());
    }
}
