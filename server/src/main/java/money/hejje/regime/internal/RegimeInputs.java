package money.hejje.regime.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.SessionWindow;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.market.indicators.Bar;
import money.hejje.regime.RegimeProperties;
import org.springframework.stereotype.Component;

/** Loads what the rules need from the market module. Intraday inputs use M5 bars (docs/regime.md). */
@Component
class RegimeInputs {

    static final Timeframe INTRADAY = Timeframe.M5;
    /** Calendar days of daily history loaded ahead of a session so the trailing window is full. */
    static final int HISTORY_DAYS = 420;

    private final MarketService market;
    private final InstrumentService instruments;
    private final BreadthUniverse universe;
    private final RegimeProperties props;
    private final HejjeClock clock;

    RegimeInputs(MarketService market, InstrumentService instruments, BreadthUniverse universe, RegimeProperties props, HejjeClock clock) {
        this.market = market;
        this.instruments = instruments;
        this.universe = universe;
        this.props = props;
        this.clock = clock;
    }

    Optional<UUID> indexId() {
        return instruments.resolve(props.indexSymbol()).map(Instrument::id);
    }

    Optional<UUID> vixId() {
        return instruments.resolve(props.vixSymbol()).map(Instrument::id);
    }

    /** Daily bars of {@code instrumentId} whose session is in {@code [from, to]}. */
    List<Candle> daily(UUID instrumentId, LocalDate from, LocalDate to) {
        ZoneId zone = clock.zone();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1);
        return market.candles(instrumentId, Timeframe.D1, start, end);
    }

    /** Closed intraday bars of {@code date} up to {@code asOf}; empty when there are none. */
    IntradayInput intraday(UUID instrumentId, LocalDate date, Instant asOf) {
        SessionWindow window = clock.sessionWindow(date);
        Instant end = asOf.isBefore(window.close().toInstant()) ? asOf : window.close().toInstant();
        List<Bar> bars = new ArrayList<>();
        for (Candle c : market.candles(instrumentId, INTRADAY, window.open().toInstant(), end)) {
            if (!c.openTime().plus(INTRADAY.duration()).isAfter(asOf)) {
                bars.add(Bar.of(c, clock.zone()));
            }
        }
        return new IntradayInput(bars, !asOf.isBefore(window.close().toInstant()));
    }

    /** A daily bar for a session that has no stored daily candle yet, built from its intraday bars (null when none). */
    static Candle partialDaily(UUID instrumentId, LocalDate date, ZoneId zone, List<Bar> bars) {
        if (bars.isEmpty()) {
            return null;
        }
        double high = bars.stream().mapToDouble(Bar::high).max().orElseThrow();
        double low = bars.stream().mapToDouble(Bar::low).min().orElseThrow();
        long volume = (long) bars.stream().mapToDouble(Bar::volume).sum();
        return new Candle(instrumentId, Timeframe.D1, date.atStartOfDay(zone).toInstant(), price(bars.get(0).open()), price(high), price(low),
                price(bars.get(bars.size() - 1).close()), volume, 0, false);
    }

    private static BigDecimal price(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Live breadth: every constituent's last intraday close of {@code date} (up to {@code asOf}) vs its previous session close, plus session VWAP. */
    BreadthInput breadthIntraday(LocalDate date, Instant asOf) {
        List<Instrument> members = universe.instruments();
        int size = universe.symbols().size();
        if (members.isEmpty()) {
            return BreadthInput.none(size);
        }
        ZoneId zone = clock.zone();
        SessionWindow window = clock.sessionWindow(date);
        Instant end = asOf.isBefore(window.close().toInstant()) ? asOf : window.close().toInstant();
        Instant start = date.minusDays(7).atStartOfDay(zone).toInstant();
        List<BreadthInput.Constituent> out = new ArrayList<>();
        for (Instrument i : members) {
            double prevClose = Double.NaN;
            double last = Double.NaN;
            double sumPv = 0;
            double sumV = 0;
            for (Candle c : market.candles(i.id(), INTRADAY, start, end)) {
                if (c.openTime().plus(INTRADAY.duration()).isAfter(asOf)) {
                    continue;
                }
                if (c.openTime().isBefore(window.open().toInstant())) {
                    prevClose = c.close().doubleValue();
                } else {
                    Bar b = Bar.of(c, zone);
                    last = b.close();
                    sumPv += b.typicalPrice() * b.volume();
                    sumV += b.volume();
                }
            }
            if (!Double.isNaN(prevClose) && !Double.isNaN(last)) {
                out.add(new BreadthInput.Constituent(i.hejjeSymbol().format(), prevClose, last, sumV > 0 ? sumPv / sumV : null));
            }
        }
        return new BreadthInput(size, out);
    }

    /** Daily closes per constituent for historical breadth (advance/decline against the previous session). */
    Map<String, TreeMap<LocalDate, Double>> constituentDailyCloses(LocalDate from, LocalDate to) {
        Map<String, TreeMap<LocalDate, Double>> out = new LinkedHashMap<>();
        ZoneId zone = clock.zone();
        for (Instrument i : universe.instruments()) {
            TreeMap<LocalDate, Double> closes = new TreeMap<>();
            for (Candle c : daily(i.id(), from.minusDays(10), to)) {
                closes.put(c.openTime().atZone(zone).toLocalDate(), c.close().doubleValue());
            }
            if (!closes.isEmpty()) {
                out.put(i.hejjeSymbol().format(), closes);
            }
        }
        return out;
    }

    /**
     * The index volume proxy (docs/regime.md): per session, the summed D1 turnover ({@code close x volume}) of the
     * constituents, and how many of them had a candle. {@code INDEX:NIFTY 50} itself carries no volume.
     */
    TreeMap<LocalDate, double[]> constituentTurnover(LocalDate from, LocalDate to) {
        TreeMap<LocalDate, double[]> out = new TreeMap<>();
        ZoneId zone = clock.zone();
        for (Instrument i : universe.instruments()) {
            for (Candle c : daily(i.id(), from, to)) {
                double[] cell = out.computeIfAbsent(c.openTime().atZone(zone).toLocalDate(), d -> new double[2]);
                cell[0] += c.close().doubleValue() * c.volume();
                cell[1]++;
            }
        }
        return out;
    }

    /** A session's turnover from the constituents' intraday bars (last close x summed volume), for a day without D1 candles yet. */
    double[] constituentTurnoverIntraday(LocalDate date, Instant asOf) {
        double[] cell = new double[2];
        for (Instrument i : universe.instruments()) {
            List<Bar> bars = intraday(i.id(), date, asOf).bars();
            if (!bars.isEmpty()) {
                cell[0] += bars.get(bars.size() - 1).close() * bars.stream().mapToDouble(Bar::volume).sum();
                cell[1]++;
            }
        }
        return cell;
    }

    BreadthInput breadthHistorical(LocalDate date, Map<String, TreeMap<LocalDate, Double>> closes) {
        List<BreadthInput.Constituent> out = new ArrayList<>();
        for (Map.Entry<String, TreeMap<LocalDate, Double>> e : closes.entrySet()) {
            Double last = e.getValue().get(date);
            Map.Entry<LocalDate, Double> prev = e.getValue().lowerEntry(date);
            if (last != null && prev != null) {
                out.add(new BreadthInput.Constituent(e.getKey(), prev.getValue(), last, null));
            }
        }
        return new BreadthInput(universe.symbols().size(), out);
    }
}
