package money.hejje.market;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Exchange;

/**
 * A continuous futures series stitched from individual contracts (docs/data.md): the nearest contract is used and the
 * series rolls on the expiry date. Candles are stored under {@code id} like any instrument's.
 *
 * @param lotSize  lot size of the most recent contract (used for backtest sizing)
 * @param segments which contract covered which sessions, oldest first
 */
public record ContinuousSeries(UUID id, String underlying, Exchange exchange, String symbol, int lotSize, BigDecimal tickSize,
        List<Segment> segments, Instant builtAt) {

    public record Segment(UUID instrumentId, String contract, LocalDate expiry, LocalDate from, LocalDate to, long candles) {}

    public ContinuousSeries {
        segments = List.copyOf(segments);
    }

    /** Canonical symbol, for example {@code NFO:NIFTY:FUT:CONT}. */
    public static String symbolFor(Exchange exchange, String underlying) {
        return exchange + ":" + underlying.trim().toUpperCase() + ":FUT:CONT";
    }

    /** Stable id derived from the symbol, so rebuilding a series keeps its candles and references. */
    public static UUID idFor(String symbol) {
        return UUID.nameUUIDFromBytes(("hejje:continuous:" + symbol).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isContinuousSymbol(String symbol) {
        return symbol != null && symbol.trim().toUpperCase().endsWith(":FUT:CONT");
    }
}
