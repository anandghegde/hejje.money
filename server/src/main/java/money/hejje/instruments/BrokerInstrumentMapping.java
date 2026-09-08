package money.hejje.instruments;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** How one broker identifies a Hejje instrument. */
public record BrokerInstrumentMapping(
        UUID instrumentId,
        String broker,
        String brokerToken,
        String tradingSymbol,
        String exchangeSegment,
        Map<String, Object> raw,
        Instant syncedAt) {
}
