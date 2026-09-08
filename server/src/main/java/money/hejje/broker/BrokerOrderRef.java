package money.hejje.broker;

/** The broker's identifier for an order. */
public record BrokerOrderRef(String brokerOrderId) {

    public BrokerOrderRef {
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            throw new IllegalArgumentException("brokerOrderId is required");
        }
    }
}
