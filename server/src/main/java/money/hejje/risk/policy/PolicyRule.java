package money.hejje.risk.policy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One row of {@code policy_rule}. An empty {@code actions} set applies the rule to every action. */
public record PolicyRule(UUID id, String name, int priority, PolicyCondition condition, Set<PolicyAction> actions, PolicyDecision decision,
        Map<String, Object> params, boolean enabled, String description, Instant updatedAt, String updatedBy) {

    public PolicyRule {
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
