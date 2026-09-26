package money.hejje.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.GttService;
import money.hejje.execution.PositionGtt;
import money.hejje.execution.ReconciliationIssue;
import money.hejje.execution.ReconciliationService;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.swing.internal.SwingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Public API of the swing module: the swing book, its broker-side stops and its daily reconciliation (plans M11.1, M11.2). */
@Service
public class SwingService {

    private static final Logger log = LoggerFactory.getLogger(SwingService.class);

    private final SwingStore store;
    private final MarketService market;
    private final InstrumentService instruments;
    private final ReconciliationService reconciliation;
    private final GttService gtts;
    private final HejjeClock clock;

    public static final String CLOSE_BOOK_CONFIRMATION = "CLOSE SWING BOOK";
    /** Next-session market events that block new swing entries (plan M11.3). */
    static final java.util.Set<money.hejje.events.EventType> BLOCKING_EVENTS = java.util.EnumSet.of(money.hejje.events.EventType.RBI_POLICY,
            money.hejje.events.EventType.BUDGET, money.hejje.events.EventType.INDEX_REBALANCE);

    private final money.hejje.swing.internal.SwingLimitsStore limitsStore;
    private final money.hejje.orders.OrderService orders;
    private final money.hejje.events.EventService events;
    private final money.hejje.ratings.RatingsService ratings;
    private final money.hejje.instruments.UniverseCatalog universes;
    private final money.hejje.broker.BrokerAdapter broker;
    private final money.hejje.execution.ExecutionEngine engine;
    private final money.hejje.audit.AuditService audit;
    private final String universe;

    SwingService(SwingStore store, MarketService market, InstrumentService instruments, ReconciliationService reconciliation, GttService gtts,
            HejjeClock clock, money.hejje.swing.internal.SwingLimitsStore limitsStore, money.hejje.orders.OrderService orders,
            money.hejje.events.EventService events, money.hejje.ratings.RatingsService ratings, money.hejje.instruments.UniverseCatalog universes,
            money.hejje.broker.BrokerAdapter broker, @org.springframework.context.annotation.Lazy money.hejje.execution.ExecutionEngine engine,
            money.hejje.audit.AuditService audit, @org.springframework.beans.factory.annotation.Value("${hejje.swing.universe:nifty500}") String universe) {
        this.limitsStore = limitsStore;
        this.orders = orders;
        this.events = events;
        this.ratings = ratings;
        this.universes = universes;
        this.broker = broker;
        this.engine = engine;
        this.audit = audit;
        this.universe = universe;
        this.store = store;
        this.market = market;
        this.instruments = instruments;
        this.reconciliation = reconciliation;
        this.gtts = gtts;
        this.clock = clock;
    }

    /** The open swing positions, newest first. */
    public List<SwingPosition> open(ExecutionMode mode) {
        return store.list(mode, SwingPosition.Status.OPEN, 500);
    }

    public List<SwingPosition> closed(ExecutionMode mode, int limit) {
        return store.list(mode, SwingPosition.Status.CLOSED, limit);
    }

    public Optional<SwingPosition> find(UUID id) {
        return store.find(id);
    }

    /** The open swing book with days held, stop, goal, R, unrealized P&L at the last price and the GTT's state. */
    public List<SwingBookRow> book(ExecutionMode mode) {
        return open(mode).stream().map(this::row).toList();
    }

    private SwingBookRow row(SwingPosition p) {
        BigDecimal last = market.lastPrice(p.instrumentId()).orElse(p.entryPrice());
        Optional<PositionGtt> gtt = gtts.live(p.positionId());
        BigDecimal stop = gtt.map(PositionGtt::stop).orElse(p.initialStop());
        BigDecimal r = null;
        if (p.initialStop() != null && p.entryPrice().subtract(p.initialStop()).signum() > 0) {
            r = last.subtract(p.entryPrice()).divide(p.entryPrice().subtract(p.initialStop()), 2, RoundingMode.HALF_UP);
        }
        Money unrealized = Money.of(last.subtract(p.entryPrice()).multiply(BigDecimal.valueOf(p.quantity())).setScale(2, RoundingMode.HALF_UP));
        String symbol = instruments.findById(p.instrumentId()).map(i -> i.hejjeSymbol().format()).orElse(p.instrumentId().toString());
        return new SwingBookRow(p.id(), p.instrumentId(), symbol, p.strategyId(), p.openedAt(), p.entryDate(), clock.sessionsBetween(p.entryDate(), clock.today()),
                p.quantity(), p.entryPrice(), stop, p.goal(), last, r, unrealized, gtt.map(g -> g.status().name()).orElse("NONE"),
                gtt.map(PositionGtt::brokerGttId).orElse(null));
    }

    /**
     * Reconciles the swing book with the broker (before the open, after the close, on demand): holdings (M11.1) and every
     * open position's GTT (M11.2). Returns the open holdings and GTT issues.
     */
    public List<ReconciliationIssue> reconcile() {
        List<ReconciliationIssue> out = new ArrayList<>(reconciliation.reconcileHoldings());
        out.addAll(gtts.reconcile());
        return out;
    }

    /**
     * Moves a swing position's stop at the broker. Tighten-only unless {@code widen} is set (a manual, audited action).
     */
    public PositionGtt moveStop(UUID swingPositionId, BigDecimal stop, boolean widen, String actor) {
        SwingPosition p = store.find(swingPositionId).filter(s -> s.status() == SwingPosition.Status.OPEN)
                .orElseThrow(() -> new IllegalArgumentException("no open swing position " + swingPositionId));
        return gtts.moveStop(p.positionId(), stop, widen, actor);
    }

    /**
     * After the close: every open position with trailing on moves its GTT's stop by {@link SwingTrail} (breakeven at +1R,
     * then one tick under the 20-day low), tighten-only. Returns how many stops moved.
     */
    public int trail(ExecutionMode mode) {
        int moved = 0;
        Instant now = clock.now();
        for (SwingPosition p : open(mode)) {
            if (!p.trail() || p.initialStop() == null) {
                continue;
            }
            Optional<PositionGtt> gtt = gtts.active(p.positionId());
            if (gtt.isEmpty()) {
                continue; // unprotected positions are the reconciliation's business
            }
            List<Candle> daily = market.candles(p.instrumentId(), Timeframe.D1, now.minus(java.time.Duration.ofDays(40)), now);
            if (daily.isEmpty()) {
                continue;
            }
            List<Candle> last20 = daily.subList(Math.max(0, daily.size() - 20), daily.size());
            BigDecimal low20 = last20.stream().map(Candle::low).min(BigDecimal::compareTo).orElse(null);
            BigDecimal close = daily.get(daily.size() - 1).close();
            BigDecimal tick = instruments.findById(p.instrumentId()).map(Instrument::tickSize).orElse(new BigDecimal("0.05"));
            BigDecimal next = SwingTrail.next(p.entryPrice(), p.initialStop(), gtt.get().stop(), close, low20, tick);
            if (next.compareTo(gtt.get().stop()) > 0) {
                try {
                    gtts.moveStop(p.positionId(), next, false, "trail");
                    moved++;
                } catch (RuntimeException e) {
                    log.warn("Trailing the stop of swing position {} failed: {}", p.id(), e.getMessage());
                }
            }
        }
        return moved;
    }

    // --- overnight risk (plan M11.3) --------------------------------------------------------------------------------------

    public SwingLimits limits(ExecutionMode mode) {
        return limitsStore.find(mode);
    }

    public SwingLimits updateLimits(SwingLimits limits, String actor) {
        if (limits.gapAllowancePct().signum() < 0 || limits.maxOpenPositions() < 0 || limits.maxPositionsPerIndustry() < 0) {
            throw new IllegalArgumentException("swing limits must not be negative");
        }
        limitsStore.update(limits, actor);
        audit.record(money.hejje.audit.AuditEvent.of(money.hejje.audit.AuditEventType.SWING_LIMITS_UPDATED, money.hejje.common.ActorType.USER)
                .withActorId(actor).withPayload(java.util.Map.of("mode", limits.mode().name(), "swingCapital", limits.swingCapital().toRupeesString(),
                        "maxOpenPositions", limits.maxOpenPositions(), "maxRiskPerPosition", limits.maxRiskPerPosition().toRupeesString(),
                        "gapAllowancePct", limits.gapAllowancePct().toPlainString(), "maxOvernightRisk", limits.maxOvernightRisk().toRupeesString(),
                        "maxPositionsPerIndustry", limits.maxPositionsPerIndustry(), "blockBeforeEvents", limits.blockBeforeEvents(),
                        "blockSurveillance", limits.blockSurveillance())));
        return limitsStore.find(limits.mode());
    }

    /**
     * The open swing positions as the risk sees them: quantity, average, last price, the stop in force (the GTT's, else the
     * entry's) and the industry.
     */
    public List<SwingRisk.Holding> holdings(ExecutionMode mode) {
        List<SwingRisk.Holding> out = new ArrayList<>();
        for (money.hejje.orders.Position p : orders.openPositions(mode)) {
            if (p.product() != money.hejje.common.Product.CNC || p.netQuantity() <= 0) {
                continue;
            }
            BigDecimal stop = gtts.live(p.id()).map(PositionGtt::stop).orElseGet(() -> store.findOpen(p.id()).map(SwingPosition::initialStop).orElse(null));
            String symbol = symbol(p.instrumentId());
            out.add(new SwingRisk.Holding(p.instrumentId(), symbol, p.netQuantity(), p.averagePrice(), market.lastPrice(p.instrumentId()).orElse(p.averagePrice()),
                    stop, industry(symbol)));
        }
        return out;
    }

    /** The swing book's overnight risk: per position and in total, against the budget, with the capital deployed. */
    public record OvernightRisk(ExecutionMode mode, Money overnightRisk, Money budget, BigDecimal gapAllowancePct, int openPositions, int maxOpenPositions,
            Money deployed, Money capital, List<PositionRisk> positions) {}

    /** One position's gap-adjusted risk: {@code quantity × (max(0, price − stop) + gap% × price)}. */
    public record PositionRisk(UUID instrumentId, String symbol, int quantity, BigDecimal price, BigDecimal stop, String industry, Money risk) {}

    public OvernightRisk overnightRisk(ExecutionMode mode) {
        SwingLimits limits = limits(mode);
        List<SwingRisk.Holding> book = holdings(mode);
        Money deployed = Money.ZERO;
        List<PositionRisk> rows = new ArrayList<>();
        for (SwingRisk.Holding h : book) {
            deployed = deployed.plus(h.cost());
            rows.add(new PositionRisk(h.instrumentId(), h.symbol(), h.quantity(), h.price(), h.stop(), h.industry(), SwingRisk.risk(h, limits.gapAllowancePct())));
        }
        return new OvernightRisk(mode, SwingRisk.overnightRisk(book, limits.gapAllowancePct()), limits.maxOvernightRisk(), limits.gapAllowancePct(), book.size(),
                limits.maxOpenPositions(), deployed, limits.swingCapital(), rows);
    }

    /** The swing limits against a new delivery entry (the swing module's risk contribution). */
    public List<money.hejje.risk.RiskCheck> checks(money.hejje.orders.OrderIntent intent) {
        SwingLimits limits = limits(intent.mode());
        String symbol = symbol(intent.instrumentId());
        BigDecimal price = intent.limitPrice() != null ? intent.limitPrice().value() : price(intent.instrumentId()).orElse(null);
        SwingRisk.Entry entry = new SwingRisk.Entry(intent.instrumentId(), intent.quantity().value(), price,
                intent.stopPrice() == null ? null : intent.stopPrice().value(), industry(symbol));
        List<String> unprotected = gtts.unprotected(intent.mode()).stream().map(p -> symbol(p.instrumentId())).toList();
        SwingRisk.Context ctx = new SwingRisk.Context(intent.mode(), unprotected, eventNextSession(), surveillance(symbol));
        return SwingRisk.check(limits, holdings(intent.mode()), entry, ctx);
    }

    /** The largest entry the swing limits allow at {@code entry} with {@code stop} (plan M11.3 sizing). */
    public SwingRisk.Size size(ExecutionMode mode, BigDecimal entry, BigDecimal stop) {
        return SwingRisk.size(limits(mode), holdings(mode), entry, stop);
    }

    /**
     * The "close swing book" action (plan M11.3): exits every open swing position (each close cancels its GTT in the same
     * operation). Needs the typed confirmation {@value #CLOSE_BOOK_CONFIRMATION}. The kill switch never does this. Returns
     * how many positions were closed.
     */
    public int closeBook(ExecutionMode mode, String confirmation, String actor) {
        if (!CLOSE_BOOK_CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("closing the swing book requires confirmation \"" + CLOSE_BOOK_CONFIRMATION + "\"");
        }
        int closed = 0;
        for (money.hejje.orders.Position p : orders.openPositions(mode)) {
            if (p.product() != money.hejje.common.Product.CNC || p.netQuantity() <= 0) {
                continue;
            }
            try {
                engine.closePosition(p.instrumentId(), p.product(), p.strategyId());
                closed++;
            } catch (RuntimeException e) {
                log.warn("Closing swing position {} failed: {}", p.id(), e.getMessage());
            }
        }
        audit.record(money.hejje.audit.AuditEvent.of(money.hejje.audit.AuditEventType.SWING_BOOK_CLOSED, money.hejje.common.ActorType.USER).withActorId(actor)
                .withPayload(java.util.Map.of("mode", mode.name(), "closed", closed)));
        return closed;
    }

    private String symbol(UUID instrumentId) {
        return instruments.findById(instrumentId).map(i -> i.hejjeSymbol().format()).orElse(instrumentId.toString());
    }

    private String industry(String symbol) {
        return universes.find(universe).map(u -> u.industry().get(symbol)).orElse(null);
    }

    private Optional<BigDecimal> price(UUID instrumentId) {
        Optional<BigDecimal> last = market.lastPrice(instrumentId);
        if (last.isPresent()) {
            return last;
        }
        try {
            return broker.getQuote(java.util.Set.of(instrumentId)).stream().findFirst().map(money.hejje.broker.Quote::lastPrice);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The blocking market event (RBI policy, Budget, index rebalance) on the next session, or null. */
    private String eventNextSession() {
        java.time.LocalDate next = clock.nextTradingDay(clock.today());
        try {
            return events.marketEvents(next).stream().filter(e -> BLOCKING_EVENTS.contains(e.type())).findFirst()
                    .map(e -> e.type() + " on " + next).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Event lookup for {} failed: {}", next, e.getMessage());
            return null;
        }
    }

    private String surveillance(String symbol) {
        try {
            money.hejje.ratings.Surveillance s = ratings.surveillance(symbol, clock.today());
            return s == null ? null : s.flag();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
