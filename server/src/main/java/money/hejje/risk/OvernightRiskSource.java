package money.hejje.risk;

import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;

/**
 * The swing book's overnight risk for the risk dashboard (plan M11.3). Implemented by the swing module (which depends
 * on risk, so risk asks through this interface, as with {@link SizeFactorSource}).
 */
public interface OvernightRiskSource {

    /** The gap-adjusted overnight risk of the open swing positions, its budget and how many positions are open. */
    record OvernightRisk(Money used, Money budget, int positions) {}

    OvernightRisk overnightRisk(ExecutionMode mode);
}
