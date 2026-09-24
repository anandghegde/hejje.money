package money.hejje.calibration.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import money.hejje.calibration.Prediction;
import money.hejje.common.Side;
import money.hejje.market.Candle;

/**
 * The outcome rules of docs/calibration.md over M1 candles (plan M9.2). Pure. {@code bars} are the M1 candles opening
 * in {@code [decidedAt, windowEnd)}, oldest first; the first one's open is the reference price. Returns null when there
 * is no bar to decide on (the caller keeps the prediction pending, or labels it NONE once the data is not coming).
 */
public final class Labeler {

    public enum Outcome { HIT, MISS, NONE }

    public record Label(Outcome outcome, Map<String, Object> evidence) {}

    private Labeler() {}

    /** {@link money.hejje.calibration.LabelRule#ENTRY_1R}: entry at the reference open, R = |entry − stop|. */
    public static Label entry(Prediction p, List<Candle> bars) {
        if (bars.isEmpty()) {
            return null;
        }
        boolean longSide = p.side() == Side.BUY;
        BigDecimal entry = bars.get(0).open();
        BigDecimal risk = entry.subtract(p.stop()).abs();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("entry", entry);
        ev.put("stop", p.stop());
        ev.put("entryBar", bars.get(0).openTime().toString());
        boolean stopOnLosingSide = longSide ? p.stop().compareTo(entry) < 0 : p.stop().compareTo(entry) > 0;
        if (!stopOnLosingSide) {
            ev.put("reason", "the reference open is already through the stop");
            return new Label(Outcome.MISS, ev);
        }
        BigDecimal target = longSide ? entry.add(risk) : entry.subtract(risk);
        BigDecimal stop = longSide ? entry.subtract(risk) : entry.add(risk);
        ev.put("plus1R", target);
        ev.put("minus1R", stop);
        for (Candle c : bars) {
            boolean stopHit = longSide ? c.low().compareTo(stop) <= 0 : c.high().compareTo(stop) >= 0;
            boolean targetHit = longSide ? c.high().compareTo(target) >= 0 : c.low().compareTo(target) <= 0;
            if (stopHit || targetHit) {
                ev.put("decidedBy", c.openTime().toString());
                if (stopHit && targetHit) {
                    ev.put("reason", "both in one bar: −1R first (pessimistic)");
                }
                return new Label(stopHit ? Outcome.MISS : Outcome.HIT, ev);
            }
        }
        ev.put("reason", "neither within the window");
        ev.put("bars", bars.size());
        return new Label(Outcome.NONE, ev);
    }

    /** Direction rules: sign of the return from the reference open to the last bar's close, against the side. */
    public static Label direction(Prediction p, List<Candle> bars) {
        if (bars.isEmpty()) {
            return null;
        }
        BigDecimal from = bars.get(0).open();
        BigDecimal to = bars.get(bars.size() - 1).close();
        return direction(p, from, to, bars.get(0).openTime(), bars.get(bars.size() - 1).openTime());
    }

    static Label direction(Prediction p, BigDecimal from, BigDecimal to, Instant fromBar, Instant toBar) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("from", from);
        ev.put("to", to);
        ev.put("fromBar", fromBar.toString());
        ev.put("toBar", toBar.toString());
        int sign = to.compareTo(from) * (p.side() == Side.BUY ? 1 : -1);
        return new Label(sign > 0 ? Outcome.HIT : sign < 0 ? Outcome.MISS : Outcome.NONE, ev);
    }

    /** {@link money.hejje.calibration.LabelRule#EXIT}: the exit was right when the adverse move beats the favourable one. */
    public static Label exit(Prediction p, List<Candle> bars) {
        if (bars.isEmpty()) {
            return null;
        }
        boolean longSide = p.side() == Side.BUY;
        BigDecimal ref = bars.get(0).open();
        BigDecimal high = ref;
        BigDecimal low = ref;
        for (Candle c : bars) {
            high = high.max(c.high());
            low = low.min(c.low());
        }
        BigDecimal favourable = longSide ? high.subtract(ref) : ref.subtract(low);
        BigDecimal adverse = longSide ? ref.subtract(low) : high.subtract(ref);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("reference", ref);
        ev.put("favourable", favourable);
        ev.put("adverse", adverse);
        ev.put("bars", bars.size());
        return new Label(adverse.compareTo(favourable) > 0 ? Outcome.HIT : Outcome.MISS, ev);
    }
}
