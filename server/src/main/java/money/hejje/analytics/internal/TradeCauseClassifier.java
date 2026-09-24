package money.hejje.analytics.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import money.hejje.analytics.TradeCause;
import money.hejje.analytics.TradeCause.Cause;
import money.hejje.analytics.TradeCause.Timing;
import money.hejje.analytics.TradeCauseProperties;
import money.hejje.market.Candle;

/**
 * The trade-cause and entry-timing rules of docs/analytics.md (plan M9.6). Pure: the same trade and candles give the
 * same answer. Rules in order: BAD_ENTRY, CLEAN_TARGET, NOISE_STOP, THESIS_BREAK, DRIFT, else UNKNOWN.
 */
public final class TradeCauseClassifier {

    private TradeCauseClassifier() {
    }

    /**
     * One closed trade. {@code bars} are the M1 candles of the entry's session from its open through the post-exit
     * window (whatever exists), oldest first; {@code sessionClose} cuts the post-exit window.
     */
    public record Trade(boolean longSide, BigDecimal entry, BigDecimal exit, BigDecimal stop, String closeReason, Instant openedAt, Instant closedAt,
            Instant sessionClose, Instant now) {}

    public static TradeCause classify(Trade t, List<Candle> bars, TradeCauseProperties p) {
        Map<String, Object> ev = new LinkedHashMap<>();
        if (t.stop() == null || t.entry().subtract(t.stop()).signum() == 0) {
            ev.put("reason", "no stop: R is unknown");
            return new TradeCause(Cause.UNKNOWN, null, null, null, ev, null, null, true);
        }
        double entry = t.entry().doubleValue();
        double r = Math.abs(entry - t.stop().doubleValue());
        int dir = t.longSide() ? 1 : -1;
        ev.put("r", round(r));

        List<Candle> pre = bars.stream().filter(c -> !c.openTime().plusSeconds(60).isAfter(t.openedAt())).toList();
        Instant entryMinute = t.openedAt().minusSeconds(t.openedAt().getEpochSecond() % 60);
        List<Candle> during = bars.stream().filter(c -> !c.openTime().isBefore(entryMinute) && c.openTime().isBefore(t.closedAt())).toList();
        Instant postEnd = min(t.closedAt().plus(Duration.ofMinutes(p.postExitMinutes())), t.sessionClose());
        List<Candle> post = bars.stream().filter(c -> !c.openTime().isBefore(t.closedAt()) && c.openTime().isBefore(postEnd)).toList();
        boolean partial = postEnd.isBefore(t.closedAt().plus(Duration.ofMinutes(p.postExitMinutes())))
                || post.isEmpty() || post.get(post.size() - 1).openTime().plusSeconds(60).isBefore(postEnd);
        if (partial) {
            ev.put("partialWindow", true);
        }

        // MFE / MAE in R while the trade was open
        double best = entry;
        double worst = entry;
        Integer firstMae = null;
        Integer firstMfe = null;
        for (int i = 0; i < during.size(); i++) {
            Candle c = during.get(i);
            double fav = dir > 0 ? c.high().doubleValue() : c.low().doubleValue();
            double adv = dir > 0 ? c.low().doubleValue() : c.high().doubleValue();
            best = dir > 0 ? Math.max(best, fav) : Math.min(best, fav);
            worst = dir > 0 ? Math.min(worst, adv) : Math.max(worst, adv);
            if (firstMae == null && dir * (worst - entry) / r <= p.earlyMaeR()) {
                firstMae = i;
            }
            if (firstMfe == null && dir * (best - entry) / r >= p.earlyMfeR()) {
                firstMfe = i;
            }
        }
        double mfeR = round(dir * (best - entry) / r);
        double maeR = round(Math.min(0, dir * (worst - entry) / r));
        double outcomeR = round(dir * (t.exit().doubleValue() - entry) / r);
        ev.put("mfeR", mfeR);
        ev.put("maeR", maeR);
        ev.put("outcomeR", outcomeR);

        // pre-entry extension: the move over the previous minutes and the distance from VWAP, in ATR(14) of M1
        Double atr = atr(pre, 14);
        Cause cause = null;
        if (atr != null && !pre.isEmpty()) {
            double last = pre.get(pre.size() - 1).close().doubleValue();
            int back = Math.min(p.preEntryMinutes(), pre.size() - 1);
            double move = dir * (last - pre.get(pre.size() - 1 - back).close().doubleValue()) / atr;
            Double vwap = vwap(pre);
            double fromVwap = vwap == null ? 0 : dir * (entry - vwap) / atr;
            ev.put("atr", round(atr));
            ev.put("preEntryMoveAtr", round(move));
            ev.put("fromVwapAtr", round(fromVwap));
            if (move >= p.extendedAtr() || fromVwap >= p.vwapAtr()) {
                cause = Cause.BAD_ENTRY;
            }
        }
        String reason = t.closeReason() == null ? "" : t.closeReason();
        boolean stopped = reason.equals("STOP") || reason.equals("TRAILING_STOP") || reason.equals("SOFTWARE_STOP");
        if (cause == null && reason.equals("TARGET") && maeR > p.cleanTargetMaeR()) {
            cause = Cause.CLEAN_TARGET;
        }
        if (cause == null && stopped) {
            double recovered = post.stream().mapToDouble(c -> dir * ((dir > 0 ? c.high() : c.low()).doubleValue() - entry) / r).max().orElse(Double.NEGATIVE_INFINITY);
            ev.put("postExitBestR", Double.isFinite(recovered) ? round(recovered) : null);
            cause = recovered >= p.noiseRecoveryR() ? Cause.NOISE_STOP : Cause.THESIS_BREAK;
        }
        if (cause == null && (reason.equals("RULE_EXIT") || reason.equals("MANUAL"))) {
            cause = Cause.THESIS_BREAK;
        }
        if (cause == null && (reason.equals("MAX_HOLDING") || reason.equals("FORCE_EXIT")) && Math.abs(outcomeR) < p.driftR()) {
            cause = Cause.DRIFT;
        }
        if (cause == null) {
            cause = Cause.UNKNOWN;
        }

        // entry timing
        Timing timing = Timing.GOOD;
        if (firstMae != null && (firstMfe == null || firstMae < firstMfe)) {
            timing = Timing.EARLY;
        } else if (mfeR < p.lateMfeR()) {
            List<Candle> range = pre.subList(Math.max(0, pre.size() - p.rangeMinutes()), pre.size());
            if (!range.isEmpty()) {
                double hi = range.stream().mapToDouble(c -> c.high().doubleValue()).max().orElse(entry);
                double lo = range.stream().mapToDouble(c -> c.low().doubleValue()).min().orElse(entry);
                if (hi > lo) {
                    double position = (entry - lo) / (hi - lo);
                    ev.put("entryInRange", round(position));
                    if (dir > 0 ? position >= 1 - p.lateRangeShare() : position <= p.lateRangeShare()) {
                        timing = Timing.LATE;
                    }
                }
            }
        }
        boolean complete = !t.now().isBefore(t.closedAt().plus(Duration.ofMinutes(p.postExitMinutes() + 5))) || !t.now().isBefore(t.sessionClose());
        return new TradeCause(cause, timing, mfeR, maeR, ev, null, null, complete);
    }

    /** Mean true range of the last {@code period} bars; null under two bars. */
    static Double atr(List<Candle> bars, int period) {
        if (bars.size() < 2) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (int i = Math.max(1, bars.size() - period); i < bars.size(); i++) {
            Candle c = bars.get(i);
            double prev = bars.get(i - 1).close().doubleValue();
            sum += Math.max(c.high().doubleValue() - c.low().doubleValue(), Math.max(Math.abs(c.high().doubleValue() - prev), Math.abs(c.low().doubleValue() - prev)));
            n++;
        }
        return n == 0 || sum == 0 ? null : sum / n;
    }

    static Double vwap(List<Candle> bars) {
        double pv = 0;
        double v = 0;
        for (Candle c : bars) {
            pv += (c.high().doubleValue() + c.low().doubleValue() + c.close().doubleValue()) / 3 * c.volume();
            v += c.volume();
        }
        return v == 0 ? null : pv / v;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
