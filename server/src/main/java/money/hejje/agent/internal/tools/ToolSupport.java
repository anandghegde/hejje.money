package money.hejje.agent.internal.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import money.hejje.agent.ToolException;
import money.hejje.common.Money;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import org.springframework.stereotype.Component;

/** Shared helpers for the tool providers: instrument references, symbols, schemas, rupee and date conversions. */
@Component
public class ToolSupport {

    private static final ObjectMapper SCHEMA_PARSER = new ObjectMapper();

    private final InstrumentService instruments;

    ToolSupport(InstrumentService instruments) {
        this.instruments = instruments;
    }

    /** An instrument by id or Hejje symbol ({@code NSE:RELIANCE}, {@code INDEX:NIFTY 50}); unknown → NOT_FOUND. */
    public Instrument instrument(String ref) {
        if (ref == null || ref.isBlank()) {
            throw ToolException.invalid("instrument is required");
        }
        String r = ref.trim();
        try {
            UUID id = UUID.fromString(r);
            return instruments.findById(id).orElseThrow(() -> ToolException.notFound("Unknown instrument " + r));
        } catch (IllegalArgumentException notAnId) {
            return instruments.resolve(r).orElseThrow(() -> ToolException.notFound("Unknown instrument " + r + " (use a Hejje symbol such as NSE:RELIANCE)"));
        }
    }

    /** A memoising id → Hejje symbol ({@code NSE:INFY}) function for one tool call. */
    public Function<UUID, String> symbols() {
        Map<UUID, String> cache = new HashMap<>();
        return id -> id == null ? null : cache.computeIfAbsent(id, k -> instruments.findById(k).map(ToolSupport::symbol).orElse(k.toString()));
    }

    /** The Hejje symbol agents can pass back to any tool ({@code NSE:INFY}, {@code INDEX:NIFTY 50}). */
    public static String symbol(Instrument instrument) {
        return instrument.hejjeSymbol().format();
    }

    public static JsonNode schema(String text) {
        try {
            return SCHEMA_PARSER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid tool schema: " + text, e);
        }
    }

    /** A JSON array literal of an enum's constant names, for schema text blocks. */
    public static String enumJson(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(e -> "\"" + e.name() + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    public static BigDecimal rupees(Money m) {
        return m == null ? null : m.toRupees();
    }

    public static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    public static LocalDate date(String text, LocalDate fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw ToolException.invalid("Not an ISO date: " + text);
        }
    }

    public static UUID uuid(String text) {
        return text == null ? null : UUID.fromString(text);
    }
}
