package money.hejje.ratings.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.Universe;
import money.hejje.instruments.UniverseCatalog;
import money.hejje.market.MarketService;
import money.hejje.ratings.RatingsProperties;
import org.springframework.stereotype.Component;

/** Loads the universe's D1 history through the market module (which caps reads at the simulation clock in SIM). */
@Component
public class UniverseSeries {

    private final RatingsProperties props;
    private final UniverseCatalog universes;
    private final MarketService market;
    private final HejjeClock clock;

    UniverseSeries(RatingsProperties props, UniverseCatalog universes, MarketService market, HejjeClock clock) {
        this.props = props;
        this.universes = universes;
        this.market = market;
        this.clock = clock;
    }

    /** Every resolved member with its candles of {@code [from, to]}; members without candles are left out. */
    List<RatingsEngine.Member> load(LocalDate from, LocalDate to) {
        Universe.Resolved resolved = universes.resolve(props.universe());
        List<RatingsEngine.Member> out = new ArrayList<>();
        for (Instrument instrument : resolved.instruments()) {
            String symbol = instrument.hejjeSymbol().format();
            var candles = market.candles(instrument.id(), Timeframe.D1, from.atStartOfDay(clock.zone()).toInstant(),
                    to.plusDays(1).atStartOfDay(clock.zone()).toInstant().minusSeconds(1));
            if (!candles.isEmpty()) {
                out.add(new RatingsEngine.Member(instrument.id(), symbol, resolved.universe().industry().get(symbol), DailySeries.of(candles, clock.zone()),
                        instrument.tickSize() == null || instrument.tickSize().signum() <= 0 ? new java.math.BigDecimal("0.05") : instrument.tickSize()));
            }
        }
        return out;
    }

    /** Dates of {@code [from, to]} on which at least {@code min-session-coverage} of the members have a candle. */
    List<LocalDate> sessions(List<RatingsEngine.Member> members, LocalDate from, LocalDate to) {
        Map<Long, Integer> count = new TreeMap<>();
        for (RatingsEngine.Member m : members) {
            for (long day : m.series().day()) {
                if (day >= from.toEpochDay() && day <= to.toEpochDay()) {
                    count.merge(day, 1, Integer::sum);
                }
            }
        }
        double needed = Math.max(1, props.formula().minSessionCoverage() * members.size());
        return count.entrySet().stream().filter(e -> e.getValue() >= needed).map(e -> LocalDate.ofEpochDay(e.getKey())).toList();
    }
}
