package money.hejje.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Money;
import money.hejje.common.Side;
import money.hejje.ratings.BaseType;

/**
 * The SWING backtest on D1 bars (plan M11.5), pure and deterministic. Each M8.4 trade plan is walked from the session after
 * its detection:
 * <ul>
 *   <li><b>Before the trigger</b>, like the ledger: a base closing below its base low (a reversal trading at its stop)
 *       FAILED; {@code expireSessions} after detection EXPIRED.</li>
 *   <li><b>Trigger</b> ({@link Trigger#RANGE}, the live rule): the day's range reaches the pivot and the day did not open
 *       above the buy zone; with {@code volumeFilter} a base also needs the day's volume at {@code volumePace} × its
 *       50-session average. Entry at the pivot, or the open when it opened above it. A day whose close reached the pivot
 *       without an entry (no volume, above the zone) consumes the setup, as the live watcher would no longer see it READY.
 *       {@link Trigger#CLOSE} is the H5 ledger's: the first close at or above the pivot, entry at the pivot or a higher open.</li>
 *   <li><b>Exits</b>: from the next session a low at or below the stop fills at the stop, or at the open when the session
 *       opened through it (gap-through); a high at or above the goal at the goal, or a higher open. The stop wins when both
 *       happen in one bar (the order inside a bar is unknown: resolved against the trade). With {@code sameDayExits} (the
 *       live rule: the GTT is placed as soon as the entry fills) the entry session's own range is checked too, stop first.
 *       Time exit after {@code maxHoldingDays} sessions: at the next session's open ({@link TimeExit#NEXT_OPEN}, live) or
 *       that session's close ({@link TimeExit#CLOSE}, the ledger's expiry).</li>
 *   <li><b>Money</b>: the quantity risks {@code riskPerTrade} on the gap-adjusted stop distance
 *       ({@code entry − stop + gap% × entry}); delivery costs (with the DP charge) come off the gross.</li>
 * </ul>
 */
public final class SwingBacktest {

    private SwingBacktest() {
    }

    /** One daily bar. */
    public record Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {}

    /**
     * A trade plan as the ledger recorded it at detection. {@code supersededOn}: the session the ledger replaced this
     * untriggered cup with its cup-with-handle (it is not watched from then on), else null.
     */
    public record Plan(UUID baseId, UUID instrumentId, String symbol, BaseType type, LocalDate detectedDate, BigDecimal baseLow, BigDecimal pivot,
            BigDecimal buyHigh, BigDecimal stop, BigDecimal goal, LocalDate supersededOn) {

        public Plan(UUID baseId, UUID instrumentId, String symbol, BaseType type, LocalDate detectedDate, BigDecimal baseLow, BigDecimal pivot,
                BigDecimal buyHigh, BigDecimal stop, BigDecimal goal) {
            this(baseId, instrumentId, symbol, type, detectedDate, baseLow, pivot, buyHigh, stop, goal, null);
        }
    }

    public enum Trigger { RANGE, CLOSE }

    public enum TimeExit { NEXT_OPEN, CLOSE }

    public record Config(Trigger trigger, boolean volumeFilter, BigDecimal volumePace, boolean sameDayExits, int maxHoldingDays, TimeExit timeExit,
            int expireSessions, Money riskPerTrade, BigDecimal gapAllowancePct) {

        /** The live swing rules. */
        public static Config swing(BigDecimal volumePace, int maxHoldingDays, int expireSessions, Money riskPerTrade, BigDecimal gapAllowancePct) {
            return new Config(Trigger.RANGE, true, volumePace, true, maxHoldingDays, TimeExit.NEXT_OPEN, expireSessions, riskPerTrade, gapAllowancePct);
        }

        /** The H5 ledger's rules (M8.4 lifecycle), for the parity check. */
        public static Config ledger(int maxHoldSessions, int expireSessions, Money riskPerTrade, BigDecimal gapAllowancePct) {
            return new Config(Trigger.CLOSE, false, BigDecimal.ONE, false, maxHoldSessions, TimeExit.CLOSE, expireSessions, riskPerTrade, gapAllowancePct);
        }
    }

    /** Transaction costs of one fill (delivery: STT both sides, stamp on the buy, the DP charge on the sell). */
    @FunctionalInterface
    public interface Costs {
        Money of(Side side, int quantity, BigDecimal price);
    }

    public enum Exit { HIT_GOAL, STOPPED, TIME_EXIT }

    /**
     * A closed trade. {@code grossR} is the ledger's R ((exit − entry) / (entry − stop), two decimals); {@code netR} is the net
     * P&L over the money at risk ({@code quantity × (entry − stop)}). {@code gapFill}: the exit filled at an open that gapped
     * through the stop or the goal.
     */
    public record Trade(Plan plan, LocalDate entryDate, BigDecimal entry, LocalDate exitDate, BigDecimal exit, Exit reason, boolean gapFill, int quantity,
            int holdingDays, Double grossR, Money gross, Money costs, Money net, Double netR) {}

    /** Why a plan produced no trade. */
    public enum NoTrade { FAILED, EXPIRED, NO_VOLUME, ABOVE_BUY_ZONE, OPEN, NO_DATA }

    public record Result(List<Trade> trades, Map<NoTrade, Integer> notTraded) {}

    public static Result run(Map<UUID, List<Bar>> bars, List<Plan> plans, Config cfg, Costs costs) {
        List<Trade> trades = new ArrayList<>();
        Map<NoTrade, Integer> none = new LinkedHashMap<>();
        for (Plan p : plans) {
            List<Bar> series = bars.getOrDefault(p.instrumentId(), List.of());
            Object out = walk(series, p, cfg, costs);
            if (out instanceof Trade t) {
                trades.add(t);
            } else {
                none.merge((NoTrade) out, 1, Integer::sum);
            }
        }
        trades.sort((a, b) -> a.entryDate().compareTo(b.entryDate()) != 0 ? a.entryDate().compareTo(b.entryDate()) : a.plan().symbol().compareTo(b.plan().symbol()));
        return new Result(trades, none);
    }

    private static Object walk(List<Bar> s, Plan p, Config cfg, Costs costs) {
        int i0 = indexOf(s, p.detectedDate());
        if (i0 < 0) {
            return NoTrade.NO_DATA;
        }
        double pivot = p.pivot().doubleValue();
        for (int i = i0 + 1; i < s.size(); i++) {
            Bar b = s.get(i);
            if (p.supersededOn() != null && !b.date().isBefore(p.supersededOn())) {
                return NoTrade.EXPIRED;
            }
            boolean reached = cfg.trigger() == Trigger.CLOSE ? b.close().doubleValue() >= pivot : b.high().doubleValue() >= pivot;
            if (reached) {
                if (cfg.trigger() == Trigger.RANGE && b.open().compareTo(p.buyHigh()) > 0) {
                    return NoTrade.ABOVE_BUY_ZONE;
                }
                if (cfg.volumeFilter() && !p.type().reversal() && !volumeOk(s, i, cfg.volumePace())) {
                    if (b.close().doubleValue() >= pivot) {
                        return NoTrade.NO_VOLUME;
                    }
                } else {
                    BigDecimal entry = b.open().compareTo(p.pivot()) > 0 ? b.open() : p.pivot();
                    return trade(s, p, i, entry, cfg, costs);
                }
            }
            boolean broken = p.type().reversal() ? b.low().compareTo(p.stop()) <= 0 : b.close().compareTo(p.baseLow()) < 0;
            if (broken) {
                return NoTrade.FAILED;
            }
            if (i - i0 >= cfg.expireSessions()) {
                return NoTrade.EXPIRED;
            }
        }
        return NoTrade.OPEN;
    }

    private static Object trade(List<Bar> s, Plan p, int e, BigDecimal entry, Config cfg, Costs costs) {
        Bar entryBar = s.get(e);
        if (cfg.sameDayExits()) {
            if (entryBar.low().compareTo(p.stop()) <= 0) {
                return close(p, s, e, entry, e, p.stop(), Exit.STOPPED, false, cfg, costs);
            }
            if (entryBar.high().compareTo(p.goal()) >= 0) {
                return close(p, s, e, entry, e, p.goal(), Exit.HIT_GOAL, false, cfg, costs);
            }
        }
        for (int d = e + 1; d < s.size(); d++) {
            Bar b = s.get(d);
            if (cfg.timeExit() == TimeExit.NEXT_OPEN && d - e > cfg.maxHoldingDays()) {
                return close(p, s, e, entry, d, b.open(), Exit.TIME_EXIT, false, cfg, costs);
            }
            if (b.low().compareTo(p.stop()) <= 0) {
                boolean gap = b.open().compareTo(p.stop()) < 0;
                return close(p, s, e, entry, d, gap ? b.open() : p.stop(), Exit.STOPPED, gap, cfg, costs);
            }
            if (b.high().compareTo(p.goal()) >= 0) {
                boolean gap = b.open().compareTo(p.goal()) > 0;
                return close(p, s, e, entry, d, gap ? b.open() : p.goal(), Exit.HIT_GOAL, gap, cfg, costs);
            }
            if (cfg.timeExit() == TimeExit.CLOSE && d - e >= cfg.maxHoldingDays()) {
                return close(p, s, e, entry, d, b.close(), Exit.TIME_EXIT, false, cfg, costs);
            }
        }
        return NoTrade.OPEN;
    }

    private static Trade close(Plan p, List<Bar> s, int e, BigDecimal entry, int x, BigDecimal exit, Exit reason, boolean gap, Config cfg, Costs costs) {
        BigDecimal exitPrice = exit.setScale(2, RoundingMode.HALF_UP);
        BigDecimal risk = entry.subtract(p.stop());
        BigDecimal perShare = risk.add(entry.multiply(cfg.gapAllowancePct()).movePointLeft(2));
        int qty = perShare.signum() <= 0 ? 1 : Math.max(1, cfg.riskPerTrade().toRupees().divide(perShare, 0, RoundingMode.FLOOR).intValue());
        Money gross = Money.of(exitPrice.subtract(entry).multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP));
        Money cost = costs.of(Side.BUY, qty, entry).plus(costs.of(Side.SELL, qty, exitPrice));
        Money net = gross.minus(cost);
        Double grossR = risk.signum() <= 0 ? null : Math.round((exitPrice.doubleValue() - entry.doubleValue()) / risk.doubleValue() * 100.0) / 100.0;
        Double netR = risk.signum() <= 0 ? null
                : Math.round(net.toRupees().doubleValue() / (qty * risk.doubleValue()) * 1000.0) / 1000.0;
        return new Trade(p, s.get(e).date(), entry, s.get(x).date(), exitPrice, reason, gap, qty, x - e, grossR, gross, cost, net, netR);
    }

    private static boolean volumeOk(List<Bar> s, int i, BigDecimal pace) {
        if (i < 50) {
            return false;
        }
        long sum = 0;
        for (int j = i - 50; j < i; j++) {
            sum += s.get(j).volume();
        }
        return sum > 0 && BigDecimal.valueOf(s.get(i).volume()).multiply(BigDecimal.valueOf(50)).compareTo(pace.multiply(BigDecimal.valueOf(sum))) >= 0;
    }

    private static int indexOf(List<Bar> s, LocalDate date) {
        for (int i = 0; i < s.size(); i++) {
            if (s.get(i).date().equals(date)) {
                return i;
            }
        }
        return -1;
    }
}
