package money.hejje.analogs.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.analogs.AnalogKind;
import money.hejje.analogs.AnalogSummary;
import money.hejje.analogs.AnalogsProperties;
import money.hejje.common.ClientNotification;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.InstrumentService;
import money.hejje.instruments.UniverseCatalog;
import money.hejje.market.Candle;
import money.hejje.market.ContinuousSeries;
import money.hejje.market.MarketService;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.EventEnvironmentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Session analogs for a date and checkpoint (plan M8.6). A summary is a function of stored M5 bars only: the
 * benchmark's bars of that date that closed by the checkpoint, and the intraday universe's sessions strictly before
 * the date. It does not depend on when it is computed, so a SIM replay of a day gives what the live job gave on it.
 * The reduced history is cached per date (about 1 kB per past session).
 */
@Component
public class SessionAnalogComputer {

    private static final Logger log = LoggerFactory.getLogger(SessionAnalogComputer.class);
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    private record Cache(LocalDate date, List<SessionAnalogEngine.Instrument> instruments, Map<UUID, SessionAnalogEngine.History> histories,
            List<SessionAnalogEngine.Session> candidates, Set<LocalDate> expiryDays) {
    }

    private final AnalogsProperties props;
    private final UniverseCatalog universes;
    private final InstrumentService instrumentService;
    private final MarketService market;
    private final EventEnvironmentSource environment;
    private final AnalogStore store;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;
    private final SessionAnalogEngine engine;
    private Cache cache;

    SessionAnalogComputer(AnalogsProperties props, UniverseCatalog universes, InstrumentService instrumentService, MarketService market,
            EventEnvironmentSource environment, AnalogStore store, HejjeClock clock, ApplicationEventPublisher events) {
        this.props = props;
        this.universes = universes;
        this.instrumentService = instrumentService;
        this.market = market;
        this.environment = environment;
        this.store = store;
        this.clock = clock;
        this.events = events;
        this.engine = new SessionAnalogEngine(props);
    }

    /** Checkpoints of {@code date} that have passed by {@code now}, in order. */
    public List<String> due(LocalDate date, Instant now) {
        return props.session().checkpoints().stream()
                .filter(c -> !date.atTime(LocalTime.parse(c)).atZone(clock.zone()).toInstant().isAfter(now)).toList();
    }

    /**
     * Computes and stores the summaries of {@code instrumentIds} for one checkpoint; keys that exist are left alone.
     * Returns the symbols written.
     */
    public synchronized List<String> compute(LocalDate date, String checkpoint, Collection<UUID> instrumentIds) {
        int index = engine.checkpointIndex(checkpoint);
        if (index < 0) {
            throw new IllegalArgumentException("checkpoint must be one of " + props.session().checkpoints());
        }
        Cache c = cache(date);
        Instant open = clock.sessionWindow(date).open().toInstant();
        Instant until = date.atTime(LocalTime.parse(checkpoint)).atZone(clock.zone()).toInstant();
        List<String> written = new ArrayList<>();
        for (UUID id : new java.util.LinkedHashSet<>(instrumentIds)) {
            SessionAnalogEngine.Instrument benchmark = c.instruments().stream().filter(i -> i.id().equals(id)).findFirst()
                    .orElseGet(() -> instrumentService.findById(id).map(i -> new SessionAnalogEngine.Instrument(id, i.hejjeSymbol().format())).orElse(null));
            if (benchmark == null) {
                continue;
            }
            SessionAnalogEngine.History history = c.histories().computeIfAbsent(id, k -> history(-1, id, date));
            List<SessionAnalogEngine.Bar> today = bars(market.candles(id, Timeframe.M5, open, until.minusSeconds(1)), until).getOrDefault(date, List.of());
            engine.today(today, index, history).ifPresent(snapshot -> {
                SessionAnalogEngine.Analysis a = engine.analyse(benchmark, date, index, snapshot, c.candidates(), c.instruments(), c.expiryDays());
                if (store.insert(a.summary(), a.matches())) {
                    written.add(benchmark.symbol());
                }
            });
        }
        if (!written.isEmpty()) {
            events.publishEvent(new ClientNotification("session_analogs_updated", Map.of("date", date.toString(), "checkpoint", checkpoint,
                    "symbols", written)));
        }
        return written;
    }

    public boolean computed(LocalDate date, String checkpoint) {
        return store.existsCheckpoint(date, checkpoint, props.engineVersion());
    }

    /** The intraday universe: the universe file's constituents, the extra symbols, and every continuous futures series. */
    private List<SessionAnalogEngine.Instrument> universe() {
        Map<UUID, SessionAnalogEngine.Instrument> out = new LinkedHashMap<>();
        universes.find(props.session().universe()).ifPresent(u -> universes.resolve(u.name()).instruments()
                .forEach(i -> out.put(i.id(), new SessionAnalogEngine.Instrument(i.id(), i.hejjeSymbol().format()))));
        for (String symbol : props.session().extraSymbols()) {
            instrumentService.resolve(symbol).ifPresent(i -> out.put(i.id(), new SessionAnalogEngine.Instrument(i.id(), i.hejjeSymbol().format())));
        }
        for (ContinuousSeries series : market.continuousSeries()) {
            out.put(series.id(), new SessionAnalogEngine.Instrument(series.id(), series.symbol()));
        }
        return List.copyOf(out.values());
    }

    private Cache cache(LocalDate date) {
        if (cache != null && cache.date().equals(date)) {
            return cache;
        }
        long started = System.nanoTime();
        List<SessionAnalogEngine.Instrument> instruments = universe();
        Map<UUID, SessionAnalogEngine.History> histories = new java.util.HashMap<>();
        List<SessionAnalogEngine.Session> candidates = new ArrayList<>();
        for (int k = 0; k < instruments.size(); k++) {
            SessionAnalogEngine.History h = history(k, instruments.get(k).id(), date);
            histories.put(instruments.get(k).id(), h);
            candidates.addAll(h.sessions());
        }
        Set<LocalDate> expiry = new HashSet<>();
        candidates.stream().map(SessionAnalogEngine.Session::date).distinct()
                .filter(d -> environment.environment(d) == EventEnvironment.EXPIRY_SESSION).forEach(expiry::add);
        cache = new Cache(date, instruments, histories, List.copyOf(candidates), expiry);
        log.info("Session analog history for {}: {} instruments, {} candidate sessions, {} ms", date, instruments.size(), candidates.size(),
                (System.nanoTime() - started) / 1_000_000);
        return cache;
    }

    /** The instrument's sessions strictly before {@code date}, reduced. */
    private SessionAnalogEngine.History history(int index, UUID id, LocalDate date) {
        Instant end = date.atStartOfDay(clock.zone()).toInstant();
        TreeMap<LocalDate, List<SessionAnalogEngine.Bar>> byDate = bars(
                market.candles(id, Timeframe.M5, EPOCH.atStartOfDay(clock.zone()).toInstant(), end.minusSeconds(1)), end);
        return engine.history(index, new ArrayList<>(byDate.keySet()), new ArrayList<>(byDate.values()));
    }

    /** Candles that closed by {@code until}, grouped by session, as bars indexed from 09:15. */
    private TreeMap<LocalDate, List<SessionAnalogEngine.Bar>> bars(List<Candle> candles, Instant until) {
        TreeMap<LocalDate, List<SessionAnalogEngine.Bar>> out = new TreeMap<>();
        for (Candle c : candles) {
            if (c.openTime().plus(Timeframe.M5.duration()).isAfter(until)) {
                continue;
            }
            var local = c.openTime().atZone(clock.zone());
            long minutes = java.time.Duration.between(SessionAnalogEngine.OPEN, local.toLocalTime()).toMinutes();
            if (minutes < 0 || minutes % SessionAnalogEngine.BAR_MINUTES != 0 || minutes >= 375) {
                continue;
            }
            out.computeIfAbsent(local.toLocalDate(), d -> new ArrayList<>()).add(new SessionAnalogEngine.Bar((int) (minutes / SessionAnalogEngine.BAR_MINUTES),
                    c.open().doubleValue(), c.high().doubleValue(), c.low().doubleValue(), c.close().doubleValue(), c.volume()));
        }
        return out;
    }

    /** Stored session summaries of a symbol for a date: one per computed checkpoint. */
    public List<AnalogSummary> summaries(String symbol, LocalDate date) {
        return store.forDate(date, AnalogKind.SESSION, props.engineVersion()).stream().filter(s -> s.symbol().equals(symbol)).toList();
    }
}
