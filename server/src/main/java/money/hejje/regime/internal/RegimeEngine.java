package money.hejje.regime.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.regime.EventEnvironmentSource;
import money.hejje.regime.RegimeProperties;
import money.hejje.regime.RegimeSnapshot;
import org.springframework.stereotype.Component;

/** Wires the loaders to the classifier for the live snapshot and the historical labelling walk. */
@Component
public class RegimeEngine {

    private final RegimeInputs inputs;
    private final RegimeClassifier classifier;
    private final EventEnvironmentSource environment;
    private final RegimeProperties props;
    private final HejjeClock clock;

    RegimeEngine(RegimeInputs inputs, EventEnvironmentSource environment, RegimeProperties props, HejjeClock clock) {
        this.inputs = inputs;
        this.classifier = new RegimeClassifier(props);
        this.environment = environment;
        this.props = props;
        this.clock = clock;
    }

    public Optional<UUID> indexId() {
        return inputs.indexId();
    }

    public List<Candle> indexDaily(LocalDate from, LocalDate to) {
        return inputs.indexId().map(id -> inputs.daily(id, from, to)).orElse(List.of());
    }

    /**
     * The snapshot of {@code date} as of {@code asOf}: daily history up to the session (a partial daily bar built
     * from the session's intraday bars stands in for a missing daily candle), intraday bars up to {@code asOf}, and
     * live breadth from constituent intraday bars.
     */
    public RegimeSnapshot snapshot(LocalDate date, Instant asOf) {
        Optional<UUID> index = inputs.indexId();
        if (index.isEmpty()) {
            return RegimeSnapshot.unknown(date, asOf, props.classifierVersion(), "index instrument " + props.indexSymbol() + " not in the instrument master");
        }
        IntradayInput intraday = inputs.intraday(index.get(), date, asOf);
        List<Candle> indexDaily = new ArrayList<>(inputs.daily(index.get(), date.minusDays(RegimeInputs.HISTORY_DAYS), date));
        appendPartial(indexDaily, index.get(), date, intraday);
        List<Candle> vixDaily = new ArrayList<>();
        Optional<UUID> vix = inputs.vixId();
        if (vix.isPresent()) {
            vixDaily.addAll(inputs.daily(vix.get(), date.minusDays(RegimeInputs.HISTORY_DAYS), date));
            appendPartial(vixDaily, vix.get(), date, inputs.intraday(vix.get(), date, asOf));
        }
        DailyState state = null;
        if (!indexDaily.isEmpty()) {
            List<DailyState> states = new DailyWalker(props, clock.zone(), vixDaily).walk(indexDaily);
            DailyState last = states.get(states.size() - 1);
            state = last.date().equals(date) ? last : withoutToday(last);
        }
        BreadthInput breadth = inputs.breadthIntraday(date, asOf);
        return classifier.classify(date, asOf, state, intraday, breadth, environment.environment(date), condition(date, asOf, intraday.sessionClosed(), indexDaily));
    }

    private record PreviousCondition(LocalDate date, Labelled<money.hejje.regime.MarketCondition> condition) {
    }

    private volatile PreviousCondition previousCondition;

    /**
     * The market condition is an end-of-day call: after the close it includes the session (constituent turnover from
     * intraday bars while the D1 candles are not stored yet); an intraday snapshot carries the previous session's, which
     * is computed once per session.
     */
    private Labelled<money.hejje.regime.MarketCondition> condition(LocalDate date, Instant asOf, boolean closed, List<Candle> indexDaily) {
        PreviousCondition cached = previousCondition;
        if (!closed && cached != null && cached.date().equals(date)) {
            return cached.condition();
        }
        TreeMap<LocalDate, double[]> turnover = inputs.constituentTurnover(date.minusDays(RegimeInputs.HISTORY_DAYS), date);
        if (closed) {
            if (!turnover.containsKey(date)) {
                turnover.put(date, inputs.constituentTurnoverIntraday(date, asOf));
            }
            return condition(indexDaily, indexDaily.size(), turnover);
        }
        List<Candle> before = indexDaily.stream().filter(c -> c.openTime().atZone(clock.zone()).toLocalDate().isBefore(date)).toList();
        Labelled<money.hejje.regime.MarketCondition> condition = condition(before, before.size(), turnover);
        previousCondition = new PreviousCondition(date, condition);
        return condition;
    }

    /** The market condition as of the {@code count}-th index bar: only bars up to it are read. */
    private Labelled<money.hejje.regime.MarketCondition> condition(List<Candle> indexDaily, int count, TreeMap<LocalDate, double[]> turnover) {
        RegimeProperties.ConditionRules rules = props.marketCondition();
        int from = Math.max(0, count - rules.windowSessions() - rules.sma());
        List<MarketConditionMachine.Day> days = new ArrayList<>(count - from);
        for (Candle c : indexDaily.subList(from, count)) {
            LocalDate d = c.openTime().atZone(clock.zone()).toLocalDate();
            double[] cell = turnover.get(d);
            days.add(new MarketConditionMachine.Day(d, c.low().doubleValue(), c.close().doubleValue(),
                    cell == null || cell[1] < rules.minConstituents() ? Double.NaN : cell[0]));
        }
        return MarketConditionMachine.evaluate(days, rules);
    }

    /** When the daily series ends before the session (no bars today), today's trend/volatility are those of the last bar but the previous close is its close. */
    private static DailyState withoutToday(DailyState last) {
        return new DailyState(last.date(), last.sessions(), Double.NaN, last.close(), last.emaFast(), last.emaSlow(), last.emaFastBack(), last.adx(),
                last.atr(), last.close(), last.vix(), last.vixPercentile(), last.atrRatioPercentile(), last.percentileWindow());
    }

    private void appendPartial(List<Candle> daily, UUID instrumentId, LocalDate date, IntradayInput intraday) {
        boolean hasToday = !daily.isEmpty() && daily.get(daily.size() - 1).openTime().atZone(clock.zone()).toLocalDate().equals(date);
        if (!hasToday) {
            Candle partial = RegimeInputs.partialDaily(instrumentId, date, clock.zone(), intraday.bars());
            if (partial != null) {
                daily.add(partial);
            }
        }
    }

    /** Final labels for every session in {@code [from, to]} with an index daily bar, in session order. */
    public List<RegimeSnapshot> labelRange(LocalDate from, LocalDate to) {
        Optional<UUID> index = inputs.indexId();
        if (index.isEmpty()) {
            return List.of();
        }
        List<Candle> indexDaily = new ArrayList<>(inputs.daily(index.get(), from.minusDays(RegimeInputs.HISTORY_DAYS), to));
        Map<LocalDate, IntradayInput> intradayByDate = new java.util.HashMap<>();
        fillMissingDaily(indexDaily, index.get(), from, to, intradayByDate);
        List<Candle> vixDaily = new ArrayList<>();
        Optional<UUID> vix = inputs.vixId();
        if (vix.isPresent()) {
            vixDaily.addAll(inputs.daily(vix.get(), from.minusDays(RegimeInputs.HISTORY_DAYS), to));
            fillMissingDaily(vixDaily, vix.get(), from, to, new java.util.HashMap<>());
        }
        Map<String, TreeMap<LocalDate, Double>> closes = inputs.constituentDailyCloses(from, to);
        TreeMap<LocalDate, double[]> turnover = inputs.constituentTurnover(from.minusDays(RegimeInputs.HISTORY_DAYS), to);
        List<RegimeSnapshot> out = new ArrayList<>();
        int position = 0;
        for (DailyState state : new DailyWalker(props, clock.zone(), vixDaily).walk(indexDaily)) {
            LocalDate date = state.date();
            position++;
            if (date.isBefore(from) || date.isAfter(to)) {
                continue;
            }
            Instant close = clock.sessionWindow(date).close().toInstant();
            IntradayInput intraday = intradayByDate.containsKey(date) ? intradayByDate.get(date) : inputs.intraday(index.get(), date, close);
            out.add(classifier.classify(date, close, state, intraday, inputs.breadthHistorical(date, closes), environment.environment(date),
                    condition(indexDaily, position, turnover)));
        }
        return out;
    }

    /**
     * Trading sessions in {@code [from, to]} without a stored daily candle get one built from their intraday bars (the
     * live path does the same for today), so a session is labelled as soon as its intraday data exists.
     */
    private void fillMissingDaily(List<Candle> daily, UUID instrumentId, LocalDate from, LocalDate to, Map<LocalDate, IntradayInput> intradayByDate) {
        java.util.Set<LocalDate> present = new java.util.HashSet<>();
        for (Candle c : daily) {
            present.add(c.openTime().atZone(clock.zone()).toLocalDate());
        }
        boolean added = false;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (present.contains(d) || !clock.isTradingDay(d)) {
                continue;
            }
            IntradayInput intraday = inputs.intraday(instrumentId, d, clock.sessionWindow(d).close().toInstant());
            intradayByDate.put(d, intraday);
            Candle partial = RegimeInputs.partialDaily(instrumentId, d, clock.zone(), intraday.bars());
            if (partial != null) {
                daily.add(partial);
                added = true;
            }
        }
        if (added) {
            daily.sort(java.util.Comparator.comparing(Candle::openTime));
        }
    }
}
