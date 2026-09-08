package money.hejje.broker;

import java.util.Optional;
import java.util.UUID;

/**
 * Port through which adapters translate between Hejje instrument ids and broker identifiers. Implemented by the
 * instruments module (which depends on this module, not the other way round).
 */
public interface BrokerInstrumentResolver {

    Optional<BrokerInstrumentRef> forInstrument(UUID instrumentId, String broker);

    Optional<UUID> byBrokerToken(String broker, String brokerToken);

    Optional<UUID> byTradingSymbol(String broker, String exchangeSegment, String tradingSymbol);
}
