package money.hejje.orders;

/** The explicit order transition table (PRD section 36). Illegal transitions throw {@link IllegalTransition}. */
public final class OrderStateMachine {

    private OrderStateMachine() {
    }

    public static boolean isLegal(OrderState from, OrderState to) {
        return from.canTransitionTo(to);
    }

    /** Returns {@code to} if the transition is legal, otherwise throws {@link IllegalTransition}. */
    public static OrderState require(OrderState from, OrderState to) {
        if (!isLegal(from, to)) {
            throw new IllegalTransition(from, to);
        }
        return to;
    }
}
