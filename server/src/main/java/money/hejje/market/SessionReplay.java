package money.hejje.market;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.internal.MarketPipeline;
import money.hejje.market.internal.ReplayMarketDataSource;
import org.springframework.stereotype.Service;

/**
 * Historical sessions as ticks for the SIM replay (plan M7.2). A day with recorded ticks ({@code ticks/<day>/ticks.parquet})
 * replays them; otherwise each stored M1 candle becomes four synthetic ticks: the open at :00, then the low and the high
 * in the conventional order (low first on an up bar, high first on a down bar) at :20 and :40, and the close at :59, with
 * the bar's volume split across them as cumulative day volume. The ticks go into the same {@link MarketPipeline} as live
 * ticks, so candles, indicators, regime and pulse are computed exactly as live. Reads here are the replay engine's own and
 * are not capped at the simulation clock; everything else reads through {@link MarketService}, which is.
 */
@Service
public class SessionReplay {

    /** Offsets of the synthetic ticks inside a minute, in seconds. */
    public static final int[] OFFSETS = {0, 20, 40, 59};

    private final HistoricalCandleStore historical;
    private final MarketPipeline pipeline;
    private final HejjeClock clock;
    private final Path ticksRoot;

    SessionReplay(HistoricalCandleStore historical, MarketPipeline pipeline, HejjeClock clock, HejjeProperties properties) {
        this.historical = historical;
        this.pipeline = pipeline;
        this.clock = clock;
        this.ticksRoot = properties.dataDir().resolve("ticks");
    }

    /** Where a day's ticks come from. */
    public enum Source { RECORDED_TICKS, M1_CANDLES, NONE }

    public Source source(UUID instrumentId, LocalDate day) {
        if (Files.exists(ticksFile(day))) {
            return Source.RECORDED_TICKS;
        }
        return m1(instrumentId, day).isEmpty() ? Source.NONE : Source.M1_CANDLES;
    }

    /** Every tick of {@code day} for the instruments, ordered by time (instruments in the given order at equal times). */
    public List<MarketTick> ticks(List<UUID> instrumentIds, LocalDate day) {
        List<MarketTick> out = new ArrayList<>();
        Path recorded = ticksFile(day);
        if (Files.exists(recorded)) {
            Set<UUID> wanted = Set.copyOf(instrumentIds);
            ReplayMarketDataSource.read(recorded).stream().filter(t -> wanted.contains(t.instrumentId())).forEach(out::add);
        } else {
            for (UUID id : instrumentIds) {
                long cumulative = 0;
                for (Candle c : m1(id, day)) {
                    if (c.synthetic()) {
                        continue; // no trade that minute: the pipeline fills the gap itself
                    }
                    out.addAll(syntheticTicks(c, cumulative));
                    cumulative += c.volume();
                }
            }
        }
        Map<UUID, Integer> order = new HashMap<>();
        for (int i = 0; i < instrumentIds.size(); i++) {
            order.put(instrumentIds.get(i), i);
        }
        out.sort(Comparator.comparing(MarketTick::ts).thenComparing(t -> order.getOrDefault(t.instrumentId(), Integer.MAX_VALUE)));
        return out;
    }

    private List<Candle> m1(UUID instrumentId, LocalDate day) {
        Instant from = day.atStartOfDay(clock.zone()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(clock.zone()).toInstant().minusSeconds(1);
        return historical.read(instrumentId, Timeframe.M1, from, to);
    }

    private Path ticksFile(LocalDate day) {
        return ticksRoot.resolve(day.toString()).resolve("ticks.parquet");
    }

    /** The four ticks of one M1 candle; {@code cumulativeBefore} is the day's volume before this bar. */
    public static List<MarketTick> syntheticTicks(Candle m1, long cumulativeBefore) {
        boolean up = m1.close().compareTo(m1.open()) >= 0;
        BigDecimal[] prices = {m1.open(), up ? m1.low() : m1.high(), up ? m1.high() : m1.low(), m1.close()};
        long share = m1.volume() / OFFSETS.length;
        List<MarketTick> out = new ArrayList<>(OFFSETS.length);
        long cumulative = cumulativeBefore;
        for (int i = 0; i < OFFSETS.length; i++) {
            cumulative += i == OFFSETS.length - 1 ? m1.volume() - share * (OFFSETS.length - 1) : share;
            out.add(new MarketTick(m1.instrumentId(), m1.openTime().plusSeconds(OFFSETS[i]), prices[i], null, null, cumulative, m1.oi(),
                    MarketTick.Mode.QUOTE));
        }
        return out;
    }

    /** Feeds one replayed tick into the pipeline. */
    public void feed(MarketTick tick) {
        pipeline.onTick(tick);
    }

    /** Starts a session with no open bars, cumulative volumes or quotes from an earlier one. */
    public void reset() {
        pipeline.resetForSimulation();
    }
}
