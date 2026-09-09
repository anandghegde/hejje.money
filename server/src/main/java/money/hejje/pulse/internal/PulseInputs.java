package money.hejje.pulse.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
import money.hejje.pulse.PulseProperties;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
import org.springframework.stereotype.Component;

/** Reduces stored M5 candles (and the regime snapshot) to the numbers the rules read. */
@Component
class PulseInputs {

    static final Timeframe TF = Timeframe.M5;

    private final MarketService market;
    private final InstrumentService instruments;
    private final RegimeService regime;
    private final SectorUniverse sectors;
    private final PulseProperties props;
    private final HejjeClock clock;

    PulseInputs(MarketService market, InstrumentService instruments, RegimeService regime, SectorUniverse sectors, PulseProperties props, HejjeClock clock) {
        this.market = market;
        this.instruments = instruments;
        this.regime = regime;
        this.sectors = sectors;
        this.props = props;
        this.clock = clock;
    }

    /** The previous session's last close and today's closed bars (up to {@code asOf}) of one instrument. */
    record DayObservation(Double prevClose, List<Bar> bars) {
        Double last() {
            return bars.isEmpty() ? null : bars.get(bars.size() - 1).close();
        }

        Double changePct() {
            Double last = last();
            return prevClose == null || last == null || prevClose == 0 ? null : 100.0 * (last - prevClose) / prevClose;
        }
    }

    PulseInput load(LocalDate date, Instant asOf) {
        RegimeSnapshot snapshot;
        try {
            snapshot = regime.current();
        } catch (RuntimeException e) {
            snapshot = null;
        }
        Optional<UUID> index = instruments.resolve(props.indexSymbol()).map(Instrument::id);
        DayObservation idx = index.map(id -> observe(id, date, asOf)).orElse(new DayObservation(null, List.of()));
        Double average = null;
        Double roc = null;
        if (!idx.bars().isEmpty()) {
            average = idx.bars().stream().mapToDouble(Bar::typicalPrice).average().orElse(Double.NaN);
            int n = idx.bars().size();
            if (n >= 3) {
                int back = Math.min(props.thresholds().rocBars(), n - 1);
                double from = idx.bars().get(n - 1 - back).close();
                roc = from == 0 ? null : 100.0 * (idx.bars().get(n - 1).close() - from) / from;
            }
        }
        DayObservation vix = instruments.resolve(props.vixSymbol()).map(Instrument::id).map(id -> observe(id, date, asOf)).orElse(new DayObservation(null, List.of()));
        Double relativeVolume = null;
        Double basis = null;
        Optional<Instrument> future = instruments.nearestFuture(props.futuresUnderlying(), date);
        if (future.isPresent()) {
            relativeVolume = relativeVolume(future.get().id(), date, asOf);
            Double futLast = observe(future.get().id(), date, asOf).last();
            if (futLast != null && idx.last() != null && idx.last() > 0) {
                basis = 100.0 * (futLast - idx.last()) / idx.last();
            }
        }
        List<PulseInput.SectorObservation> sectorRows = new ArrayList<>();
        for (SectorUniverse.Sector s : sectors.sectors()) {
            Double change = instruments.resolve(s.symbol()).map(Instrument::id).map(id -> observe(id, date, asOf).changePct()).orElse(null);
            sectorRows.add(new PulseInput.SectorObservation(s.name(), s.symbol(), change));
        }
        return new PulseInput(snapshot, idx.last(), idx.prevClose(), average, roc, idx.bars().size(), vix.last(), vix.prevClose(), relativeVolume, basis, sectorRows);
    }

    DayObservation observe(UUID instrumentId, LocalDate date, Instant asOf) {
        ZoneId zone = clock.zone();
        SessionWindow window = clock.sessionWindow(date);
        Instant end = asOf.isBefore(window.close().toInstant()) ? asOf : window.close().toInstant();
        Double prevClose = null;
        List<Bar> bars = new ArrayList<>();
        for (Candle c : market.candles(instrumentId, TF, date.minusDays(7).atStartOfDay(zone).toInstant(), end)) {
            if (c.openTime().plus(TF.duration()).isAfter(asOf)) {
                continue;
            }
            if (c.openTime().isBefore(window.open().toInstant())) {
                prevClose = c.close().doubleValue();
            } else {
                bars.add(Bar.of(c, zone));
            }
        }
        return new DayObservation(prevClose, bars);
    }

    /** Cumulative volume so far today vs the average cumulative volume at the same time of day over the prior sessions. */
    Double relativeVolume(UUID instrumentId, LocalDate date, Instant asOf) {
        ZoneId zone = clock.zone();
        LocalTime cutoff = asOf.atZone(zone).toLocalTime();
        if (cutoff.isAfter(HejjeClock.SESSION_CLOSE)) {
            cutoff = HejjeClock.SESSION_CLOSE;
        }
        int sessions = props.thresholds().relativeVolumeSessions();
        Instant start = date.minusDays(sessions * 2L + 7).atStartOfDay(zone).toInstant();
        TreeMap<LocalDate, Double> cumulative = new TreeMap<>();
        for (Candle c : market.candles(instrumentId, TF, start, asOf)) {
            if (c.openTime().plus(TF.duration()).isAfter(asOf)) {
                continue;
            }
            Bar b = Bar.of(c, zone);
            if (b.closeTimeOfDay().isAfter(cutoff)) {
                continue;
            }
            cumulative.merge(b.session(), b.volume(), Double::sum);
        }
        Double today = cumulative.get(date);
        if (today == null) {
            return null;
        }
        List<Double> prior = new ArrayList<>(cumulative.headMap(date, false).values());
        if (prior.isEmpty()) {
            return null;
        }
        List<Double> window = prior.subList(Math.max(0, prior.size() - sessions), prior.size());
        double avg = window.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return avg <= 0 ? null : today / avg;
    }
}
