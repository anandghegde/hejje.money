package money.hejje.broker;

import java.time.Instant;

/** An order state change reported by the broker through any channel. */
public record BrokerOrderUpdate(BrokerOrder order, Source source, Instant receivedAt) {

    public enum Source { BROKER_WS, BROKER_POSTBACK, BROKER_POLL }
}
