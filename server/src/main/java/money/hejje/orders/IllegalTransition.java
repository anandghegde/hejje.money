package money.hejje.orders;

/** Thrown when an order is asked to make a transition the state machine forbids. The order state is left unchanged. */
public class IllegalTransition extends RuntimeException {

    private final OrderState from;
    private final OrderState to;

    public IllegalTransition(OrderState from, OrderState to) {
        super("Illegal order transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public OrderState from() {
        return from;
    }

    public OrderState to() {
        return to;
    }
}
