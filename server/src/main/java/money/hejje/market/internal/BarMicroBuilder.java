package money.hejje.market.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.event.MarketTick;
import money.hejje.market.BarMicro;
import money.hejje.market.Candle;

/**
 * Builds {@link BarMicro} per (instrument, M1 bar) from ticks and aggregates closed M1 values into derived timeframes
 * (plan M9.4). Pure and deterministic: the same ticks give the same values. A bar gets micro data only when at least one
 * of its ticks carried order-book fields (FULL mode); ticks without them still count for the flow of later book ticks.
 */
public class BarMicroBuilder {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int KEEP_MINUTES = 400;

    private static final class Acc {
        int ticks;
        int depthTicks;
        boolean book;
        Double imbalanceClose;
        double imbalanceSum;
        Double buySellRatio;
        long up;
        long down;
    }

    private record Last(BigDecimal price, long volume, LocalDate day) {}

    private final Map<String, Acc> open = new HashMap<>();
    private final Map<UUID, Last> last = new HashMap<>();
    private final Map<UUID, NavigableMap<Instant, BarMicro>> minutes = new HashMap<>();

    public synchronized void onTick(MarketTick tick) {
        UUID id = tick.instrumentId();
        Instant minute = CandleBuilder.floorTo(tick.ts(), Timeframe.M1);
        Acc acc = open.computeIfAbsent(id + "/" + minute, k -> new Acc());
        acc.ticks++;
        LocalDate day = tick.ts().atZone(IST).toLocalDate();
        Last prev = last.get(id);
        if (prev != null && prev.day().equals(day)) {
            long delta = tick.volume() - prev.volume();
            int move = tick.lastPrice().compareTo(prev.price());
            if (delta > 0 && move > 0) {
                acc.up += delta;
            } else if (delta > 0 && move < 0) {
                acc.down += delta;
            }
        }
        last.put(id, new Last(tick.lastPrice(), tick.volume(), day));
        if (!tick.hasBook()) {
            return;
        }
        acc.book = true;
        if (tick.bidQty5() != null && tick.askQty5() != null && tick.bidQty5() + tick.askQty5() > 0) {
            double imbalance = (double) (tick.bidQty5() - tick.askQty5()) / (tick.bidQty5() + tick.askQty5());
            acc.depthTicks++;
            acc.imbalanceSum += imbalance;
            acc.imbalanceClose = imbalance;
        }
        if (tick.totalBuyQty() != null && tick.totalSellQty() != null && tick.totalSellQty() > 0) {
            acc.buySellRatio = (double) tick.totalBuyQty() / tick.totalSellQty();
        }
    }

    /** The micro data of a closed candle: an M1 bar's own ticks, or the M1 bars inside a derived bar. Null when none. */
    public synchronized BarMicro onCandleClosed(Candle candle) {
        UUID id = candle.instrumentId();
        if (candle.timeframe() == Timeframe.M1) {
            Acc acc = open.remove(id + "/" + candle.openTime());
            if (acc == null || !acc.book) {
                return null;
            }
            BarMicro m = new BarMicro(id, Timeframe.M1, candle.openTime(), acc.imbalanceClose, acc.depthTicks == 0 ? null : acc.imbalanceSum / acc.depthTicks,
                    acc.buySellRatio, share(acc.up, acc.down), acc.up, acc.down, acc.ticks, acc.depthTicks);
            NavigableMap<Instant, BarMicro> mine = minutes.computeIfAbsent(id, k -> new TreeMap<>());
            mine.put(candle.openTime(), m);
            while (mine.size() > KEEP_MINUTES) {
                mine.pollFirstEntry();
            }
            return m;
        }
        NavigableMap<Instant, BarMicro> mine = minutes.get(id);
        if (mine == null) {
            return null;
        }
        List<BarMicro> inside = new ArrayList<>(mine.subMap(candle.openTime(), true, candle.openTime().plus(candle.timeframe().duration()), false).values());
        return aggregate(id, candle.timeframe(), candle.openTime(), inside);
    }

    /** M1 values rolled up: the last imbalance and ratio, depth-tick-weighted mean, summed flow. Null for no bars. */
    static BarMicro aggregate(UUID id, Timeframe tf, Instant openTime, List<BarMicro> m1) {
        if (m1.isEmpty()) {
            return null;
        }
        Double close = null;
        Double ratio = null;
        double sum = 0;
        long up = 0;
        long down = 0;
        int ticks = 0;
        int depth = 0;
        for (BarMicro m : m1) {
            if (m.imbalanceClose() != null) {
                close = m.imbalanceClose();
            }
            if (m.buySellRatio() != null) {
                ratio = m.buySellRatio();
            }
            if (m.imbalanceMean() != null) {
                sum += m.imbalanceMean() * m.depthTicks();
            }
            up += m.upVolume();
            down += m.downVolume();
            ticks += m.ticks();
            depth += m.depthTicks();
        }
        return new BarMicro(id, tf, openTime, close, depth == 0 ? null : sum / depth, ratio, share(up, down), up, down, ticks, depth);
    }

    private static Double share(long up, long down) {
        return up + down == 0 ? null : (double) up / (up + down);
    }

    /** A new SIM session starts from nothing. */
    public synchronized void clear() {
        open.clear();
        last.clear();
        minutes.clear();
    }
}
