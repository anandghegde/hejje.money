package money.hejje.analogs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import money.hejje.analogs.AnalogMatch;
import money.hejje.analogs.AnalogSummary;
import money.hejje.common.Exchange;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.InstrumentService;
import money.hejje.instruments.UniverseCatalog;
import money.hejje.market.Candle;
import money.hejje.market.ContinuousSeries;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.market.MarketService;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.EventEnvironmentSource;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

/**
 * The session history is read once and extended date by date: computing many dates on one computer gives exactly what a
 * fresh computer per date gives, and a store write, an earlier date or a new Hejje day rebuilds it.
 */
class SessionAnalogHistoryCacheTest {

    static final List<LocalDate> DATES = AnalogFixtures.weekdays(LocalDate.of(2025, 1, 1), 45);
    static final Instant EPOCH = LocalDate.of(2000, 1, 1).atStartOfDay(MutableClock.IST).toInstant();
    static final List<UUID> IDS = List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3));
    static final Map<UUID, List<Candle>> CANDLES = new HashMap<>();

    static {
        for (UUID id : IDS) {
            Random random = new Random(id.getLeastSignificantBits());
            List<Candle> out = new ArrayList<>();
            double last = 1000;
            for (LocalDate d : DATES) {
                last *= 1 + random.nextGaussian() * 0.004;
                for (int i = 0; i < 75; i++) {
                    double open = last;
                    double close = open * (1 + random.nextGaussian() * 0.0012);
                    Instant t = d.atTime(LocalTime.of(9, 15).plusMinutes(5L * i)).atZone(MutableClock.IST).toInstant();
                    out.add(new Candle(id, Timeframe.M5, t, dec(open), dec(Math.max(open, close) + 0.3), dec(Math.min(open, close) - 0.3), dec(close),
                            5000 + random.nextInt(5000), 0, false));
                    last = close;
                }
            }
            CANDLES.put(id, out);
        }
    }

    final MutableClock time = MutableClock.atIst("2026-09-08T10:00:00");
    final AtomicLong writes = new AtomicLong();

    record Stored(AnalogSummary summary, List<AnalogMatch> matches) {
    }

    /** A computer over the three seeded instruments (as continuous series, the only universe source that needs no catalogue). */
    SessionAnalogComputer computer(MarketService market, List<Stored> stored) {
        HistoricalCandleStore historical = mock(HistoricalCandleStore.class);
        when(historical.writes()).thenAnswer(i -> writes.get());
        UniverseCatalog universes = mock(UniverseCatalog.class);
        when(universes.find(anyString())).thenReturn(Optional.empty());
        EventEnvironmentSource environment = d -> d.getDayOfWeek() == DayOfWeek.THURSDAY ? EventEnvironment.EXPIRY_SESSION : EventEnvironment.NORMAL;
        AnalogStore store = mock(AnalogStore.class);
        when(store.insert(any(), any())).thenAnswer(i -> stored.add(new Stored(i.getArgument(0), i.getArgument(1))));
        return new SessionAnalogComputer(AnalogFixtures.props(3), universes, mock(InstrumentService.class), market, historical, environment, store,
                new HejjeClock(time, MutableClock.IST, (d, e) -> false), e -> { });
    }

    static MarketService market() {
        MarketService market = mock(MarketService.class);
        List<ContinuousSeries> series = new ArrayList<>();
        for (UUID id : IDS) {
            series.add(new ContinuousSeries(id, "U" + id.getLeastSignificantBits(), Exchange.values()[0], "NFO:U" + id.getLeastSignificantBits() + "-I",
                    1, BigDecimal.ONE, List.of(), Instant.EPOCH));
        }
        when(market.continuousSeries()).thenReturn(series);
        when(market.candles(any(), eq(Timeframe.M5), any(), any())).thenAnswer(i -> {
            Instant from = i.getArgument(2);
            Instant to = i.getArgument(3);
            return CANDLES.get(i.<UUID>getArgument(0)).stream().filter(c -> !c.openTime().isBefore(from) && !c.openTime().isAfter(to)).toList();
        });
        return market;
    }

    /** How many times the whole history of an instrument was read (a build from scratch). */
    static long fullReads(MarketService market) {
        return mockingDetails(market).getInvocations().stream().map(Invocation::getArguments)
                .filter(a -> a.length == 4 && EPOCH.equals(a[2])).count();
    }

    static void computeAll(SessionAnalogComputer computer, LocalDate date) {
        for (String checkpoint : List.of("09:45", "11:15")) {
            computer.compute(date, checkpoint, IDS);
        }
    }

    @Test
    void manyDatesReadTheHistoryOnceAndGiveWhatAFreshBuildGives() {
        List<Stored> incremental = new ArrayList<>();
        MarketService shared = market();
        SessionAnalogComputer one = computer(shared, incremental);
        List<Stored> fromScratch = new ArrayList<>();
        for (LocalDate date : DATES.subList(20, 45)) {
            computeAll(one, date);
            computeAll(computer(market(), fromScratch), date);
        }
        assertThat(incremental).hasSize(25 * 2 * 3).isEqualTo(fromScratch);
        assertThat(incremental.get(incremental.size() - 1).summary().candidates()).isGreaterThan(incremental.get(0).summary().candidates());
        assertThat(fullReads(shared)).isEqualTo(IDS.size());   // one full read per instrument for 25 dates
    }

    @Test
    void aStoreWriteAnEarlierDateOrANewDayRebuilds() {
        MarketService market = market();
        SessionAnalogComputer computer = computer(market, new ArrayList<>());
        computeAll(computer, DATES.get(30));
        computeAll(computer, DATES.get(31));
        assertThat(fullReads(market)).isEqualTo(3);
        writes.incrementAndGet();
        computeAll(computer, DATES.get(32));
        assertThat(fullReads(market)).isEqualTo(6);
        computeAll(computer, DATES.get(25));
        assertThat(fullReads(market)).isEqualTo(9);
        time.setIst("2026-09-09T10:00:00");
        computeAll(computer, DATES.get(26));
        assertThat(fullReads(market)).isEqualTo(12);
        computeAll(computer, DATES.get(27));
        assertThat(fullReads(market)).isEqualTo(12);
    }

    static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
