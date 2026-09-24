package money.hejje.strategy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import money.hejje.strategy.dsl.Condition;
import money.hejje.strategy.dsl.Expr;

/**
 * A strategy definition in plain words, line by line (plan M4.6: an NL draft is reviewed as rules in words next to its
 * YAML). Deterministic and derived only from the parsed definition.
 */
public final class RuleWords {

    private RuleWords() {
    }

    public static List<String> describe(StrategyDefinition d) {
        List<String> out = new ArrayList<>();
        out.add(direction(d.direction()) + " on " + universe(d) + ", " + d.timeframe().duration().toMinutes() + "-minute bars.");
        rules(out, "Enter", d.entry());
        if (d.exit() != null && !d.exit().conditions().isEmpty()) {
            rules(out, "Exit early", d.exit());
        }
        out.add("Stop: " + stop(d.stop()) + ".");
        if (d.target() != null && d.target().type() != StrategyDefinition.TargetType.NONE) {
            out.add("Target: " + target(d.target()) + ".");
        }
        if (d.trailingStop() != null) {
            out.add("Trailing stop: " + trailing(d.trailingStop()) + ".");
        }
        String window = d.tradeWindow() == null ? "" : "New entries only between " + d.tradeWindow().start() + " and " + d.tradeWindow().end() + "; ";
        out.add(window + "force exit at " + d.forceExitTime() + ".");
        out.add("At most " + d.maxTradesPerDay() + (d.maxTradesPerDay() == 1 ? " trade" : " trades") + " a day"
                + (d.maxHoldingMinutes() == null ? "" : ", each held at most " + d.maxHoldingMinutes() + " minutes") + ".");
        if (d.positionSizing() != null && d.positionSizing().riskRupees() != null) {
            out.add("Risks " + d.positionSizing().riskRupees().toRupeesString() + " rupees a trade; the quantity is sized from the stop distance.");
        } else if (d.positionSizing() != null && d.positionSizing().riskPercentOfCapital() != null) {
            out.add("Risks " + plain(d.positionSizing().riskPercentOfCapital()) + "% of capital a trade; the quantity is sized from the stop distance.");
        } else {
            out.add("Risk per trade comes from the deployment; the quantity is sized from the stop distance.");
        }
        if (d.regimePreferences() != null && !d.regimePreferences().isEmpty()) {
            // preferred, then avoided, then neutral; regimes alphabetically, so the sentence never depends on map order
            Map<StrategyDefinition.RegimePreference, List<String>> byPref = new LinkedHashMap<>();
            for (StrategyDefinition.RegimePreference pref : List.of(StrategyDefinition.RegimePreference.PREFERRED, StrategyDefinition.RegimePreference.AVOID,
                    StrategyDefinition.RegimePreference.NEUTRAL)) {
                List<String> regimes = d.regimePreferences().entrySet().stream().filter(e -> e.getValue() == pref).map(Map.Entry::getKey).sorted().toList();
                if (!regimes.isEmpty()) {
                    byPref.put(pref, regimes);
                }
            }
            List<String> parts = new ArrayList<>();
            byPref.forEach((pref, regimes) -> parts.add(switch (pref) {
                case PREFERRED -> "prefers " + String.join(", ", regimes) + " sessions";
                case AVOID -> "avoids " + String.join(", ", regimes) + " sessions";
                case NEUTRAL -> "is neutral about " + String.join(", ", regimes) + " sessions";
            }));
            out.add(capitalize(String.join("; ", parts)) + ".");
        }
        if (d.eventRules() != null && d.eventRules().action() != null && d.eventRules().action() != StrategyDefinition.EventAction.ALLOW) {
            out.add(d.eventRules().action() == StrategyDefinition.EventAction.BLOCK
                    ? "No new entries within " + d.eventRules().highRiskEventWithinMinutes() + " minutes of a high-risk event."
                    : "Entries within " + d.eventRules().highRiskEventWithinMinutes() + " minutes of a high-risk event are flagged as trade with caution.");
        }
        return out;
    }

    private static void rules(List<String> out, String verb, StrategyDefinition.RuleSet rules) {
        out.add(verb + " when " + (rules.mode() == StrategyDefinition.RuleMode.ALL ? "all" : "any") + " of these hold at a bar close:");
        for (Condition c : rules.conditions()) {
            out.add("• " + condition(c));
        }
    }

    public static String condition(Condition c) {
        String op = switch (c.op().symbol()) {
            case ">" -> "is above";
            case "<" -> "is below";
            case ">=" -> "is at or above";
            case "<=" -> "is at or below";
            case "==" -> "equals";
            case "crosses_above" -> "crosses above";
            case "crosses_below" -> "crosses below";
            default -> c.op().symbol();
        };
        return words(c.lhs()) + " " + op + " " + words(c.rhs());
    }

    static String words(Expr e) {
        return switch (e) {
            case Expr.NumberLiteral n -> n.text();
            case Expr.SeriesRef s -> ago(switch (s.name()) {
                case "volume" -> "volume";
                default -> "the " + s.name();
            }, s.offset());
            case Expr.IndicatorCall c -> ago(indicator(c), c.offset());
            case Expr.Binary b -> words(b.left()) + " " + switch (b.op()) {
                case ADD -> "+";
                case SUB -> "−";
                case MUL -> "×";
                case DIV -> "÷";
            } + " " + words(b.right());
            case Expr.Negate n -> "minus " + words(n.operand());
        };
    }

    private static String indicator(Expr.IndicatorCall c) {
        String a0 = c.args().isEmpty() ? "" : arg(c.args().get(0));
        String a1 = c.args().size() < 2 ? "" : arg(c.args().get(1));
        return switch (c.name()) {
            case "sma", "ema" -> "the " + a0 + "-bar " + c.name().toUpperCase();
            case "rsi", "atr", "adx" -> c.name().toUpperCase() + "(" + a0 + ")";
            case "bb_upper" -> "the upper Bollinger band (" + a0 + ", " + a1 + ")";
            case "bb_lower" -> "the lower Bollinger band (" + a0 + ", " + a1 + ")";
            case "vwap" -> "VWAP";
            case "opening_range_high" -> "the " + (a0.isEmpty() ? "15-minute" : a0) + " opening-range high";
            case "opening_range_low" -> "the " + (a0.isEmpty() ? "15-minute" : a0) + " opening-range low";
            case "relative_volume" -> "relative volume (" + (a0.isEmpty() ? "20" : a0) + " sessions)";
            case "book_imbalance" -> "the order-book imbalance at the bar's close";
            case "book_imbalance_mean" -> "the bar's mean order-book imbalance";
            case "buy_sell_qty_ratio" -> "the day's buy/sell quantity ratio";
            case "flow_up_share" -> "the up-volume share of the last " + (a0.isEmpty() ? "5" : a0) + " bars";
            case "prev_day_high" -> "the previous day's high";
            case "prev_day_low" -> "the previous day's low";
            case "prev_day_close" -> "the previous day's close";
            case "gap_pct" -> "the opening gap %";
            case "session_minutes" -> "minutes since the open";
            case "session_open" -> "today's open";
            case "session_high" -> "today's high so far";
            case "session_low" -> "today's low so far";
            case "pivot" -> "the pivot";
            case "cpr_top" -> "the top of the central pivot range";
            case "cpr_bottom" -> "the bottom of the central pivot range";
            case "cpr_width_pct" -> "the CPR width %";
            case "supertrend" -> "Supertrend(" + a0 + ", " + a1 + ")";
            case "prev_day_nr" -> "the previous day's NR" + a0 + " flag (1 when it had the narrowest range of the last " + a0 + " sessions)";
            case "opening_return" -> "the % return from the previous close to the close " + a0.replace("-", " ") + "s after the open";
            case "highest" -> "the highest high of the last " + a0 + " bars";
            case "lowest" -> "the lowest low of the last " + a0 + " bars";
            default -> c.text();
        };
    }

    private static String arg(Expr.Arg a) {
        return switch (a) {
            case Expr.Arg.Number n -> n.text();
            case Expr.Arg.DurationArg d -> d.value().toMinutes() % 60 == 0 && d.value().toMinutes() >= 60 ? d.value().toHours() + "-hour"
                    : d.value().toMinutes() + "-minute";
        };
    }

    private static String ago(String what, int offset) {
        return offset == 0 ? what : offset == 1 ? what + " one bar earlier" : what + " " + offset + " bars earlier";
    }

    private static String direction(StrategyDefinition.Direction d) {
        return switch (d) {
            case LONG -> "Long";
            case SHORT -> "Short";
            case BOTH -> "Long or short";
            case NEUTRAL -> "Neutral (no side on the underlying)";
        };
    }

    private static String universe(StrategyDefinition d) {
        List<String> parts = new ArrayList<>();
        for (StrategyDefinition.UniverseEntry u : d.universe()) {
            parts.add(switch (u.kind()) {
                case NEAREST_FUTURE -> "the nearest " + u.value() + " future";
                case INDEX -> u.value() + " (index)";
                default -> u.value();
            });
        }
        return String.join(", ", parts);
    }

    private static String stop(StrategyDefinition.StopSpec s) {
        if (s == null) {
            return "none";
        }
        return switch (s.type()) {
            case OPENING_RANGE_LOW -> "below the opening-range low";
            case OPENING_RANGE_HIGH -> "above the opening-range high";
            case ATR_MULTIPLE -> plain(s.value()) + " × ATR(14) from the entry";
            case PERCENT -> plain(s.value()) + "% from the entry";
            case POINTS -> plain(s.value()) + " points from the entry";
            case SWING_LOW -> "below the recent swing low (lowest low of 10 bars)";
            case SWING_HIGH -> "above the recent swing high (highest high of 10 bars)";
            case PREV_DAY_LOW -> "below the previous day's low";
            case PREV_DAY_HIGH -> "above the previous day's high";
        };
    }

    private static String target(StrategyDefinition.TargetSpec t) {
        return switch (t.type()) {
            case RISK_MULTIPLE -> plain(t.value()) + "R (" + plain(t.value()) + " × the risk)";
            case POINTS -> plain(t.value()) + " points";
            case PERCENT -> plain(t.value()) + "%";
            case VWAP -> "at VWAP";
            case NONE -> "none";
        };
    }

    private static String trailing(StrategyDefinition.TrailingStopSpec t) {
        return switch (t.type()) {
            case ATR_MULTIPLE -> plain(t.value()) + " × ATR";
            case PERCENT -> plain(t.value()) + "%";
            case BREAKEVEN_AT_R -> "move the stop to break-even at " + plain(t.value()) + "R";
        };
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
