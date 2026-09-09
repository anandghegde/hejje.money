package money.hejje.events;

import money.hejje.strategy.StrategyDefinition;

/**
 * Applies a definition's {@code event_rules} (docs/strategy-dsl.md) to an {@link EventRisk}: the rule triggers when
 * the risk is HIGH and its event is within {@code high_risk_event_within_minutes} (the DSL requires the window unless the
 * action is allow; a missing window means any HIGH event today); the action then decides between AVOID (block) and
 * TRADE WITH CAUTION (caution).
 */
public final class EventRules {

    private EventRules() {
    }

    public static EventRuleOutcome apply(StrategyDefinition.EventRules rules, EventRisk risk) {
        StrategyDefinition.EventRules r = rules == null ? StrategyDefinition.EventRules.DEFAULT : rules;
        if (r.action() == StrategyDefinition.EventAction.ALLOW) {
            return EventRuleOutcome.none("event rules: allow");
        }
        if (!risk.available()) {
            return EventRuleOutcome.none("event rules: event service unavailable, not applied");
        }
        if (risk.level() != EventRiskLevel.HIGH) {
            return EventRuleOutcome.none("event risk " + risk.level() + ": rule not triggered");
        }
        Integer window = r.highRiskEventWithinMinutes();
        Long minutes = risk.triggerMinutesTo();
        boolean within = window == null || minutes == null || minutes <= window;
        String what = risk.trigger() == null ? "high-risk event" : risk.trigger().title();
        if (!within) {
            return EventRuleOutcome.none(what + " in " + minutes + " min is outside the " + window + "-minute rule window");
        }
        String reason = what + (minutes == null || minutes == 0 ? " today" : " in " + minutes + " min") + " (event risk HIGH, rule: "
                + r.action().name().toLowerCase() + (window == null ? "" : " within " + window + " min") + ")";
        return new EventRuleOutcome(true, r.action(), reason);
    }
}
