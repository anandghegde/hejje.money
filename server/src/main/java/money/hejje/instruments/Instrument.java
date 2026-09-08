package money.hejje.instruments;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OptionType;

/**
 * A broker-neutral instrument. Identity is the natural key {@code (exchange, symbol, type, expiry, strike, optionType)};
 * {@code id} is stable across syncs.
 */
public record Instrument(
        UUID id,
        String symbol,
        String name,
        Exchange exchange,
        InstrumentType type,
        String underlying,
        LocalDate expiry,
        BigDecimal strike,
        OptionType optionType,
        int lotSize,
        BigDecimal tickSize,
        String isin,
        boolean active,
        Instant updatedAt) {

    @JsonProperty("hejjeSymbol")
    public HejjeSymbol hejjeSymbol() {
        return new HejjeSymbol(exchange, symbol, type, expiry, strike, optionType);
    }

    public boolean isDerivative() {
        return type == InstrumentType.FUT || type == InstrumentType.OPT;
    }
}
