package money.hejje.recommend;

import java.util.List;

/**
 * The PRD 15 decision table (docs/decisions.md), pure so it can be tested exhaustively:
 * <ol>
 * <li>any hard block (risk rejection, event block, kill switch, readiness, sizing) → AVOID</li>
 * <li>no active signal, or the only failing control is the trading window → WAIT</li>
 * <li>no score, or score below the threshold → WAIT</li>
 * <li>score at or above the threshold: TRADE, or TRADE WITH CAUTION when any caution stands</li>
 * </ol>
 */
public final class DecisionRules {

    private DecisionRules() {
    }

    /**
     * @param signalActive      an unexpired signal exists
     * @param hardBlocks        failing controls (names + messages) other than the trading window
     * @param outsideWindow     the trading-window control failed (signal exists but timing is poor)
     * @param score             latest Hejje Score, null when never scored
     * @param minScore          {@code hejje.recommend.min-score}
     * @param cautions          standing cautions
     */
    public static Decision decide(boolean signalActive, List<String> hardBlocks, boolean outsideWindow, Integer score, int minScore, List<Caution> cautions) {
        if (signalActive && !hardBlocks.isEmpty()) {
            return Decision.AVOID;
        }
        if (!signalActive || outsideWindow) {
            return Decision.WAIT;
        }
        if (score == null || score < minScore) {
            return Decision.WAIT;
        }
        return cautions.isEmpty() ? Decision.TRADE : Decision.TRADE_WITH_CAUTION;
    }
}
