package money.hejje.risk.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.Funds;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Side;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.MarketService;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.orders.Trade;
import money.hejje.risk.AccountSnapshot;
import org.springframework.stereotype.Component;

/** Assembles the {@link AccountSnapshot} the risk engine reasons over. */
@Component
public class AccountSnapshotBuilder {

    private final OrderService orders;
    private final MarketService market;
    private final BrokerAdapter broker;
    private final HejjeClock clock;

    AccountSnapshotBuilder(OrderService orders, MarketService market, BrokerAdapter broker, HejjeClock clock) {
        this.orders = orders;
        this.market = market;
        this.broker = broker;
        this.clock = clock;
    }

    public AccountSnapshot build(ExecutionMode mode) {
        List<Position> positions = orders.positions(mode);
        Money realized = Money.ZERO;
        Money unrealized = Money.ZERO;
        Money gross = Money.ZERO;
        int open = 0;
        Map<UUID, Integer> netByInstrument = new HashMap<>();
        for (Position p : positions) {
            realized = realized.plus(p.realizedPnl());
            netByInstrument.merge(p.instrumentId(), p.netQuantity(), Integer::sum);
            if (!p.isFlat()) {
                open++;
                BigDecimal ltp = market.lastPrice(p.instrumentId()).orElse(p.averagePrice());
                unrealized = unrealized.plus(Money.of(ltp.subtract(p.averagePrice()).multiply(BigDecimal.valueOf(p.netQuantity())).setScale(2, RoundingMode.HALF_UP)));
                gross = gross.plus(Money.of(ltp.multiply(BigDecimal.valueOf(Math.abs(p.netQuantity()))).setScale(2, RoundingMode.HALF_UP)));
            }
        }

        Instant startOfDay = clock.today().atStartOfDay(clock.zone()).toInstant();
        List<Trade> todaysTrades = orders.trades(mode, startOfDay, clock.now());
        Map<UUID, Instant> lastTradeAt = new HashMap<>();
        for (Trade t : todaysTrades) {
            lastTradeAt.merge(t.instrumentId(), t.ts(), (a, b) -> a.isAfter(b) ? a : b);
        }

        Money availableCash = Money.ZERO;
        Money usedMargin = Money.ZERO;
        try {
            Funds funds = broker.getFunds();
            availableCash = funds.availableCash();
            usedMargin = funds.usedMargin();
        } catch (BrokerException ignored) {
            // margin unavailable; checks fall back to notional
        }

        Day day = day(todaysTrades);
        return new AccountSnapshot(realized, unrealized, open, gross, todaysTrades.size(), day.consecutiveLosses(), availableCash, usedMargin,
                netByInstrument, lastTradeAt, day.entries(), day.closes());
    }

    /** Today's entries (first fill per opening order), closed round trips, and the trailing losses among them. */
    record Day(List<Instant> entries, List<AccountSnapshot.Close> closes, int consecutiveLosses) {}

    static int consecutiveLosses(List<Trade> trades) {
        return day(trades).consecutiveLosses();
    }

    /** Reconstructs closed round-trips from today's trades (average cost, per instrument) and counts trailing losses. */
    static Day day(List<Trade> trades) {
        Map<UUID, Instant> entryOrders = new java.util.LinkedHashMap<>();
        List<Trade> ordered = new ArrayList<>(trades);
        ordered.sort((a, b) -> a.ts().compareTo(b.ts()));
        Map<UUID, int[]> net = new HashMap<>();           // net qty
        Map<UUID, BigDecimal> avg = new HashMap<>();
        Map<UUID, BigDecimal> realizedAccum = new HashMap<>();
        record Close(Instant ts, BigDecimal realized) {}
        List<Close> closes = new ArrayList<>();
        for (Trade t : ordered) {
            UUID k = t.instrumentId();
            int n = net.getOrDefault(k, new int[]{0})[0];
            BigDecimal a = avg.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal racc = realizedAccum.getOrDefault(k, BigDecimal.ZERO);
            int signed = t.side() == Side.BUY ? t.quantity() : -t.quantity();
            if (n == 0 || Integer.signum(n) == Integer.signum(signed)) {
                if (t.orderId() != null) {
                    entryOrders.putIfAbsent(t.orderId(), t.ts());
                }
                BigDecimal total = a.multiply(BigDecimal.valueOf(Math.abs(n))).add(t.price().multiply(BigDecimal.valueOf(t.quantity())));
                n += signed;
                a = total.divide(BigDecimal.valueOf(Math.abs(n)), 2, RoundingMode.HALF_UP);
            } else {
                int closing = Math.min(Math.abs(n), t.quantity());
                BigDecimal perUnit = n > 0 ? t.price().subtract(a) : a.subtract(t.price());
                racc = racc.add(perUnit.multiply(BigDecimal.valueOf(closing)));
                n += signed;
                if (n == 0) {
                    closes.add(new Close(t.ts(), racc));
                    racc = BigDecimal.ZERO;
                    a = BigDecimal.ZERO;
                } else if (Integer.signum(n) != Integer.signum(n - signed)) {
                    a = t.price();
                }
            }
            net.put(k, new int[]{n});
            avg.put(k, a);
            realizedAccum.put(k, racc);
        }
        closes.sort((x, y) -> x.ts().compareTo(y.ts()));
        int count = 0;
        for (int i = closes.size() - 1; i >= 0; i--) {
            if (closes.get(i).realized().signum() < 0) {
                count++;
            } else {
                break;
            }
        }
        List<Instant> entries = entryOrders.values().stream().sorted().toList();
        return new Day(entries, closes.stream().map(c -> new AccountSnapshot.Close(c.ts(), c.realized())).toList(), count);
    }
}
