package money.hejje.broker.internal;

/** In-process signal from an adapter that the broker rejected the session (AUTH error). Not durable. */
public record BrokerAuthRejected(String broker, String detail) {
}
