package money.hejje.strategy.dsl;

import java.util.List;
import java.util.OptionalDouble;
import money.hejje.strategy.dsl.Expr.Arg;

/**
 * What a condition can observe at one closed bar. Implemented by the indicator context (M2.2) for both the backtester
 * and the live engine. An empty value means "not available yet" (warm-up incomplete, not enough history) and makes the
 * condition {@code NOT_READY}; conditions never evaluate to true on missing data.
 */
public interface BarContext {

    /** Value of the {@code open/high/low/close/volume} series {@code offset} closed bars back (0 = the bar that just closed). */
    OptionalDouble series(String name, int offset);

    /** Value of an indicator (arguments already normalised by the parser) {@code offset} closed bars back. */
    OptionalDouble indicator(String name, List<Arg> args, int offset);
}
