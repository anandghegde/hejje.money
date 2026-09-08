package money.hejje.broker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OptionType;

/**
 * One row of a broker's instrument master, already normalized to Hejje semantics. The broker-side identity is
 * {@code brokerToken} (unique per broker) plus {@code tradingSymbol}/{@code exchangeSegment}; the Hejje identity is
 * {@code (exchange, symbol, type, expiry, strike, optionType)}.
 *
 * @param brokerToken     broker's instrument token, as text
 * @param tradingSymbol   broker's trading symbol, for example {@code NIFTY26SEPFUT}
 * @param exchangeSegment broker's exchange/segment code, for example {@code NFO}
 * @param exchange        Hejje exchange; {@code INDEX} for indices
 * @param symbol          equity/index symbol, or the underlying for derivatives
 * @param name            descriptive name
 * @param type            EQ, FUT, OPT or INDEX
 * @param underlying      underlying symbol for derivatives, otherwise null
 * @param expiry          derivative expiry, otherwise null
 * @param strike          option strike (scale 2), otherwise null
 * @param optionType      CE/PE for options, otherwise null
 * @param lotSize         lot size (1 for equities and indices)
 * @param tickSize        minimum price increment
 * @param isin            ISIN when the broker provides it, otherwise null
 * @param raw             the broker's original columns, kept for diagnostics
 */
public record BrokerInstrument(
        String brokerToken,
        String tradingSymbol,
        String exchangeSegment,
        Exchange exchange,
        String symbol,
        String name,
        InstrumentType type,
        String underlying,
        LocalDate expiry,
        BigDecimal strike,
        OptionType optionType,
        int lotSize,
        BigDecimal tickSize,
        String isin,
        Map<String, String> raw) {

    public BrokerInstrument {
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }
}
