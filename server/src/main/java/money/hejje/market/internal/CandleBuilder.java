package money.hejje.market.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.common.Timeframe;
import money.hejje.common.event.MarketTick;
import money.hejje.market.Candle;

/**
 * Aggregates ticks into 1-minute candles aligned to IST minute boundaries, then rolls 1-minute candles up into
 * M3/M5/M15/H1. A minute with no tick yields a synthetic candle (o=h=l=c = previous close, volume 0). Pure and
 * single-threaded per instrument; the streamer feeds it from the tick-bus dispatcher thread.
 *
 * <p>Design: volume in a tick is the cumulative day volume, so a candle's volume is the last tick's cumulative volume
 * minus the day volume at the candle's open. Closed candles are returned to the caller to publish and persist.
 */
public class CandleBuilder {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_SYNTHETIC_FILL = 400;
    private static final List<Timeframe> DERIVED = List.of(Timeframe.M3, Timeframe.M5, Timeframe.M15, Timeframe.H1);

    private static final class Bar {
        Instant openTime;
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        long volumeAtOpen;
        long lastCumulativeVolume;
        long oi;
        boolean fromTick;

        Candle toCandle(UUID instrumentId, Timeframe tf, boolean synthetic) {
            long volume = synthetic ? 0 : Math.max(0, lastCumulativeVolume - volumeAtOpen);
            return new Candle(instrumentId, tf, openTime, open, high, low, close, volume, oi, synthetic);
        }
    }

    private final Map<UUID, Bar> minuteBars = new ConcurrentHashMap<>();
    private final Map<String, Bar> derivedBars = new ConcurrentHashMap<>();
    private final Map<UUID, BigDecimal> lastClose = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCumulativeVolume = new ConcurrentHashMap<>();
    /** IST date of each instrument's last tick: the cumulative day volume restarts from zero on a new day. */
    private final Map<UUID, java.time.LocalDate> lastTickDay = new ConcurrentHashMap<>();

    static Instant floorTo(Instant ts, Timeframe tf) {
        ZonedDateTime ist = ts.atZone(IST);
        Duration d = tf.duration();
        if (tf == Timeframe.H1) {
            return ist.withMinute(0).withSecond(0).withNano(0).toInstant();
        }
        int minutes = (int) d.toMinutes();
        int flooredMinute = (ist.getMinute() / minutes) * minutes;
        return ist.withMinute(flooredMinute).withSecond(0).withNano(0).toInstant();
    }

    /** Applies a tick; returns any candles (1m and derived) that the tick closed. */
    public synchronized List<Candle> onTick(MarketTick tick) {
        UUID id = tick.instrumentId();
        Instant minuteStart = floorTo(tick.ts(), Timeframe.M1);
        List<Candle> closed = new ArrayList<>();
        Bar bar = minuteBars.get(id);
        if (bar != null && minuteStart.isAfter(bar.openTime)) {
            closed.addAll(closeMinute(id, bar, minuteStart));
            bar = null;
        }
        java.time.LocalDate day = tick.ts().atZone(IST).toLocalDate();
        if (!day.equals(lastTickDay.put(id, day))) {
            lastCumulativeVolume.remove(id); // yesterday's cumulative is no baseline for today's first minute
        }
        if (bar == null) {
            bar = newBar(minuteStart, tick.lastPrice(), volumeBaseline(id));
            minuteBars.put(id, bar);
        }
        update(bar, tick.lastPrice(), tick.volume(), tick.oi());
        lastCumulativeVolume.put(id, tick.volume());
        return closed;
    }

    /**
     * Closes any minute whose boundary has passed as of {@code now}, filling empty minutes with synthetic candles.
     * Returns closed candles (1m and derived) ordered by openTime.
     */
    public synchronized List<Candle> onClock(Instant now) {
        List<Candle> closed = new ArrayList<>();
        Instant currentMinute = floorTo(now, Timeframe.M1);
        for (UUID id : List.copyOf(minuteBars.keySet())) {
            Bar bar = minuteBars.get(id);
            if (bar != null && currentMinute.isAfter(bar.openTime)) {
                closed.addAll(closeMinute(id, bar, currentMinute));
                minuteBars.remove(id);
            }
        }
        return closed;
    }

    private List<Candle> closeMinute(UUID id, Bar bar, Instant currentMinute) {
        List<Candle> closed = new ArrayList<>();
        Candle candle = bar.toCandle(id, Timeframe.M1, !bar.fromTick);
        closed.add(candle);
        lastClose.put(id, candle.close());
        closed.addAll(rollUp(id, candle));
        // fill the gap of empty minutes up to (but not including) currentMinute with synthetic candles
        Instant next = bar.openTime.plus(Duration.ofMinutes(1));
        int filled = 0;
        while (next.isBefore(currentMinute) && filled++ < MAX_SYNTHETIC_FILL) {
            Candle synthetic = synthetic(id, next).toCandle(id, Timeframe.M1, true);
            closed.add(synthetic);
            lastClose.put(id, synthetic.close());
            closed.addAll(rollUp(id, synthetic));
            next = next.plus(Duration.ofMinutes(1));
        }
        return closed;
    }

    private List<Candle> rollUp(UUID id, Candle minute) {
        List<Candle> closed = new ArrayList<>();
        for (Timeframe tf : DERIVED) {
            Instant start = floorTo(minute.openTime(), tf);
            String key = id + "/" + tf;
            Bar bar = derivedBars.get(key);
            if (bar != null && start.isAfter(bar.openTime)) {
                closed.add(bar.toCandle(id, tf, !bar.fromTick));
                bar = null;
            }
            if (bar == null) {
                bar = newBar(start, minute.open(), minute.synthetic() ? volumeBaselineForDerived(id, minute) : minute.volume());
                bar.volumeAtOpen = 0;
                bar.lastCumulativeVolume = 0;
                derivedBars.put(key, bar);
            }
            // aggregate the minute candle into the derived bar
            bar.high = bar.high.max(minute.high());
            bar.low = bar.low.min(minute.low());
            bar.close = minute.close();
            bar.lastCumulativeVolume += minute.volume();
            bar.oi = minute.oi();
            if (!minute.synthetic()) {
                bar.fromTick = true;
            }
            // the period's last minute is in: the derived bar closes now, at its own close time, not a minute later
            if (!minute.openTime().plus(Duration.ofMinutes(1)).isBefore(start.plus(tf.duration()))) {
                closed.add(bar.toCandle(id, tf, !bar.fromTick));
                derivedBars.remove(key);
            }
        }
        return closed;
    }

    private long volumeBaselineForDerived(UUID id, Candle minute) {
        return 0;
    }

    private Bar synthetic(UUID id, Instant minuteStart) {
        BigDecimal close = lastClose.getOrDefault(id, BigDecimal.ZERO);
        Bar bar = newBar(minuteStart, close, lastCumulativeVolume.getOrDefault(id, 0L));
        bar.fromTick = false;
        bar.lastCumulativeVolume = bar.volumeAtOpen; // zero volume
        return bar;
    }

    private Bar newBar(Instant openTime, BigDecimal price, long volumeBaseline) {
        Bar bar = new Bar();
        bar.openTime = openTime;
        bar.open = price;
        bar.high = price;
        bar.low = price;
        bar.close = price;
        bar.volumeAtOpen = volumeBaseline;
        bar.lastCumulativeVolume = volumeBaseline;
        bar.fromTick = false;
        return bar;
    }

    private void update(Bar bar, BigDecimal price, long cumulativeVolume, long oi) {
        bar.high = bar.high.max(price);
        bar.low = bar.low.min(price);
        bar.close = price;
        bar.lastCumulativeVolume = Math.max(bar.lastCumulativeVolume, cumulativeVolume);
        bar.oi = oi;
        bar.fromTick = true;
    }

    private long volumeBaseline(UUID id) {
        return lastCumulativeVolume.getOrDefault(id, 0L);
    }

    /** Forgets every instrument's cumulative day volume (a new SIM session starts from zero). */
    public synchronized void clearVolumes() {
        lastCumulativeVolume.clear();
        lastTickDay.clear();
    }

    /** Closes and returns every open bar (session end / shutdown), 1m then derived, without synthetic gap-fill. */
    public synchronized List<Candle> flushAll() {
        List<Candle> closed = new ArrayList<>();
        for (Map.Entry<UUID, Bar> e : minuteBars.entrySet()) {
            Candle minute = e.getValue().toCandle(e.getKey(), Timeframe.M1, !e.getValue().fromTick);
            closed.add(minute);
            lastClose.put(e.getKey(), minute.close());
        }
        minuteBars.clear();
        for (Map.Entry<String, Bar> e : derivedBars.entrySet()) {
            String[] parts = e.getKey().split("/");
            UUID id = UUID.fromString(parts[0]);
            Timeframe tf = Timeframe.valueOf(parts[1]);
            closed.add(e.getValue().toCandle(id, tf, !e.getValue().fromTick));
        }
        derivedBars.clear();
        return closed;
    }
}
