package money.hejje.auto;

import java.util.UUID;
import money.hejje.risk.policy.PolicyResult;

/** What the AUTO executor did with one signal (plan M5.2). {@code policy} is null when it never reached the policy. */
public record AutoDecision(UUID signalId, Outcome outcome, String reason, PolicyResult policy, UUID orderId) {

    public enum Outcome {
        /** Not an AUTO signal (autonomy below 4, other mode, paused) or no longer actionable: the normal flow applies. */
        SKIPPED,
        /** Submitted through the pipeline as actor STRATEGY. */
        EXECUTED,
        /** Needs a human: an approval was requested. */
        HELD,
        /** Refused (policy DENY, kill switch). */
        DENIED,
        /** The policy allowed it but the pipeline refused it (risk, gate); the signal stays with the human flow. */
        FAILED
    }
}
