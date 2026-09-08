package money.hejje.broker;

/** Normalized broker order status. Brokers report many strings; Hejje reasons about these. */
public enum BrokerOrderStatus {
    /** Received by the broker, not yet at the exchange (validation pending, put order req received, ...). */
    PENDING,
    /** Live at the exchange. */
    OPEN,
    /** Stop order waiting for its trigger. */
    TRIGGER_PENDING,
    /** A modification is being processed. */
    MODIFY_PENDING,
    /** A cancellation is being processed. */
    CANCEL_PENDING,
    /** Fully executed. */
    COMPLETE,
    CANCELLED,
    REJECTED,
    /** Status string the adapter does not recognize. */
    UNKNOWN;

    public boolean isTerminal() {
        return this == COMPLETE || this == CANCELLED || this == REJECTED;
    }
}
