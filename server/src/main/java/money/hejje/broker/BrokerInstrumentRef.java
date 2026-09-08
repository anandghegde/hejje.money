package money.hejje.broker;

import java.math.BigDecimal;
import java.util.UUID;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;

/** What an adapter needs to know about a Hejje instrument to talk to its broker. */
public record BrokerInstrumentRef(UUID instrumentId, Exchange exchange, InstrumentType type, String brokerToken, String tradingSymbol,
        String exchangeSegment, int lotSize, BigDecimal tickSize) {
}
