package money.hejje.risk;

import java.util.List;

/** The outcome of evaluating an order intent against every risk control. */
public record RiskDecision(RiskOutcome outcome, List<RiskCheck> checks) {

    public RiskDecision {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public boolean isApproved() {
        return outcome == RiskOutcome.APPROVED;
    }

    public static RiskDecision approved(List<RiskCheck> checks) {
        return new RiskDecision(RiskOutcome.APPROVED, checks);
    }

    public static RiskDecision rejected(List<RiskCheck> checks) {
        return new RiskDecision(RiskOutcome.REJECTED, checks);
    }

    /** The names+messages of the checks that failed. */
    public List<String> failures() {
        return checks.stream().filter(c -> !c.passed()).map(c -> c.name() + ": " + c.message()).toList();
    }
}
