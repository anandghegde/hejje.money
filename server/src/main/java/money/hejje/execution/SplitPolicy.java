package money.hejje.execution;

import java.math.BigDecimal;

/**
 * How a large intent is worked in child orders (PRD 35, plan M5.3).
 *
 * @param maxChildQuantity  largest child (rounded down to whole lots)
 * @param delayMs           pause between children
 * @param priceTolerancePct adverse move from the reference price (percent) beyond which children stop; null = no check
 * @param cancelOnMove      true: a breach ends the split (CANCELLED); false: children wait for the price to come back until the deadline
 * @param deadlineSeconds   overall time allowed; what is not filled by then is cancelled (EXPIRED)
 */
public record SplitPolicy(int maxChildQuantity, long delayMs, BigDecimal priceTolerancePct, boolean cancelOnMove, long deadlineSeconds) {

    public SplitPolicy {
        if (maxChildQuantity < 1) {
            throw new IllegalArgumentException("maxChildQuantity must be at least 1");
        }
        if (delayMs < 0 || delayMs > 600_000) {
            throw new IllegalArgumentException("delayMs must be between 0 and 600000");
        }
        if (priceTolerancePct != null && priceTolerancePct.signum() <= 0) {
            throw new IllegalArgumentException("priceTolerancePct must be positive");
        }
        if (deadlineSeconds < 1) {
            throw new IllegalArgumentException("deadlineSeconds must be at least 1");
        }
    }
}
