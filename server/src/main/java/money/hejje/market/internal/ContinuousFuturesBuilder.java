package money.hejje.market.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.CandleCoverage;
import money.hejje.market.ContinuousSeries;
import money.hejje.market.HistoricalCandleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stitches per-contract candles into a continuous series (docs/data.md): for each session the contract with the
 * earliest expiry strictly after the session date is used, so the series rolls on the expiry date itself. No
 * back-adjustment.
 */
@Component
public class ContinuousFuturesBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContinuousFuturesBuilder.class);

    private final InstrumentService instruments;
    private final HistoricalCandleStore historical;
    private final ContinuousSeriesStore store;
    private final HejjeClock clock;

    ContinuousFuturesBuilder(InstrumentService instruments, HistoricalCandleStore historical, ContinuousSeriesStore store, HejjeClock clock) {
        this.instruments = instruments;
        this.historical = historical;
        this.store = store;
        this.clock = clock;
    }

    /** Builds (or rebuilds) the series for {@code underlying} on {@code timeframe} from every contract in the store. */
    public ContinuousSeries build(String underlying, Timeframe timeframe) {
        List<Instrument> contracts = instruments.futures(underlying);
        if (contracts.isEmpty()) {
            throw new IllegalArgumentException("No futures contracts known for " + underlying);
        }
        Map<Instrument, List<Candle>> perContract = new LinkedHashMap<>();
        for (Instrument contract : contracts) {
            CandleCoverage coverage = historical.coverage(contract.id(), timeframe);
            if (coverage.isEmpty()) {
                continue;
            }
            perContract.put(contract, historical.read(contract.id(), timeframe, coverage.from(), coverage.to()));
        }
        Instrument latest = contracts.get(contracts.size() - 1);
        String symbol = ContinuousSeries.symbolFor(latest.exchange(), underlying);
        UUID id = ContinuousSeries.idFor(symbol);
        Stitched stitched = stitch(perContract, id, timeframe, clock.zone());
        if (!stitched.candles().isEmpty()) {
            historical.write(id, timeframe, stitched.candles());
        }
        ContinuousSeries series = new ContinuousSeries(id, underlying.trim().toUpperCase(), latest.exchange(), symbol, latest.lotSize(),
                latest.tickSize(), stitched.segments(), clock.now());
        store.upsert(series);
        log.info("Continuous series {} ({}) built from {} contract(s), {} candles", symbol, timeframe, stitched.segments().size(), stitched.candles().size());
        return series;
    }

    /** Result of stitching: the merged candles (re-keyed to the series id) and the contract segments. */
    public record Stitched(List<Candle> candles, List<ContinuousSeries.Segment> segments) {}

    /** Pure stitching rule, unit-testable: contract for session d = earliest expiry strictly after d. */
    public static Stitched stitch(Map<Instrument, List<Candle>> perContract, UUID seriesId, Timeframe timeframe, ZoneId zone) {
        List<Instrument> ordered = new ArrayList<>(perContract.keySet());
        ordered.sort((a, b) -> a.expiry().compareTo(b.expiry()));
        TreeMap<Instant, Candle> merged = new TreeMap<>();
        Map<UUID, LocalDate[]> range = new LinkedHashMap<>();
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (Instrument contract : ordered) {
            for (Candle c : perContract.get(contract)) {
                LocalDate session = c.openTime().atZone(zone).toLocalDate();
                Instrument owner = ownerOf(ordered, session);
                if (owner == null || !owner.id().equals(contract.id())) {
                    continue;
                }
                merged.put(c.openTime(), new Candle(seriesId, timeframe, c.openTime(), c.open(), c.high(), c.low(), c.close(), c.volume(), c.oi(), c.synthetic()));
                LocalDate[] r = range.computeIfAbsent(contract.id(), k -> new LocalDate[]{session, session});
                if (session.isBefore(r[0])) {
                    r[0] = session;
                }
                if (session.isAfter(r[1])) {
                    r[1] = session;
                }
                counts.merge(contract.id(), 1L, Long::sum);
            }
        }
        List<ContinuousSeries.Segment> segments = new ArrayList<>();
        for (Instrument contract : ordered) {
            LocalDate[] r = range.get(contract.id());
            if (r != null) {
                segments.add(new ContinuousSeries.Segment(contract.id(), contract.hejjeSymbol().format(), contract.expiry(), r[0], r[1], counts.get(contract.id())));
            }
        }
        return new Stitched(new ArrayList<>(merged.values()), segments);
    }

    private static Instrument ownerOf(List<Instrument> ordered, LocalDate session) {
        for (Instrument contract : ordered) {
            if (contract.expiry().isAfter(session)) {
                return contract;
            }
        }
        return null;
    }
}
