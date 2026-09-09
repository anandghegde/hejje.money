package money.hejje.events;

import money.hejje.strategy.StrategyDefinition;

/**
 * A strategy's {@code event_rules} applied to an {@link EventRisk}.
 *
 * @param triggered true when a HIGH-risk event is within the rule's window (or today, when no window is given)
 * @param action    the rule's action (ALLOW when the definition has none)
 * @param reason    templated sentence, for example {@code Q2 results today 16:00 (event risk HIGH, rule: block within 15 min)}
 */
public record EventRuleOutcome(boolean triggered, StrategyDefinition.EventAction action, String reason) {

    public boolean blocks() {
        return triggered && action == StrategyDefinition.EventAction.BLOCK;
    }

    public boolean cautions() {
        return triggered && action == StrategyDefinition.EventAction.CAUTION;
    }

    public static EventRuleOutcome none(String reason) {
        return new EventRuleOutcome(false, StrategyDefinition.EventAction.ALLOW, reason);
    }
}
