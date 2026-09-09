package money.hejje.analytics;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import money.hejje.analytics.internal.ReviewStore;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.OrderService;
import money.hejje.orders.Trade;
import money.hejje.signals.SignalService;
import money.hejje.signals.StrategyPosition;
import money.hejje.strategy.StrategyService;
import org.springframework.stereotype.Service;

/** Public API of the analytics module: round trips, P&L breakdowns and reviews. */
@Service
public class AnalyticsService {

    public static final String MANUAL = "MANUAL";

    private final OrderService orders;
    private final SignalService signals;
    private final StrategyService strategies;
    private final InstrumentService instruments;
    private final ReviewStore reviews;
    private final HejjeProperties properties;
    private final HejjeClock clock;

    AnalyticsService(OrderService orders, SignalService signals, StrategyService strategies, InstrumentService instruments, ReviewStore reviews,
            HejjeProperties properties, HejjeClock clock) {
        this.orders = orders;
        this.signals = signals;
        this.strategies = strategies;
        this.instruments = instruments;
        this.reviews = reviews;
        this.properties = properties;
        this.clock = clock;
    }

    /** Round trips rebuilt from fills in {@code [from, to]}, oldest first. */
    public List<RoundTrip> roundTrips(ExecutionMode mode, Instant from, Instant to) {
        List<Trade> fills = new ArrayList<>(orders.trades(mode, from, to));
        fills.sort((a, b) -> a.ts().compareTo(b.ts()));
        return roundTrips(fills, t -> orders.cost(t).total(), this::attribution);
    }

    /** Pure round-trip builder: per (instrument, strategy), accumulate until the net quantity returns to zero. */
    public static List<RoundTrip> roundTrips(List<Trade> fills, Function<Trade, Money> feeOf, Function<UUID, Optional<StrategyPosition>> positionOfEntryOrder) {
        Map<String, Open> open = new HashMap<>();
        List<RoundTrip> out = new ArrayList<>();
        for (Trade t : fills) {
            String key = t.instrumentId() + "|" + t.strategyId();
            Open o = open.computeIfAbsent(key, k -> new Open(t));
            int signed = t.side() == Side.BUY ? t.quantity() : -t.quantity();
            BigDecimal value = t.price().multiply(BigDecimal.valueOf(t.quantity()));
            boolean sameSide = o.side == t.side();
            if (sameSide) {
                o.entryQty += t.quantity();
                o.entryValue = o.entryValue.add(value);
            } else {
                o.exitQty += t.quantity();
                o.exitValue = o.exitValue.add(value);
            }
            o.net += signed;
            o.fees = o.fees.plus(feeOf.apply(t));
            o.lastTs = t.ts();
            if (o.net == 0) {
                BigDecimal entry = o.entryValue.divide(BigDecimal.valueOf(o.entryQty), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal exit = o.exitValue.divide(BigDecimal.valueOf(o.exitQty), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal gross = o.side == Side.BUY ? o.exitValue.subtract(o.entryValue) : o.entryValue.subtract(o.exitValue);
                Optional<StrategyPosition> p = positionOfEntryOrder.apply(o.entryOrderId);
                out.add(new RoundTrip(t.instrumentId(), t.strategyId(), p.map(StrategyPosition::versionId).orElse(null), p.map(StrategyPosition::signalId).orElse(null),
                        o.entryOrderId, o.side, o.entryQty, entry, exit, o.openedAt, o.lastTs, Money.of(gross.setScale(2, java.math.RoundingMode.HALF_UP)), o.fees));
                open.remove(key);
            }
        }
        return out;
    }

    private static final class Open {
        final Side side;
        final UUID entryOrderId;
        final Instant openedAt;
        int net;
        int entryQty;
        int exitQty;
        BigDecimal entryValue = BigDecimal.ZERO;
        BigDecimal exitValue = BigDecimal.ZERO;
        Money fees = Money.ZERO;
        Instant lastTs;

        Open(Trade first) {
            this.side = first.side();
            this.entryOrderId = first.orderId();
            this.openedAt = first.ts();
        }
    }

    private Optional<StrategyPosition> attribution(UUID entryOrderId) {
        return signals.positionForOrder(entryOrderId);
    }

    /** PRD 53 breakdown. {@code groupBy}: strategy | version | instrument | weekday | hour | regime (single UNKNOWN bucket until Phase 3). */
    public List<PnlBucket> pnl(String groupBy, ExecutionMode mode, Instant from, Instant to) {
        List<RoundTrip> trips = roundTrips(mode, from, to);
        Map<String, List<RoundTrip>> grouped = new TreeMap<>();
        Map<String, String> labels = new HashMap<>();
        for (RoundTrip r : trips) {
            String key;
            String label;
            switch (groupBy == null ? "strategy" : groupBy.toLowerCase(Locale.ROOT)) {
                case "version" -> {
                    key = r.versionId() == null ? (r.strategyId() == null ? MANUAL : r.strategyId().toString()) : r.versionId().toString();
                    label = r.versionId() == null ? strategyLabel(r.strategyId()) : versionLabel(r.versionId());
                }
                case "instrument" -> {
                    key = r.instrumentId().toString();
                    label = instruments.findById(r.instrumentId()).map(i -> i.hejjeSymbol().format()).orElse(key);
                }
                case "weekday" -> {
                    DayOfWeek day = r.openedAt().atZone(clock.zone()).getDayOfWeek();
                    key = String.valueOf(day.getValue());
                    label = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                }
                case "hour" -> {
                    int hour = r.openedAt().atZone(clock.zone()).getHour();
                    key = String.format("%02d", hour);
                    label = key + ":00";
                }
                case "regime" -> {
                    key = "UNKNOWN";
                    label = "UNKNOWN (regime engine arrives in Phase 3)";
                }
                default -> {
                    key = r.strategyId() == null ? MANUAL : r.strategyId().toString();
                    label = strategyLabel(r.strategyId());
                }
            }
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            labels.put(key, label);
        }
        List<PnlBucket> out = new ArrayList<>();
        grouped.forEach((key, list) -> {
            long gross = list.stream().mapToLong(r -> r.grossPnl().paise()).sum();
            long fees = list.stream().mapToLong(r -> r.fees().paise()).sum();
            long net = list.stream().mapToLong(r -> r.netPnl().paise()).sum();
            int wins = (int) list.stream().filter(RoundTrip::isWin).count();
            List<Double> rs = list.stream().map(r -> reviews.findByEntryOrder(r.entryOrderId()).map(TradeReview::outcomeR).orElse(null)).filter(x -> x != null).toList();
            Double avgR = rs.isEmpty() ? null : rs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            out.add(new PnlBucket(key, labels.get(key), list.size(), wins, Money.ofPaise(gross), Money.ofPaise(fees), Money.ofPaise(net),
                    list.isEmpty() ? 0 : (double) wins / list.size(), avgR));
        });
        return out;
    }

    private String strategyLabel(UUID strategyId) {
        return strategyId == null ? MANUAL : strategies.find(strategyId).map(s -> s.slug()).orElse(strategyId.toString());
    }

    private String versionLabel(UUID versionId) {
        return strategies.versionById(versionId).map(v -> strategies.find(v.strategyId()).map(s -> s.slug()).orElse("?") + " v" + v.version()).orElse(versionId.toString());
    }

    public List<TradeReview> reviews(ExecutionMode mode, int limit) {
        return reviews.list(mode, limit);
    }

    public Optional<TradeReview> review(UUID id) {
        return reviews.find(id);
    }

    public Optional<TradeReview> reviewForEntryOrder(UUID entryOrderId) {
        return reviews.findByEntryOrder(entryOrderId);
    }

    public ExecutionMode mode() {
        return properties.mode();
    }

    public Map<String, Object> summary(ExecutionMode mode, Instant from, Instant to) {
        List<RoundTrip> trips = roundTrips(mode, from, to);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roundTrips", trips.size());
        m.put("grossPnl", Money.ofPaise(trips.stream().mapToLong(r -> r.grossPnl().paise()).sum()));
        m.put("fees", Money.ofPaise(trips.stream().mapToLong(r -> r.fees().paise()).sum()));
        m.put("netPnl", Money.ofPaise(trips.stream().mapToLong(r -> r.netPnl().paise()).sum()));
        return m;
    }
}
