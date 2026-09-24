package money.hejje.strategy.dsl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import money.hejje.strategy.dsl.Expr.Arg;
import money.hejje.strategy.dsl.Expr.IndicatorCall;

/**
 * The indicators a condition may reference, their arities and defaults (docs/strategy-dsl.md). The implementations
 * live in {@code market.indicators} (M2.2); this catalogue only fixes names and argument shapes so that parsing and
 * validation do not depend on the market module.
 */
public final class IndicatorCatalog {

    /** Argument kinds an indicator accepts, in order. */
    public enum ArgKind { INT, NUMBER, DURATION }

    /**
     * @param name     indicator name as written in conditions
     * @param kinds    kinds of the arguments, in order
     * @param defaults default values for trailing arguments that may be omitted; shorter than {@code kinds} when
     *                 leading arguments are mandatory
     */
    public record Spec(String name, List<ArgKind> kinds, List<Arg> defaults) {
        public int minArgs() {
            return kinds.size() - defaults.size();
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        period("sma");
        period("ema");
        period("rsi");
        period("atr");
        period("adx");
        period("highest");
        period("lowest");
        add("bb_upper", List.of(ArgKind.INT, ArgKind.NUMBER), List.of());
        add("bb_lower", List.of(ArgKind.INT, ArgKind.NUMBER), List.of());
        add("vwap", List.of(), List.of());
        add("opening_range_high", List.of(ArgKind.DURATION), List.of(new Arg.DurationArg(Duration.ofMinutes(15))));
        add("opening_range_low", List.of(ArgKind.DURATION), List.of(new Arg.DurationArg(Duration.ofMinutes(15))));
        add("relative_volume", List.of(ArgKind.INT), List.of(new Arg.Number(20)));
        add("prev_day_high", List.of(), List.of());
        add("prev_day_low", List.of(), List.of());
        add("prev_day_close", List.of(), List.of());
        add("gap_pct", List.of(), List.of());
        add("session_minutes", List.of(), List.of());
        add("session_open", List.of(), List.of());
        add("session_high", List.of(), List.of());
        add("session_low", List.of(), List.of());
        add("pivot", List.of(), List.of());
        add("cpr_top", List.of(), List.of());
        add("cpr_bottom", List.of(), List.of());
        add("cpr_width_pct", List.of(), List.of());
        add("supertrend", List.of(ArgKind.INT, ArgKind.NUMBER), List.of());
        add("prev_day_nr", List.of(ArgKind.INT), List.of());
        add("opening_return", List.of(ArgKind.DURATION), List.of());
        // plan M9.4: order-book and trade-flow, live or recorded ticks only (NOT_READY on candle history)
        add("book_imbalance", List.of(), List.of());
        add("book_imbalance_mean", List.of(), List.of());
        add("buy_sell_qty_ratio", List.of(), List.of());
        add("flow_up_share", List.of(ArgKind.INT), List.of(new Arg.Number(5)));
    }

    /** Indicators that need order-book or tick-flow data, which candle history does not have (plan M9.4). */
    public static final Set<String> MICROSTRUCTURE = Set.of("book_imbalance", "book_imbalance_mean", "buy_sell_qty_ratio", "flow_up_share");

    private IndicatorCatalog() {
    }

    private static void period(String name) {
        add(name, List.of(ArgKind.INT), List.of());
    }

    private static void add(String name, List<ArgKind> kinds, List<Arg> defaults) {
        SPECS.put(name, new Spec(name, kinds, defaults));
    }

    public static Set<String> names() {
        return SPECS.keySet();
    }

    public static Spec spec(String name) {
        return SPECS.get(name);
    }

    /**
     * Checks the call against the catalogue and fills in omitted trailing arguments.
     *
     * @throws IllegalArgumentException with a human-readable reason on an unknown indicator or a bad argument
     */
    public static IndicatorCall normalize(IndicatorCall call) {
        Spec spec = SPECS.get(call.name());
        if (spec == null) {
            throw new IllegalArgumentException("Unknown indicator '" + call.name() + "'");
        }
        int given = call.args().size();
        if (given < spec.minArgs() || given > spec.kinds().size()) {
            throw new IllegalArgumentException("'" + call.name() + "' takes " + arity(spec) + " argument(s) but got " + given);
        }
        List<Arg> args = new ArrayList<>(call.args());
        for (int i = given; i < spec.kinds().size(); i++) {
            args.add(spec.defaults().get(i - spec.minArgs()));
        }
        for (int i = 0; i < args.size(); i++) {
            Arg arg = args.get(i);
            ArgKind kind = spec.kinds().get(i);
            switch (kind) {
                case INT -> {
                    if (!(arg instanceof Arg.Number n) || n.value() != Math.rint(n.value()) || n.value() < 1) {
                        throw new IllegalArgumentException("Argument " + (i + 1) + " of '" + call.name() + "' must be a positive whole number");
                    }
                }
                case NUMBER -> {
                    if (!(arg instanceof Arg.Number n) || n.value() <= 0) {
                        throw new IllegalArgumentException("Argument " + (i + 1) + " of '" + call.name() + "' must be a positive number");
                    }
                }
                case DURATION -> {
                    if (!(arg instanceof Arg.DurationArg d) || d.value().isZero() || d.value().isNegative()) {
                        throw new IllegalArgumentException("Argument " + (i + 1) + " of '" + call.name() + "' must be a duration such as 15m");
                    }
                }
            }
        }
        return new IndicatorCall(call.name(), args, call.offset());
    }

    private static String arity(Spec spec) {
        int min = spec.minArgs();
        int max = spec.kinds().size();
        return min == max ? Integer.toString(min) : min + " to " + max;
    }
}
