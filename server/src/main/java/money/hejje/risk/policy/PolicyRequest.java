package money.hejje.risk.policy;

import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;

/**
 * The action under decision and its context. {@code autonomyLevel} is the deployment's (or the account's) level,
 * {@code eventRisk} the instrument's level name (LOW, MEDIUM, HIGH), {@code score} the Hejje Score when known.
 *
 * @param autoQualified the deployment's version qualifies for automatic execution (plan M5.2: promoted for the mode and,
 *                      for live modes, {@code hejje.auto.min-paper-trades} closed paper trades)
 * @param budgetBreach  why the deployment's daily budget is used up, or null while within it
 * @param unscoredPaper a PAPER rehearsal of an options strategy, which can never have a Hejje Score (no option candles
 *                      to backtest): AUTO_ELIGIBLE then does not require a score (plan M6.4). Simulated fills only.
 */
public record PolicyRequest(PolicyAction action, ActorType actorType, ExecutionMode mode, Integer autonomyLevel, String eventRisk, Integer score,
        boolean newStrategyVersion, UUID strategyId, UUID instrumentId, boolean autoQualified, String budgetBreach, boolean unscoredPaper) {

    public PolicyRequest {
        unscoredPaper = unscoredPaper && mode == ExecutionMode.PAPER;
    }

    public PolicyRequest(PolicyAction action, ActorType actorType, ExecutionMode mode, Integer autonomyLevel, String eventRisk, Integer score,
            boolean newStrategyVersion, UUID strategyId, UUID instrumentId, boolean autoQualified, String budgetBreach) {
        this(action, actorType, mode, autonomyLevel, eventRisk, score, newStrategyVersion, strategyId, instrumentId, autoQualified, budgetBreach, false);
    }

    /** A request outside AUTO execution (agents, manual): not qualified for automation, no deployment budget. */
    public PolicyRequest(PolicyAction action, ActorType actorType, ExecutionMode mode, Integer autonomyLevel, String eventRisk, Integer score,
            boolean newStrategyVersion, UUID strategyId, UUID instrumentId) {
        this(action, actorType, mode, autonomyLevel, eventRisk, score, newStrategyVersion, strategyId, instrumentId, false, null, false);
    }
}
