package money.hejje.market.indicators;

import java.time.Duration;
import java.util.List;
import money.hejje.strategy.dsl.Expr.Arg;
import money.hejje.strategy.dsl.IndicatorCatalog;

/** Builds indicator instances for the names in the {@link IndicatorCatalog}. */
final class IndicatorFactory {

    private IndicatorFactory() {
    }

    static Indicator create(String name, List<Arg> args, SessionTracker session) {
        return switch (name) {
            case "sma" -> new Sma(intArg(args, 0));
            case "ema" -> new Ema(intArg(args, 0));
            case "rsi" -> new Rsi(intArg(args, 0));
            case "atr" -> new Atr(intArg(args, 0));
            case "adx" -> new Adx(intArg(args, 0));
            case "highest" -> new Extreme(intArg(args, 0), true);
            case "lowest" -> new Extreme(intArg(args, 0), false);
            case "bb_upper" -> new Bollinger(intArg(args, 0), numArg(args, 1), true);
            case "bb_lower" -> new Bollinger(intArg(args, 0), numArg(args, 1), false);
            case "vwap" -> new SessionVwap();
            case "opening_range_high" -> new OpeningRange(durationArg(args, 0), true);
            case "opening_range_low" -> new OpeningRange(durationArg(args, 0), false);
            case "relative_volume" -> new RelativeVolume(intArg(args, 0));
            case "prev_day_high" -> SessionIndicators.prevDayHigh(session);
            case "prev_day_low" -> SessionIndicators.prevDayLow(session);
            case "prev_day_close" -> SessionIndicators.prevDayClose(session);
            case "gap_pct" -> SessionIndicators.gapPct(session);
            case "session_minutes" -> SessionIndicators.sessionMinutes();
            case "session_open" -> SessionIndicators.sessionOpen(session);
            case "session_high" -> SessionIndicators.sessionHigh(session);
            case "session_low" -> SessionIndicators.sessionLow(session);
            case "pivot" -> SessionIndicators.pivot(session);
            case "cpr_top" -> SessionIndicators.cprTop(session);
            case "cpr_bottom" -> SessionIndicators.cprBottom(session);
            case "cpr_width_pct" -> SessionIndicators.cprWidthPct(session);
            case "supertrend" -> new Supertrend(intArg(args, 0), numArg(args, 1));
            case "prev_day_nr" -> SessionIndicators.prevDayNr(session, intArg(args, 0));
            case "opening_return" -> new OpeningReturn(durationArg(args, 0), session);
            default -> throw new IllegalArgumentException("Unknown indicator '" + name + "'");
        };
    }

    private static int intArg(List<Arg> args, int i) {
        return (int) numArg(args, i);
    }

    private static double numArg(List<Arg> args, int i) {
        if (i >= args.size() || !(args.get(i) instanceof Arg.Number n)) {
            throw new IllegalArgumentException("Argument " + (i + 1) + " must be a number");
        }
        return n.value();
    }

    private static Duration durationArg(List<Arg> args, int i) {
        if (i >= args.size() || !(args.get(i) instanceof Arg.DurationArg d)) {
            throw new IllegalArgumentException("Argument " + (i + 1) + " must be a duration");
        }
        return d.value();
    }
}
