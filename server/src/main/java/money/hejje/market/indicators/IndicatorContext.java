package money.hejje.market.indicators;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import money.hejje.common.Timeframe;
import money.hejje.market.Candle;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDefinition.RuleSet;
import money.hejje.strategy.dsl.BarContext;
import money.hejje.strategy.dsl.Condition;
import money.hejje.strategy.dsl.Expr;
import money.hejje.strategy.dsl.Expr.Arg;
import money.hejje.strategy.dsl.IndicatorCatalog;

/**
 * The indicator state for one (instrument, timeframe): price/volume series histories, a session tracker and every
 * registered indicator, all fed one closed candle at a time through {@link #onCandleClosed}. Implements the DSL's
 * {@link BarContext}, so conditions are evaluated against the bar that just closed. An indicator requested before it
 * was registered is registered on the spot and stays not ready until it has seen enough bars.
 */
public final class IndicatorContext implements BarContext {

    private final Timeframe timeframe;
    private final ZoneId zone;
    private final SessionTracker session = new SessionTracker();
    private final Map<String, History> series = new LinkedHashMap<>();
    private final Map<String, Indicator> indicators = new LinkedHashMap<>();
    private Bar last;
    private int bars;

    public IndicatorContext(Timeframe timeframe, ZoneId zone) {
        this.timeframe = timeframe;
        this.zone = zone;
        for (String name : Expr.SeriesRef.NAMES) {
            series.put(name, new History());
        }
    }

    public Timeframe timeframe() {
        return timeframe;
    }

    public SessionTracker session() {
        return session;
    }

    /** Registers one indicator (arguments as normalised by the parser). Idempotent. */
    public Indicator register(String name, List<Arg> args) {
        Expr.IndicatorCall normalized = IndicatorCatalog.normalize(new Expr.IndicatorCall(name, args, 0));
        return indicators.computeIfAbsent(normalized.text(), key -> IndicatorFactory.create(normalized.name(), normalized.args(), session));
    }

    /** Registers every indicator referenced by the definition's entry and exit rules. */
    public void registerDefinition(StrategyDefinition definition) {
        registerRules(definition.entry());
        if (definition.exit() != null) {
            registerRules(definition.exit());
        }
    }

    private void registerRules(RuleSet rules) {
        for (Condition condition : rules.conditions()) {
            walk(condition.lhs());
            walk(condition.rhs());
        }
    }

    private void walk(Expr expr) {
        switch (expr) {
            case Expr.IndicatorCall call -> register(call.name(), call.args());
            case Expr.Binary b -> {
                walk(b.left());
                walk(b.right());
            }
            case Expr.Negate n -> walk(n.operand());
            default -> {
            }
        }
    }

    public Set<String> registered() {
        return new TreeSet<>(indicators.keySet());
    }

    /** Feeds one closed candle. Candles must arrive in time order and match the context's timeframe. */
    public void onCandleClosed(Candle candle) {
        if (candle.timeframe() != timeframe) {
            throw new IllegalArgumentException("Context is " + timeframe + " but candle is " + candle.timeframe());
        }
        if (last != null && !candle.openTime().isAfter(last.openTime())) {
            throw new IllegalArgumentException("Candle " + candle.openTime() + " is not after the last bar " + last.openTime());
        }
        Bar bar = Bar.of(candle, zone);
        session.update(bar);
        series.get("open").push(bar.open());
        series.get("high").push(bar.high());
        series.get("low").push(bar.low());
        series.get("close").push(bar.close());
        series.get("volume").push(bar.volume());
        for (Indicator indicator : indicators.values()) {
            indicator.update(bar);
        }
        last = bar;
        bars++;
    }

    /** Feeds historical candles (oldest first) so indicators are ready when live bars start. */
    public void warmUp(List<Candle> candles) {
        for (Candle candle : candles) {
            onCandleClosed(candle);
        }
    }

    public Bar lastBar() {
        return last;
    }

    public int barCount() {
        return bars;
    }

    /** True when every registered indicator has a value for the last bar. */
    public boolean isReady() {
        return last != null && indicators.values().stream().allMatch(Indicator::isReady);
    }

    public Indicator indicator(String canonicalCall) {
        return indicators.get(canonicalCall);
    }

    @Override
    public OptionalDouble series(String name, int offset) {
        History history = series.get(name);
        return history == null ? OptionalDouble.empty() : history.get(offset);
    }

    @Override
    public OptionalDouble indicator(String name, List<Arg> args, int offset) {
        Expr.IndicatorCall normalized = IndicatorCatalog.normalize(new Expr.IndicatorCall(name, args, 0));
        Indicator indicator = indicators.get(normalized.text());
        if (indicator == null) {
            register(normalized.name(), normalized.args());
            return OptionalDouble.empty();
        }
        return indicator.value(offset);
    }
}
