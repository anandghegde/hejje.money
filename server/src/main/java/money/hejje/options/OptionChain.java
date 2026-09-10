package money.hejje.options;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.OptionType;

/**
 * An option chain for one underlying and expiry (plan M5.4) with Black-76 implied volatility and greeks per quote.
 *
 * @param forward        the futures price the model uses ({@code forwardSource} says which future, or the index as a proxy)
 * @param yearsToExpiry  to 15:30 IST on the expiry date, in years of 365 days; null or ≤ 0 when expired
 * @param pcrOi          put/call ratio of open interest (null without call OI)
 * @param maxPain        the strike where option holders' total payout at expiry is smallest (null without OI)
 */
public record OptionChain(String underlying, LocalDate expiry, Instant asOf, BigDecimal forward, String forwardSource, Double yearsToExpiry,
        BigDecimal atmStrike, Double pcrOi, Double pcrVolume, BigDecimal maxPain, List<Row> rows, List<String> notes) {

    public OptionChain {
        rows = List.copyOf(rows);
        notes = List.copyOf(notes);
    }

    public record Row(BigDecimal strike, OptionQuote call, OptionQuote put) {
        public OptionQuote side(OptionType type) {
            return type == OptionType.CE ? call : put;
        }
    }

    /** One option's quote and model values; {@code iv} and the greeks are null without a usable price. */
    public record OptionQuote(UUID instrumentId, String symbol, int lotSize, BigDecimal last, BigDecimal bid, BigDecimal ask, long oi, long volume,
            Double iv, Double delta, Double gamma, Double vega, Double theta, boolean stale) {}

    public Optional<Row> row(BigDecimal strike) {
        return rows.stream().filter(r -> r.strike().compareTo(strike) == 0).findFirst();
    }
}
