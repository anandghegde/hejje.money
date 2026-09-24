package money.hejje.ratings;

/**
 * Lifecycle of a base (docs/ratings.md). {@code PULLBACK}: triggered, closed back below the pivot, stop not hit.
 * {@code FAILED}: the structure broke before it triggered. The last four are closed.
 */
public enum BaseStatus {
    FORMING, NEAR_PIVOT, IN_BUY_ZONE, EXTENDED, PULLBACK, HIT_GOAL, STOPPED, FAILED, EXPIRED;

    public boolean closed() {
        return this == HIT_GOAL || this == STOPPED || this == FAILED || this == EXPIRED;
    }
}
