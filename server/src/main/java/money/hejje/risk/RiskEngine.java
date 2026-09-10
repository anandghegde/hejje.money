package money.hejje.risk;

import money.hejje.orders.OrderIntent;

/**
 * Authoritative, deterministic pre-trade control. Every order intent passes through here before it reaches the broker;
 * no client can bypass it. M1.4 ships an approve-all stub so the call site and persistence exist; M1.5 replaces the
 * implementation with the real controls and kill switch.
 */
public interface RiskEngine {

    RiskDecision evaluate(OrderIntent intent);

    /**
     * As {@link #evaluate(OrderIntent)} with the named checks waived: a failed waived check is recorded as passed, with
     * {@code reason} and the original message, so the decision stays explainable. Only the execution module waives, and
     * only for split children whose whole intent already passed the same checks (plan M5.3).
     */
    default RiskDecision evaluate(OrderIntent intent, java.util.Set<String> waived, String reason) {
        RiskDecision decision = evaluate(intent);
        if (waived == null || waived.isEmpty()) {
            return decision;
        }
        java.util.List<RiskCheck> checks = decision.checks().stream().map(c -> !c.passed() && waived.contains(c.name())
                ? new RiskCheck(c.name(), true, c.observed(), c.limit(), "waived: " + reason + " (" + c.message() + ")") : c).toList();
        return checks.stream().allMatch(RiskCheck::passed) ? RiskDecision.approved(checks) : RiskDecision.rejected(checks);
    }
}
