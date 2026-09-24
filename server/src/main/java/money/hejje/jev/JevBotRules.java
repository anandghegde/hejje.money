package money.hejje.jev;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import money.hejje.llm.JevAnswer;
import money.hejje.llm.JevResult;

/**
 * What the Jev bot does with Jev's answers (plan M9.5, docs/jev.md "The Jev bot"). Pure; thresholds come from the
 * question sets' {@code params}, so changing one bumps the set's version.
 */
public final class JevBotRules {

    private JevBotRules() {
    }

    /** A stage-1 candidate: the symbol, its side and P(side). */
    public record Candidate(String symbol, boolean longSide, double p) {}

    /**
     * Stage 1: per side the top {@code top-per-side} symbols at P ≥ {@code min-prob}; shorts are dropped in a
     * {@code trend_up} market and longs in a {@code trend_down} one. {@code symbols.get(i)} was asked as long_i / short_i.
     */
    public static List<Candidate> stage1(JevResult r, List<String> symbols, JsonNode params) {
        double min = params.path("min-prob").asDouble(0.4);
        int top = params.path("top-per-side").asInt(3);
        String regime = r.answer("regime").map(JevAnswer::choice).orElse("");
        List<Candidate> longs = new ArrayList<>();
        List<Candidate> shorts = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) {
            double pl = r.noul("long_" + i, 0);
            double ps = r.noul("short_" + i, 0);
            if (pl >= min && !regime.equals("trend_down")) {
                longs.add(new Candidate(symbols.get(i), true, pl));
            }
            if (ps >= min && !regime.equals("trend_up")) {
                shorts.add(new Candidate(symbols.get(i), false, ps));
            }
        }
        Comparator<Candidate> byP = Comparator.comparingDouble(Candidate::p).reversed().thenComparing(Candidate::symbol);
        List<Candidate> out = new ArrayList<>(longs.stream().sorted(byP).limit(top).toList());
        out.addAll(shorts.stream().sorted(byP).limit(top).toList());
        return out;
    }

    /** The stage-2 reading of one candidate. {@code enter} when every entry condition holds; {@code reason} otherwise. */
    public record Entry(boolean enter, double pSetup, Double confidence, double composite, Map<String, Double> scores, String setup, String reason) {}

    /**
     * Stage 2: enter when the setup matches the side ({@code long_continuation} / {@code short_continuation}), P(that
     * setup) ≥ {@code min-setup-prob}, its confidence ≥ {@code min-confidence}, the weighted mean of the asked scores ≥
     * {@code min-composite}, and no asked score is under {@code min-score}. Scores are level / (levels − 1).
     */
    public static Entry stage2(JevResult r, boolean longSide, Map<String, Integer> levels, JsonNode params) {
        String wanted = longSide ? "long_continuation" : "short_continuation";
        JevAnswer setup = r.answer("setup").orElse(null);
        double p = setup == null ? 0 : setup.probabilityOf(wanted);
        Double confidence = setup == null ? null : setup.confidence();
        Map<String, Double> scores = new LinkedHashMap<>();
        double weighted = 0;
        double weights = 0;
        JsonNode w = params.path("weights");
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            JevAnswer a = r.answer(e.getKey()).orElse(null);
            if (a == null || a.score() == null || e.getValue() < 2) {
                continue;
            }
            double s = a.score() / (e.getValue() - 1);
            scores.put(e.getKey(), round(s));
            double weight = w.path(e.getKey()).asDouble(0);
            weighted += weight * s;
            weights += weight;
        }
        double composite = weights == 0 ? 0 : weighted / weights;
        String reason = null;
        if (setup == null || !wanted.equals(setup.choice())) {
            reason = "setup " + (setup == null ? "missing" : setup.choice()) + ", not " + wanted;
        } else if (p < params.path("min-setup-prob").asDouble(0.55)) {
            reason = "P(setup) " + round(p) + " under " + params.path("min-setup-prob").asDouble(0.55);
        } else if (confidence == null || confidence < params.path("min-confidence").asDouble(0.4)) {
            reason = "confidence " + confidence + " under " + params.path("min-confidence").asDouble(0.4);
        } else if (composite < params.path("min-composite").asDouble(0.5)) {
            reason = "composite " + round(composite) + " under " + params.path("min-composite").asDouble(0.5);
        } else {
            double min = params.path("min-score").asDouble(0.25);
            for (Map.Entry<String, Double> s : scores.entrySet()) {
                if (s.getValue() < min) {
                    reason = s.getKey() + " " + s.getValue() + " under " + min;
                    break;
                }
            }
        }
        return new Entry(reason == null, round(p), confidence, round(composite), scores, setup == null ? null : setup.choice(), reason);
    }

    /** What to do with an open position. */
    public enum PositionAction { EXIT, TAKE_PROFIT, MOVE_STOP_TO_ENTRY, HOLD }

    public record PositionCall(PositionAction action, String reason) {}

    /**
     * Position rules in order: EXIT when thesis < {@code exit-thesis-below} or exit_now ≥ {@code exit-now-prob};
     * TAKE_PROFIT at take_profit ≥ {@code take-profit-prob}; the stop to entry when thesis < {@code break-even-thesis-below},
     * the position is up ≥ {@code break-even-after-r} R and the stop is not there yet; EXIT after
     * {@code time-stop-minutes} when not in profit; else HOLD.
     */
    public static PositionCall position(JevResult r, JevBotState.PositionFacts facts, JsonNode params) {
        double thesis = r.answer("thesis").map(JevAnswer::score).orElse(Double.NaN);
        double exitNow = r.noul("exit_now", 0);
        double takeProfit = r.noul("take_profit", 0);
        if (thesis < params.path("exit-thesis-below").asDouble(1)) {
            return new PositionCall(PositionAction.EXIT, "thesis " + round(thesis) + " (broken)");
        }
        if (exitNow >= params.path("exit-now-prob").asDouble(0.7)) {
            return new PositionCall(PositionAction.EXIT, "exit_now " + round(exitNow));
        }
        if (takeProfit >= params.path("take-profit-prob").asDouble(0.7)) {
            return new PositionCall(PositionAction.TAKE_PROFIT, "take_profit " + round(takeProfit));
        }
        if (thesis < params.path("break-even-thesis-below").asDouble(1.5) && facts.r() >= params.path("break-even-after-r").asDouble(0.5)
                && !facts.stopAtBreakEven()) {
            return new PositionCall(PositionAction.MOVE_STOP_TO_ENTRY, "thesis " + round(thesis) + " weakening at " + round(facts.r()) + "R");
        }
        if (facts.minutesHeld() >= params.path("time-stop-minutes").asInt(30) && facts.unrealisedBps() <= 0) {
            return new PositionCall(PositionAction.EXIT, "time stop: " + facts.minutesHeld() + " minutes without profit");
        }
        return new PositionCall(PositionAction.HOLD, "thesis " + round(thesis));
    }

    static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : v;
    }
}
