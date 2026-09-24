package money.hejje.jev;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import money.hejje.market.BarMicro;
import money.hejje.market.Candle;

/**
 * The Jev bot's state from Hejje's own data (plan M9.5, docs/jev.md "The Jev bot"). Pure. Numbers are turned into named
 * buckets in code (Jev does not compare numbers reliably); a field whose data is missing is left out, and without
 * order-book data the {@code book} block is absent (never a fake "balanced").
 */
public final class JevBotState {

    static final double FLAT_BPS = 8;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private JevBotState() {
    }

    /** The computed values behind one stock's block. Null where the data does not allow a value. */
    public record Features(double price, Double vwap, Double atr, Double rangePosition, Double r1, Double r5, Double r15, Double r60, Double relativeVolume,
            Double recentVolume, List<int[]> lastBars, Double imbalance, Double buySellRatio, Double flowShare) {

        public boolean hasBook() {
            return imbalance != null || buySellRatio != null || flowShare != null;
        }

        public boolean aboveVwap() {
            return vwap != null && price > vwap;
        }
    }

    /**
     * @param today    the session's M1 candles so far, oldest first (at least one)
     * @param baseline mean cumulative volume at each minute of the session over previous sessions (index = minutes since
     *                 09:15), or null
     * @param micro    the session's M1 order-book data, oldest first (may be empty)
     */
    public static Features features(List<Candle> today, double[] baseline, List<BarMicro> micro) {
        int n = today.size();
        Candle last = today.get(n - 1);
        double price = last.close().doubleValue();
        double pv = 0;
        double vol = 0;
        double hi = Double.NEGATIVE_INFINITY;
        double lo = Double.POSITIVE_INFINITY;
        long cumulative = 0;
        for (Candle c : today) {
            double typical = (c.high().doubleValue() + c.low().doubleValue() + c.close().doubleValue()) / 3;
            pv += typical * c.volume();
            vol += c.volume();
            hi = Math.max(hi, c.high().doubleValue());
            lo = Math.min(lo, c.low().doubleValue());
            cumulative += c.volume();
        }
        Double vwap = vol > 0 ? pv / vol : null;
        Double atr = atr(today, 14);
        Double rangePosition = hi > lo ? (price - lo) / (hi - lo) : null;
        Double relative = null;
        if (baseline != null && n - 1 < baseline.length && baseline[n - 1] > 0) {
            relative = cumulative / baseline[n - 1];
        }
        Double recent = null;
        if (n >= 15) {
            double last5 = today.subList(n - 5, n).stream().mapToLong(Candle::volume).average().orElse(0);
            double prior10 = today.subList(n - 15, n - 5).stream().mapToLong(Candle::volume).average().orElse(0);
            recent = prior10 > 0 ? last5 / prior10 : null;
        }
        List<int[]> bars = today.subList(Math.max(0, n - 10), n).stream().map(c -> {
            double open = c.open().doubleValue();
            return new int[] {(int) Math.round(bps(open, c.close().doubleValue())), (int) Math.round(bps(c.low().doubleValue(), c.high().doubleValue()))};
        }).toList();
        Double imbalance = null;
        Double ratio = null;
        Double flow = null;
        if (!micro.isEmpty() && micro.get(micro.size() - 1).openTime().equals(last.openTime())) {
            BarMicro m = micro.get(micro.size() - 1);
            imbalance = m.imbalanceClose();
            ratio = m.buySellRatio();
            if (micro.size() >= 5) {
                List<BarMicro> five = micro.subList(micro.size() - 5, micro.size());
                long up = five.stream().mapToLong(BarMicro::upVolume).sum();
                long down = five.stream().mapToLong(BarMicro::downVolume).sum();
                flow = up + down == 0 ? null : (double) up / (up + down);
            }
        }
        return new Features(price, vwap, atr, rangePosition, ret(today, 1), ret(today, 5), ret(today, 15), ret(today, 60), relative, recent, bars, imbalance,
                ratio, flow);
    }

    /** Mean true range of the last {@code period} bars (fewer while the session is young); null under two bars. */
    public static Double atr(List<Candle> bars, int period) {
        if (bars.size() < 2) {
            return null;
        }
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, bars.size() - period); i < bars.size(); i++) {
            Candle c = bars.get(i);
            double prevClose = bars.get(i - 1).close().doubleValue();
            double tr = Math.max(c.high().doubleValue() - c.low().doubleValue(),
                    Math.max(Math.abs(c.high().doubleValue() - prevClose), Math.abs(c.low().doubleValue() - prevClose)));
            sum += tr;
            count++;
        }
        return count == 0 || sum == 0 ? null : sum / count;
    }

    /** Return in bps from the close {@code minutes} bars back to the last close; null without that many bars. */
    static Double ret(List<Candle> bars, int minutes) {
        int n = bars.size();
        if (n <= minutes) {
            return null;
        }
        return bps(bars.get(n - 1 - minutes).close().doubleValue(), bars.get(n - 1).close().doubleValue());
    }

    static double bps(double from, double to) {
        return from == 0 ? 0 : (to - from) / from * 10_000;
    }

    /** The stock block with named buckets (and {@code last_bars}); the book fields go to {@link #book}. */
    public static ObjectNode stock(Features f) {
        ObjectNode s = JSON.objectNode();
        if (f.vwap() != null && f.atr() != null) {
            double d = (f.price() - f.vwap()) / f.atr();
            s.put("vwap_position", d > 1.5 ? "far_above" : d > 0.5 ? "above" : d >= -0.5 ? "near" : d >= -1.5 ? "below" : "far_below");
        }
        if (f.rangePosition() != null) {
            double r = f.rangePosition();
            s.put("day_range_position", r >= 0.8 ? "top" : r >= 0.6 ? "upper" : r >= 0.4 ? "middle" : r >= 0.2 ? "lower" : "bottom");
        }
        direction(s, "return_1m", f.r1());
        direction(s, "return_5m", f.r5());
        direction(s, "return_15m", f.r15());
        direction(s, "return_60m", f.r60());
        if (f.relativeVolume() != null) {
            double v = f.relativeVolume();
            s.put("relative_volume", v < 0.7 ? "light" : v < 1.5 ? "normal" : v < 3 ? "heavy" : "very_heavy");
        }
        if (f.recentVolume() != null) {
            double v = f.recentVolume();
            s.put("volume_last_5m_vs_prior_10m", v > 1.3 ? "rising" : v < 0.7 ? "falling" : "steady");
        }
        var bars = s.putArray("last_bars");
        for (int[] b : f.lastBars()) {
            bars.addObject().put("change_bps", b[0]).put("range_bps", b[1]);
        }
        return s;
    }

    /** The book block, or null without order-book data. */
    public static ObjectNode book(Features f) {
        if (!f.hasBook()) {
            return null;
        }
        ObjectNode b = JSON.objectNode();
        if (f.imbalance() != null) {
            b.put("book_imbalance", f.imbalance() > 0.2 ? "bid_heavy" : f.imbalance() < -0.2 ? "ask_heavy" : "balanced");
        }
        if (f.buySellRatio() != null) {
            b.put("buy_sell_quantity", f.buySellRatio() > 1.2 ? "buyers" : f.buySellRatio() < 1 / 1.2 ? "sellers" : "even");
        }
        if (f.flowShare() != null) {
            b.put("trade_flow_5m", f.flowShare() > 0.6 ? "buying" : f.flowShare() < 0.4 ? "selling" : "mixed");
        }
        return b;
    }

    static void direction(ObjectNode s, String field, Double bps) {
        if (bps != null) {
            s.put(field, bps > FLAT_BPS ? "up" : bps < -FLAT_BPS ? "down" : "flat");
        }
    }

    /**
     * The index block: NIFTY returns over 5/15/60 minutes, the share of the bot's universe above VWAP, and the regime,
     * Pulse and market condition labels (each left out when unknown).
     */
    public static ObjectNode index(List<Candle> nifty, Double aboveVwapShare, String trend, String volatility, String pulse, String marketCondition) {
        ObjectNode i = JSON.objectNode();
        direction(i, "nifty_5m", ret(nifty, 5));
        direction(i, "nifty_15m", ret(nifty, 15));
        direction(i, "nifty_60m", ret(nifty, 60));
        if (aboveVwapShare != null) {
            i.put("breadth_above_vwap", aboveVwapShare >= 2.0 / 3 ? "most" : aboveVwapShare >= 1.0 / 3 ? "half" : "few");
        }
        putIf(i, "regime_trend", trend);
        putIf(i, "regime_volatility", volatility);
        putIf(i, "pulse", pulse);
        putIf(i, "market_condition", marketCondition);
        return i;
    }

    private static void putIf(ObjectNode n, String k, String v) {
        if (v != null && !v.equals("UNKNOWN")) {
            n.put(k, v.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** What the position block is computed from. */
    public record PositionFacts(boolean longSide, double entry, double stop, double initialStop, double price, double best, long minutesHeld) {

        public double unrealisedBps() {
            return longSide ? bps(entry, price) : -bps(entry, price);
        }

        public double r() {
            double risk = Math.abs(entry - initialStop);
            return risk == 0 ? 0 : (longSide ? price - entry : entry - price) / risk;
        }

        public double bestBps() {
            return Math.max(0, longSide ? bps(entry, best) : -bps(entry, best));
        }

        public boolean stopAtBreakEven() {
            return longSide ? stop >= entry : stop <= entry;
        }
    }

    public static ObjectNode position(PositionFacts p) {
        ObjectNode n = JSON.objectNode();
        n.put("side", p.longSide() ? "long" : "short");
        n.put("unrealised_bps", Math.round(p.unrealisedBps()));
        n.put("unrealised_r", Math.round(p.r() * 10) / 10.0);
        n.put("best_bps", Math.round(p.bestBps()));
        n.put("giveback_bps", Math.round(Math.max(0, p.bestBps() - p.unrealisedBps())));
        n.put("minutes_held", p.minutesHeld());
        n.put("stop_at_break_even", p.stopAtBreakEven());
        return n;
    }
}
