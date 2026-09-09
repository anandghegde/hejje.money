package money.hejje.scoring.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.orders.OrderService;
import money.hejje.orders.Trade;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoreAdjuster;
import money.hejje.scoring.ScoreContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Trailing paper/live performance versus the backtest: the last 20 round trips attributed to the strategy (fills
 * paired per instrument until the net quantity returns to zero) are compared with the base backtest's expectancy in
 * money. Ratio ≥ 1 → +5, ≥ 0.5 → +2, ≥ 0 → −2, negative → −5; no round trips → 0.
 */
@Component
@Order(20)
public class RecentPerformanceAdjuster implements ScoreAdjuster {

    static final int ROUND_TRIPS = 20;
    static final int LOOKBACK_DAYS = 60;

    private final OrderService orders;
    private final HejjeProperties properties;
    private final HejjeClock clock;

    RecentPerformanceAdjuster(OrderService orders, HejjeProperties properties, HejjeClock clock) {
        this.orders = orders;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "Recent paper/live performance";
    }

    @Override
    public int min() {
        return -5;
    }

    @Override
    public int max() {
        return 5;
    }

    @Override
    public Adjustment adjust(ScoreContext ctx) {
        Instant now = clock.now();
        UUID strategyId = ctx.version().strategyId();
        List<Trade> fills = orders.trades(properties.mode(), now.minus(Duration.ofDays(LOOKBACK_DAYS)), now).stream()
                .filter(t -> strategyId.equals(t.strategyId())).sorted((a, b) -> a.ts().compareTo(b.ts())).toList();
        List<BigDecimal> roundTrips = roundTrips(fills);
        if (roundTrips.isEmpty()) {
            return Adjustment.none(name(), min(), max(), "no paper/live round trips for this strategy in the last " + LOOKBACK_DAYS + " days");
        }
        List<BigDecimal> recent = roundTrips.subList(Math.max(0, roundTrips.size() - ROUND_TRIPS), roundTrips.size());
        BigDecimal avg = recent.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(recent.size()), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal expected = ctx.baseBacktest().map(b -> b.metrics() == null ? null : b.metrics().expectancyMoney().toRupees()).orElse(null);
        int delta;
        String comparison;
        if (expected == null || expected.signum() <= 0) {
            delta = avg.signum() > 0 ? 2 : -2;
            comparison = "no positive backtest expectancy to compare against";
        } else {
            double ratio = avg.doubleValue() / expected.doubleValue();
            delta = ratio >= 1 ? 5 : ratio >= 0.5 ? 2 : ratio >= 0 ? -2 : -5;
            comparison = String.format("%.0f%% of the backtest expectancy of %s per trade", ratio * 100, expected.toPlainString());
        }
        return new Adjustment(name(), delta, min(), max(), List.of(
                "last " + recent.size() + " round trips average " + avg.toPlainString() + " per trade (gross)", comparison));
    }

    /** Gross P&L of each completed round trip, per instrument, in time order. */
    public static List<BigDecimal> roundTrips(List<Trade> fills) {
        Map<UUID, int[]> net = new HashMap<>();
        Map<UUID, BigDecimal> cash = new HashMap<>();
        List<BigDecimal> out = new ArrayList<>();
        for (Trade t : fills) {
            int[] q = net.computeIfAbsent(t.instrumentId(), k -> new int[1]);
            BigDecimal value = t.price().multiply(BigDecimal.valueOf(t.quantity()));
            cash.merge(t.instrumentId(), t.side() == Side.BUY ? value.negate() : value, BigDecimal::add);
            q[0] += t.side() == Side.BUY ? t.quantity() : -t.quantity();
            if (q[0] == 0) {
                out.add(cash.remove(t.instrumentId()));
            }
        }
        return out;
    }
}
