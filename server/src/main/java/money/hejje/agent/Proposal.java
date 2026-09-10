package money.hejje.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A sized order proposal with its dry-run risk decision and policy decision (plan M4.4 {@code prepare_order}). */
public record Proposal(String kind, UUID signalId, UUID strategyId, String strategy, UUID versionId, UUID instrumentId, String instrument, String side,
        int quantity, String orderType, String product, BigDecimal entry, BigDecimal stop, BigDecimal target, BigDecimal riskRupees, BigDecimal maxRisk,
        Integer autonomyLevel, String eventRisk, Integer score, boolean newStrategyVersion, String riskOutcome, List<RiskCheckView> riskChecks,
        String policyDecision, String policyRule, String policyReason, List<String> notes, String summary) {

    public record RiskCheckView(String name, boolean passed, String observed, String limit, String message) {}
}
