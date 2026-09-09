package money.hejje.regime.internal;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import money.hejje.market.Candle;
import money.hejje.market.indicators.Adx;
import money.hejje.market.indicators.Atr;
import money.hejje.market.indicators.Bar;
import money.hejje.market.indicators.Ema;
import money.hejje.regime.RegimeProperties;

/**
 * Walks the index and VIX daily series forward, one session at a time, producing a {@link DailyState} per index bar.
 * Feeding bars strictly in order keeps every state free of look-ahead: the state for a session only knows bars up to
 * and including that session. Percentiles are ranks of the latest value within the trailing window (inclusive).
 */
final class DailyWalker {

    private final RegimeProperties props;
    private final ZoneId zone;
    private final Ema emaFast;
    private final Ema emaSlow;
    private final Adx adx;
    private final Atr atr;
    private final Deque<Double> vixWindow = new ArrayDeque<>();
    private final Deque<Double> atrRatioWindow = new ArrayDeque<>();
    private final TreeMap<LocalDate, Double> vixByDate;
    private double prevClose = Double.NaN;
    private double prevAtr = Double.NaN;
    private int sessions;

    DailyWalker(RegimeProperties props, ZoneId zone, List<Candle> vixDaily) {
        this.props = props;
        this.zone = zone;
        this.emaFast = new Ema(props.trend().emaFast());
        this.emaSlow = new Ema(props.trend().emaSlow());
        this.adx = new Adx(props.trend().adxPeriod());
        this.atr = new Atr(props.volatility().atrPeriod());
        this.vixByDate = new TreeMap<>();
        for (Candle c : vixDaily) {
            vixByDate.put(c.openTime().atZone(zone).toLocalDate(), c.close().doubleValue());
        }
    }

    /** Feeds every bar and returns the state after each one, in order. */
    List<DailyState> walk(List<Candle> indexDaily) {
        List<DailyState> out = new ArrayList<>(indexDaily.size());
        for (Candle c : indexDaily) {
            out.add(feed(c));
        }
        return out;
    }

    DailyState feed(Candle candle) {
        Bar bar = Bar.of(candle, zone);
        LocalDate date = bar.session();
        emaFast.update(bar);
        emaSlow.update(bar);
        adx.update(bar);
        double atrBefore = prevAtr;
        atr.update(bar);
        sessions++;
        double atrNow = atr.value().orElse(Double.NaN);
        Double atrRatioPct = null;
        if (!Double.isNaN(atrNow) && bar.close() > 0) {
            atrRatioPct = pushAndRank(atrRatioWindow, atrNow / bar.close());
        }
        Map.Entry<LocalDate, Double> vixEntry = vixByDate.floorEntry(date);
        double vix = vixEntry == null ? Double.NaN : vixEntry.getValue();
        Double vixPct = Double.isNaN(vix) ? null : pushAndRank(vixWindow, vix);
        DailyState state = new DailyState(date, sessions, bar.open(), bar.close(), emaFast.value().orElse(Double.NaN), emaSlow.value().orElse(Double.NaN),
                back(emaFast, props.trend().slopeSessions()), adx.value().orElse(Double.NaN), atrBefore, prevClose, vix, vixPct, atrRatioPct,
                Math.max(vixWindow.size(), atrRatioWindow.size()));
        prevClose = bar.close();
        prevAtr = atrNow;
        return state;
    }

    private static double back(Ema ema, int offset) {
        OptionalDouble v = ema.value(offset);
        return v.isPresent() ? v.getAsDouble() : Double.NaN;
    }

    /** Appends to the trailing window (bounded by the lookback) and returns the percentile rank of the new value. */
    private Double pushAndRank(Deque<Double> window, double value) {
        window.addLast(value);
        while (window.size() > props.lookbackSessions()) {
            window.removeFirst();
        }
        if (window.size() < props.minSessions()) {
            return null;
        }
        long below = window.stream().filter(v -> v < value).count();
        long equal = window.stream().filter(v -> v == value).count();
        return 100.0 * (below + 0.5 * equal) / window.size();
    }
}
