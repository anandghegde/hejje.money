package money.hejje.broker;

import java.util.List;

/**
 * Broker-neutral contract (PRD section 5.2). Every broker call the daemon makes goes through this interface;
 * callers only see Hejje models. M1.1 defines the instrument master call; the remaining operations
 * (session, quotes, history, streaming, orders, positions, funds) arrive in M1.2.
 */
public interface BrokerAdapter {

    /** Stable broker code used in {@code broker_instrument_mapping.broker} and {@code hejje_order.broker}. */
    String brokerCode();

    /** Downloads the full instrument master. Called once per trading day before the session opens. */
    List<BrokerInstrument> getInstruments();
}
