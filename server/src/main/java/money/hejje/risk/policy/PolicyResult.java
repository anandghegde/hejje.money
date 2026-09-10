package money.hejje.risk.policy;

import java.util.List;

/** The decision, the rule that made it (null for the default) and why, plus how each rule evaluated. */
public record PolicyResult(PolicyDecision decision, String rule, String reason, List<String> trace) {

    public PolicyResult {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
