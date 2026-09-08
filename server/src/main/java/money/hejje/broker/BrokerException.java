package money.hejje.broker;

/** Any failure talking to a broker, normalized to a {@link Kind} so callers never see broker-specific errors. */
public class BrokerException extends RuntimeException {

    public enum Kind {
        /** Session invalid or expired; a new login is required. */
        AUTH,
        /** Broker rate limit hit; retry after a pause. */
        RATE_LIMIT,
        /** Connection failed; the request may or may not have reached the broker. */
        NETWORK,
        /** No response in time; the request may or may not have been processed. */
        TIMEOUT,
        /** Broker or exchange rejected the request (risk, margin, market closed, ...). */
        REJECTED,
        /** The request itself was invalid. */
        INPUT,
        /** Anything else; treat the outcome as unknown. */
        UNKNOWN
    }

    private final Kind kind;
    private final String brokerMessage;
    private final boolean retryable;

    public BrokerException(Kind kind, String brokerMessage, boolean retryable, Throwable cause) {
        super(kind + ": " + brokerMessage, cause);
        this.kind = kind;
        this.brokerMessage = brokerMessage;
        this.retryable = retryable;
    }

    public BrokerException(Kind kind, String brokerMessage) {
        this(kind, brokerMessage, kind == Kind.NETWORK || kind == Kind.TIMEOUT || kind == Kind.RATE_LIMIT, null);
    }

    public Kind kind() {
        return kind;
    }

    public String brokerMessage() {
        return brokerMessage;
    }

    public boolean retryable() {
        return retryable;
    }

    /** True when the broker may have processed the request despite the failure (state must be reconciled). */
    public boolean outcomeUnknown() {
        return kind == Kind.NETWORK || kind == Kind.TIMEOUT || kind == Kind.UNKNOWN;
    }
}
