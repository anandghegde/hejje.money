package money.hejje.ratings.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;
import money.hejje.ratings.Base;
import money.hejje.ratings.BaseStatus;
import money.hejje.ratings.RatingsProperties;

/**
 * The trade plan of a detected base and its status, advanced one session at a time (docs/ratings.md, "Lifecycle").
 * Trigger = the first close at or above the pivot; the assumed entry is the pivot, or that session's open when it
 * opened above the pivot. From the next session on a low at or below the stop closes it as STOPPED and a high at or
 * above the goal as HIT_GOAL (a gap through either fills at the open; when both happen in one session the stop wins).
 */
final class BaseLifecycle {

    private final RatingsProperties.Bases cfg;

    BaseLifecycle(RatingsProperties.Bases cfg) {
        this.cfg = cfg;
    }

    /** The base as detected on session {@code d.end()}, with its plan and its first status. */
    Base open(RatingsEngine.Member m, BaseDetector.Detected d, String version) {
        DailySeries s = m.series();
        BigDecimal tick = m.tick();
        BigDecimal pivot = toTick(d.pivot(), tick);
        boolean reversal = d.type().reversal();
        BigDecimal stop = toTick(reversal ? d.stop() : d.pivot() * (1 - cfg.stopPct() / 100.0), tick);
        BigDecimal goal = toTick(d.pivot() * (1 + (reversal ? cfg.reversalGoalPct() : cfg.goalPct()) / 100.0), tick);
        LocalDate start = s.date(d.start());
        UUID id = UUID.nameUUIDFromBytes((m.id() + "|" + d.type() + "|" + start + "|" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Base(id, m.id(), m.symbol(), d.type(), version, start, s.date(d.end()), Math.round(d.depthPct() * 100.0) / 100.0,
                toTick(d.baseLow(), tick), pivot, pivot, toTick(d.pivot() * (1 + cfg.buyZonePct() / 100.0), tick), stop, goal, d.evidence(),
                waiting(s.close()[d.end()], pivot.doubleValue()), s.date(d.end()), null, null, null, null, null, null);
    }

    /** The base after session {@code i} (a session after the one it was last advanced to). Returns the same instance when nothing changed. */
    Base advance(Base b, DailySeries s, int i) {
        double close = s.close()[i];
        double pivot = b.pivot().doubleValue();
        LocalDate date = s.date(i);
        if (b.triggerDate() == null) {
            if (close >= pivot) {
                BigDecimal entry = s.open()[i] > pivot ? BigDecimal.valueOf(s.open()[i]).setScale(2, RoundingMode.HALF_UP) : b.pivot();
                return b.withStatus(close <= b.buyHigh().doubleValue() ? BaseStatus.IN_BUY_ZONE : BaseStatus.EXTENDED, date, date, entry,
                        volumeConfirmed(s, i), null, null, null);
            }
            boolean broken = b.type().reversal() ? s.low()[i] <= b.stop().doubleValue() : close < b.baseLow().doubleValue();
            if (broken) {
                return b.withStatus(BaseStatus.FAILED, date, null, null, null, null, null, null);
            }
            if (i - s.indexOf(b.detectedDate()) >= cfg.expireSessions()) {
                return b.withStatus(BaseStatus.EXPIRED, date, null, null, null, null, null, null);
            }
            return changed(b, waiting(close, pivot), date);
        }
        double stop = b.stop().doubleValue();
        double goal = b.goal().doubleValue();
        if (s.low()[i] <= stop) {
            return closed(b, BaseStatus.STOPPED, date, Math.min(s.open()[i], stop));
        }
        if (s.high()[i] >= goal) {
            return closed(b, BaseStatus.HIT_GOAL, date, Math.max(s.open()[i], goal));
        }
        if (i - s.indexOf(b.triggerDate()) >= cfg.maxHoldSessions()) {
            return closed(b, BaseStatus.EXPIRED, date, close);
        }
        return changed(b, close > b.buyHigh().doubleValue() ? BaseStatus.EXTENDED : close >= pivot ? BaseStatus.IN_BUY_ZONE : BaseStatus.PULLBACK, date);
    }

    private BaseStatus waiting(double close, double pivot) {
        return close >= pivot * (1 - cfg.nearPivotPct() / 100.0) ? BaseStatus.NEAR_PIVOT : BaseStatus.FORMING;
    }

    private static Base changed(Base b, BaseStatus status, LocalDate date) {
        return status == b.status() ? b : b.withStatus(status, date, b.triggerDate(), b.entry(), b.volumeConfirmed(), null, null, null);
    }

    private static Base closed(Base b, BaseStatus status, LocalDate date, double exit) {
        double entry = b.entry().doubleValue();
        double risk = entry - b.stop().doubleValue();
        return b.withStatus(status, date, b.triggerDate(), b.entry(), b.volumeConfirmed(), BigDecimal.valueOf(exit).setScale(2, RoundingMode.HALF_UP),
                Math.round((exit / entry - 1.0) * 10000.0) / 100.0, risk <= 0 ? null : Math.round((exit - entry) / risk * 100.0) / 100.0);
    }

    /** The session's volume against the mean of the previous 50 sessions; null without that much history. */
    private Boolean volumeConfirmed(DailySeries s, int i) {
        if (i < 50) {
            return null;
        }
        double sum = 0;
        for (int j = i - 50; j < i; j++) {
            sum += s.volume()[j];
        }
        return sum > 0 && s.volume()[i] >= cfg.breakoutVolume() * sum / 50;
    }

    static BigDecimal toTick(double price, BigDecimal tick) {
        BigDecimal steps = BigDecimal.valueOf(price).divide(tick, 0, RoundingMode.HALF_UP);
        return steps.multiply(tick).setScale(2, RoundingMode.HALF_UP);
    }
}
