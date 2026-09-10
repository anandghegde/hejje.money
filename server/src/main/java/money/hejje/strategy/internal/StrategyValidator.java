package money.hejje.strategy.internal;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import money.hejje.common.Product;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDefinition.Direction;
import money.hejje.strategy.StrategyDefinition.RuleSet;
import money.hejje.strategy.StrategyDefinition.StopType;
import money.hejje.strategy.StrategyDefinition.TargetType;
import money.hejje.strategy.StrategyDefinition.UniverseKind;
import money.hejje.strategy.StrategyProperties;
import money.hejje.strategy.ValidationError;
import money.hejje.strategy.dsl.Condition;
import money.hejje.strategy.dsl.Expr;
import money.hejje.strategy.dsl.Expr.Arg;
import org.springframework.stereotype.Component;

/**
 * Semantic validation of a structurally valid definition (docs/strategy-dsl.md, "Validation"): opening-range specs
 * compatible with the timeframe, trade window inside the session, MIS force exit before 15:20, directional stops that
 * match the direction, known universe aliases, and so on.
 */
@Component
public class StrategyValidator {

    static final LocalTime MIS_FORCE_EXIT_LIMIT = LocalTime.of(15, 20);

    private final StrategyProperties properties;

    StrategyValidator(StrategyProperties properties) {
        this.properties = properties;
    }

    public List<ValidationError> validate(StrategyDefinition d) {
        List<ValidationError> errors = new ArrayList<>();
        Timeframe tf = d.timeframe();

        // trade window inside the session, start before end
        LocalTime start = d.tradeWindow().start();
        LocalTime end = d.tradeWindow().end();
        if (start != null && start.isBefore(HejjeClock.SESSION_OPEN)) {
            errors.add(new ValidationError("trade_window.start", "must not be before session open 09:15"));
        }
        if (end != null && end.isAfter(HejjeClock.SESSION_CLOSE)) {
            errors.add(new ValidationError("trade_window.end", "must not be after session close 15:30"));
        }
        if (start != null && end != null && !start.isBefore(end)) {
            errors.add(new ValidationError("trade_window", "start must be before end"));
        }

        // force exit
        LocalTime forceExit = d.forceExitTime();
        if (forceExit != null) {
            if (d.product() == Product.MIS && !forceExit.isBefore(MIS_FORCE_EXIT_LIMIT)) {
                errors.add(new ValidationError("force_exit_time", "must be before 15:20 for MIS (broker auto square-off)"));
            }
            if (forceExit.isAfter(HejjeClock.SESSION_CLOSE) || !forceExit.isAfter(HejjeClock.SESSION_OPEN)) {
                errors.add(new ValidationError("force_exit_time", "must be inside the session"));
            }
            if (end != null && end.isAfter(forceExit)) {
                errors.add(new ValidationError("trade_window.end", "must not be after force_exit_time"));
            }
        }

        if (d.maxTradesPerDay() < 1) {
            errors.add(new ValidationError("max_trades_per_day", "must be at least 1"));
        }
        if (d.maxHoldingMinutes() != null && d.maxHoldingMinutes() < 1) {
            errors.add(new ValidationError("max_holding_minutes", "must be at least 1"));
        }
        if (d.signalValidityMinutes() != null && d.signalValidityMinutes() < 1) {
            errors.add(new ValidationError("signal_validity_minutes", "must be at least 1"));
        }

        // stop / direction compatibility
        StopType stop = d.stop().type();
        if (stop != null && stop.isDirectional()) {
            if (d.direction() == Direction.BOTH) {
                errors.add(new ValidationError("stop.type", stop.name().toLowerCase() + " is one-sided; direction both needs atr_multiple, percent or points"));
            } else if (d.direction() == Direction.LONG && !stop.isLongSide()) {
                errors.add(new ValidationError("stop.type", stop.name().toLowerCase() + " is a short stop but direction is long"));
            } else if (d.direction() == Direction.SHORT && stop.isLongSide()) {
                errors.add(new ValidationError("stop.type", stop.name().toLowerCase() + " is a long stop but direction is short"));
            }
        }
        if (d.target().type() == TargetType.RISK_MULTIPLE && d.riskOverrides().minRewardRisk() != null
                && d.target().value().compareTo(d.riskOverrides().minRewardRisk()) < 0) {
            errors.add(new ValidationError("target.value", "risk multiple is below risk_overrides.min_reward_risk"));
        }

        // timeframe compatibility with opening-range references
        if (tf != null) {
            if (tf == Timeframe.D1) {
                errors.add(new ValidationError("timeframe", "intraday strategies need an intraday timeframe"));
            }
            checkRules("entry", d.entry(), tf, errors);
            if (d.exit() != null) {
                checkRules("exit", d.exit(), tf, errors);
            }
            if (stop == StopType.OPENING_RANGE_LOW || stop == StopType.OPENING_RANGE_HIGH) {
                // the stop uses the default 15-minute opening range; the timeframe must divide it
                checkOpeningRange("stop.type", Duration.ofMinutes(15), tf, errors);
            }
        }

        // options legs (plan M5.4)
        if (!d.legs().isEmpty() && d.family() != money.hejje.strategy.StrategyFamily.OPTIONS) {
            errors.add(new ValidationError("legs", "legs are for options strategies (family: options)"));
        }
        if (d.family() == money.hejje.strategy.StrategyFamily.OPTIONS && d.legs().isEmpty()) {
            errors.add(new ValidationError("legs", "an options strategy needs legs: the options it trades on each signal"));
        }
        if (d.combinedExit() != null && d.legs().isEmpty()) {
            errors.add(new ValidationError("combined_exit", "needs legs"));
        }

        // universe aliases must be known
        for (int i = 0; i < d.universe().size(); i++) {
            var entry = d.universe().get(i);
            if (entry.kind() == UniverseKind.ALIAS && !properties.aliases().containsKey(entry.value())) {
                errors.add(new ValidationError("universe[" + i + "]", "unknown alias '" + entry.value()
                        + "'; use a canonical symbol such as NSE:INFY, INDEX:NIFTY 50 or a selector (nearest_future / index)"));
            }
        }
        return errors;
    }

    private void checkRules(String path, RuleSet rules, Timeframe tf, List<ValidationError> errors) {
        String key = rules.mode().name().toLowerCase();
        for (int i = 0; i < rules.conditions().size(); i++) {
            Condition c = rules.conditions().get(i);
            String cpath = path + "." + key + "[" + i + "]";
            walk(c.lhs(), cpath, tf, errors);
            walk(c.rhs(), cpath, tf, errors);
        }
    }

    private void walk(Expr expr, String path, Timeframe tf, List<ValidationError> errors) {
        switch (expr) {
            case Expr.IndicatorCall call -> {
                if (call.name().startsWith("opening_range_")) {
                    Duration range = ((Arg.DurationArg) call.args().get(0)).value();
                    checkOpeningRange(path, range, tf, errors);
                }
            }
            case Expr.Binary b -> {
                walk(b.left(), path, tf, errors);
                walk(b.right(), path, tf, errors);
            }
            case Expr.Negate n -> walk(n.operand(), path, tf, errors);
            default -> {
            }
        }
    }

    private static void checkOpeningRange(String path, Duration range, Timeframe tf, List<ValidationError> errors) {
        long tfMinutes = tf.duration().toMinutes();
        long rangeMinutes = range.toMinutes();
        if (tf == Timeframe.D1 || rangeMinutes % tfMinutes != 0) {
            errors.add(new ValidationError(path, "opening range of " + rangeMinutes + "m is not a whole number of " + tfMinutes + "m bars"));
        }
    }
}
