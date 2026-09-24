package money.hejje.analogs.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import money.hejje.analogs.AnalogComputeResult;
import money.hejje.analogs.AnalogKind;
import money.hejje.analogs.AnalogSummary;
import money.hejje.analogs.AnalogsProperties;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.UniverseCatalog;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the daily engine for a set of dates: loads the universe's D1 closes and volumes up to the last date, then works
 * lookback by lookback (one feature table in memory at a time), in parallel across symbols on a bounded pool of
 * platform threads (the work is CPU bound). A (date, lookback) that already has summaries under the engine version is
 * skipped, so re-running is a no-op.
 */
@Component
public class DailyAnalogComputer {

    private static final Logger log = LoggerFactory.getLogger(DailyAnalogComputer.class);

    private final AnalogsProperties props;
    private final UniverseCatalog universes;
    private final MarketService market;
    private final AnalogStore store;
    private final HejjeClock clock;
    private final ObjectMapper json;

    DailyAnalogComputer(AnalogsProperties props, UniverseCatalog universes, MarketService market, AnalogStore store, HejjeClock clock,
            ObjectMapper json) {
        this.props = props;
        this.universes = universes;
        this.market = market;
        this.store = store;
        this.clock = clock;
        this.json = json;
    }

    /**
     * @param symbols   benchmarks to compute (null or empty: the whole universe); the candidates are always the whole universe
     * @param lookbacks null or empty: every configured lookback
     */
    public synchronized AnalogComputeResult compute(List<LocalDate> dates, Set<String> symbols, List<Integer> lookbacks) {
        long started = System.nanoTime();
        List<LocalDate> ordered = dates.stream().distinct().sorted().toList();
        LocalDate last = ordered.get(ordered.size() - 1);
        DailyAnalogEngine engine = new DailyAnalogEngine(load(last), props);
        List<Integer> windows = lookbacks == null || lookbacks.isEmpty() ? props.lookbacks() : lookbacks;
        AtomicInteger written = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, props.parallelism()), r -> {
            Thread t = new Thread(r, "analogs");
            t.setDaemon(true);
            return t;
        });
        try {
            for (int lookback : windows) {
                DailyAnalogEngine.Table table = null;
                for (LocalDate date : ordered) {
                    boolean whole = symbols == null || symbols.isEmpty();
                    if (whole && store.exists(date, AnalogKind.DAILY, lookback, props.engineVersion())) {
                        continue;
                    }
                    if (table == null) {
                        table = engine.table(lookback);
                    }
                    DailyAnalogEngine.Prepared prepared = engine.prepare(date, table);
                    List<Future<?>> tasks = new ArrayList<>();
                    for (int b = 0; b < engine.universe().size(); b++) {
                        if (!whole && !symbols.contains(engine.universe().get(b).symbol())) {
                            continue;
                        }
                        final int benchmark = b;
                        tasks.add(pool.submit(() -> engine.analyse(prepared, benchmark, engine.context(benchmark, date)).ifPresent(a -> {
                            if (store.insert(a.summary(), a.matches())) {
                                written.incrementAndGet();
                            }
                        })));
                    }
                    for (Future<?> task : tasks) {
                        task.get();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("analog run interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("analog run failed: " + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("Daily analogs v{}: {} dates, {} lookbacks, {} symbols, {} summaries in {} ms", props.engineVersion(), ordered.size(), windows.size(),
                engine.universe().size(), written.get(), millis);
        List<AnalogSummary> all = new ArrayList<>();
        ordered.forEach(d -> all.addAll(store.forDate(d, AnalogKind.DAILY, props.engineVersion())));
        return new AnalogComputeResult(ordered, props.engineVersion(), engine.universe().size(), written.get(), millis, hash(all));
    }

    /** The universe's D1 closes and volumes up to and including {@code to} (the market module caps reads at the simulation clock in SIM). */
    private List<DailyAnalogEngine.Series> load(LocalDate to) {
        List<DailyAnalogEngine.Series> out = new ArrayList<>();
        for (Instrument instrument : universes.resolve(props.universe()).instruments()) {
            List<Candle> candles = market.candles(instrument.id(), Timeframe.D1, LocalDate.of(2000, 1, 1).atStartOfDay(clock.zone()).toInstant(),
                    to.plusDays(1).atStartOfDay(clock.zone()).toInstant().minusSeconds(1));
            if (candles.isEmpty()) {
                continue;
            }
            int n = candles.size();
            long[] day = new long[n];
            double[] close = new double[n];
            double[] volume = new double[n];
            for (int i = 0; i < n; i++) {
                day[i] = candles.get(i).openTime().atZone(clock.zone()).toLocalDate().toEpochDay();
                close[i] = candles.get(i).close().doubleValue();
                volume[i] = candles.get(i).volume();
            }
            out.add(DailyAnalogEngine.Series.of(instrument.id(), instrument.hejjeSymbol().format(), day, close, volume));
        }
        return out;
    }

    String hash(List<AnalogSummary> summaries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AnalogSummary s : summaries) {
                digest.update(json.writeValueAsString(s).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
