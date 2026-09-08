package money.hejje.instruments;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OptionType;

/**
 * Canonical broker-neutral symbol (docs/symbols.md):
 * <pre>
 *   NSE:RELIANCE
 *   INDEX:NIFTY 50
 *   NFO:NIFTY:FUT:2026-09-24
 *   NFO:NIFTY:OPT:2026-09-24:25000:CE
 * </pre>
 * {@code symbol} is the equity/index symbol, or the underlying for derivatives.
 */
public record HejjeSymbol(Exchange exchange, String symbol, InstrumentType type, LocalDate expiry, BigDecimal strike,
        OptionType optionType) {

    public HejjeSymbol {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(type, "type");
        if (symbol == null || symbol.isBlank() || symbol.contains(":")) {
            throw new IllegalArgumentException("Invalid symbol: " + symbol);
        }
        symbol = symbol.trim().toUpperCase();
        switch (type) {
            case EQ, INDEX -> {
                if (expiry != null || strike != null || optionType != null) {
                    throw new IllegalArgumentException(type + " symbols carry no expiry, strike or option type");
                }
                if ((type == InstrumentType.INDEX) != (exchange == Exchange.INDEX)) {
                    throw new IllegalArgumentException("INDEX type and INDEX exchange go together: " + exchange + " " + type);
                }
            }
            case FUT -> {
                Objects.requireNonNull(expiry, "expiry");
                requireDerivativeExchange(exchange);
                if (strike != null || optionType != null) {
                    throw new IllegalArgumentException("FUT symbols carry no strike or option type");
                }
            }
            case OPT -> {
                Objects.requireNonNull(expiry, "expiry");
                requireDerivativeExchange(exchange);
                Objects.requireNonNull(strike, "strike");
                Objects.requireNonNull(optionType, "optionType");
                if (strike.signum() <= 0) {
                    throw new IllegalArgumentException("Strike must be positive: " + strike);
                }
                strike = strike.stripTrailingZeros();
                if (strike.scale() < 0) {
                    strike = strike.setScale(0);
                }
            }
        }
    }

    private static void requireDerivativeExchange(Exchange exchange) {
        if (exchange == Exchange.INDEX) {
            throw new IllegalArgumentException("Derivatives cannot be on the INDEX pseudo-exchange");
        }
    }

    public static HejjeSymbol equity(Exchange exchange, String symbol) {
        return new HejjeSymbol(exchange, symbol, InstrumentType.EQ, null, null, null);
    }

    public static HejjeSymbol index(String symbol) {
        return new HejjeSymbol(Exchange.INDEX, symbol, InstrumentType.INDEX, null, null, null);
    }

    public static HejjeSymbol future(Exchange exchange, String underlying, LocalDate expiry) {
        return new HejjeSymbol(exchange, underlying, InstrumentType.FUT, expiry, null, null);
    }

    public static HejjeSymbol option(Exchange exchange, String underlying, LocalDate expiry, BigDecimal strike, OptionType optionType) {
        return new HejjeSymbol(exchange, underlying, InstrumentType.OPT, expiry, strike, optionType);
    }

    /** Parses the canonical form. Throws {@link IllegalArgumentException} with a reason on malformed input. */
    @JsonCreator
    public static HejjeSymbol parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Symbol is required");
        }
        String[] parts = text.trim().split(":", -1);
        Exchange exchange;
        try {
            exchange = Exchange.valueOf(parts[0].trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown exchange in symbol '" + text + "'");
        }
        try {
            return switch (parts.length) {
                case 2 -> exchange == Exchange.INDEX ? index(parts[1]) : equity(exchange, parts[1]);
                case 4 -> {
                    requireType(parts[2], "FUT", text);
                    yield future(exchange, parts[1], LocalDate.parse(parts[3].trim()));
                }
                case 6 -> {
                    requireType(parts[2], "OPT", text);
                    yield option(exchange, parts[1], LocalDate.parse(parts[3].trim()), new BigDecimal(parts[4].trim()),
                            OptionType.valueOf(parts[5].trim().toUpperCase()));
                }
                default -> throw new IllegalArgumentException("Malformed symbol '" + text
                        + "': expected EXCHANGE:SYMBOL, EXCHANGE:UNDERLYING:FUT:YYYY-MM-DD or EXCHANGE:UNDERLYING:OPT:YYYY-MM-DD:STRIKE:CE|PE");
            };
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Malformed expiry in symbol '" + text + "': " + e.getParsedString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed strike in symbol '" + text + "'");
        }
    }

    private static void requireType(String actual, String expected, String text) {
        if (!expected.equalsIgnoreCase(actual.trim())) {
            throw new IllegalArgumentException("Malformed symbol '" + text + "': expected " + expected + " but found '" + actual + "'");
        }
    }

    /** The canonical string form. */
    public String format() {
        return switch (type) {
            case EQ, INDEX -> exchange + ":" + symbol;
            case FUT -> exchange + ":" + symbol + ":FUT:" + expiry;
            case OPT -> exchange + ":" + symbol + ":OPT:" + expiry + ":" + strike.toPlainString() + ":" + optionType;
        };
    }

    @JsonValue
    @Override
    public String toString() {
        return format();
    }
}
