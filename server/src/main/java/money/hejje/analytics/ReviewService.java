package money.hejje.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.analytics.internal.ReviewStore;
import money.hejje.common.Ids;
import money.hejje.common.Side;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.market.indicators.IndicatorContext;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.signals.CloseReason;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.signals.StrategyPosition;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.dsl.ConditionEvaluator;
import money.hejje.strategy.dsl.EvalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Writes the PRD 55 review for the round trip that just closed (docs/analytics.md, "Post-trade review"). */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final Duration LOOKBACK = Duration.ofDays(7);

    private final AnalyticsService analytics;
    private final OrderService orders;
    private final SignalService signals;
    private final StrategyService strategies;
    private final MarketService market;
    private final ReviewStore store;
    private final money.hejje.regime.RegimeService regime;
    private final money.hejje.events.EventService events;
    private final money.hejje.news.NewsService news;
    private final HejjeClock clock;

    ReviewService(AnalyticsService analytics, OrderService orders, SignalService signals, StrategyService strategies, MarketService market, ReviewStore store,
            money.hejje.regime.RegimeService regime, money.hejje.events.EventService events, money.hejje.news.NewsService news, HejjeClock clock) {
        this.analytics = analytics;
        this.regime = regime;
        this.events = events;
        this.news = news;
        this.orders = orders;
        this.signals = signals;
        this.strategies = strategies;
        this.market = market;
        this.store = store;
        this.clock = clock;
    }

    /** Reviews the latest closed round trip of the flattened position (idempotent per entry order). */
    public Optional<TradeReview> reviewClosedPosition(UUID positionId) {
        Optional<Position> position = orders.findPosition(positionId);
        if (position.isEmpty()) {
            return Optional.empty();
        }
        Position p = position.get();
        Instant now = clock.now();
        List<RoundTrip> trips = analytics.roundTrips(p.mode(), now.minus(LOOKBACK), now.plusSeconds(60)).stream()
                .filter(r -> r.instrumentId().equals(p.instrumentId()) && java.util.Objects.equals(r.strategyId(), p.strategyId())).toList();
        if (trips.isEmpty()) {
            return Optional.empty();
        }
        RoundTrip trip = trips.get(trips.size() - 1);
        if (store.findByEntryOrder(trip.entryOrderId()).isPresent()) {
            return store.findByEntryOrder(trip.entryOrderId());
        }
        TradeReview review = build(p, trip);
        store.insert(review);
        return Optional.of(review);
    }

    TradeReview build(Position p, RoundTrip trip) {
        Optional<StrategyPosition> sp = signals.positionForOrder(trip.entryOrderId());
        Optional<OrderIntent> intent = orders.findById(trip.entryOrderId()).flatMap(o -> orders.findIntent(o.intentId()));
        UUID strategyId = trip.strategyId() != null ? trip.strategyId() : intent.map(OrderIntent::strategyId).orElse(null);
        Optional<StrategyVersion> version = sp.map(StrategyPosition::versionId).flatMap(strategies::versionById)
                .or(() -> strategyId == null ? Optional.empty() : strategies.latestVersion(strategyId));
        Optional<Signal> signal = sp.map(StrategyPosition::signalId).flatMap(signals::find);

        BigDecimal stop = sp.map(StrategyPosition::initialStop).or(() -> intent.map(i -> i.stopPrice() == null ? null : i.stopPrice().value())).orElse(null);
        Double outcomeR = null;
        if (stop != null) {
            BigDecimal risk = trip.entryPrice().subtract(stop).abs().multiply(BigDecimal.valueOf(trip.quantity()));
            if (risk.signum() > 0) {
                outcomeR = trip.netPnl().toRupees().divide(risk, 4, RoundingMode.HALF_UP).doubleValue();
            }
        }
        Double entrySlippage = signal.map(s -> bps(trip.entryPrice(), s.referencePrice(), trip.side() == Side.BUY)).orElse(null);
        Double exitSlippage = null;
        String closeReason = sp.map(StrategyPosition::closeReason).map(Enum::name).orElse(null);
        if (sp.isPresent() && sp.get().closeReason() != null) {
            CloseReason reason = sp.get().closeReason();
            BigDecimal planned = switch (reason) {
                case STOP, TRAILING_STOP, SOFTWARE_STOP -> sp.get().stop();
                case TARGET -> sp.get().target();
                default -> null;
            };
            exitSlippage = planned == null ? 0.0 : bps(trip.exitPrice(), planned, trip.side() == Side.SELL);
        }
        Boolean setupValid = version.map(v -> setupValid(v.definition(), trip)).orElse(null);
        Integer adherence = null;
        if (sp.isPresent()) {
            boolean ruleExit = sp.get().closeReason() != null && sp.get().closeReason() != CloseReason.MANUAL && sp.get().closeReason() != CloseReason.DEPLOYMENT_STOPPED;
            adherence = (Boolean.TRUE.equals(setupValid) ? 50 : 0) + (ruleExit ? 50 : 0);
        } else if (version.isPresent()) {
            adherence = Boolean.TRUE.equals(setupValid) ? 50 : 0;
        }
        Map<String, Object> context = new LinkedHashMap<>(); // UNKNOWN (not null) when a source has nothing: survives JSON non-null serialisation
        money.hejje.regime.RegimeSnapshot regimeAtClose = regimeAt(trip.closedAt());
        context.put("regime", regimeAtClose == null ? "UNKNOWN" : regimeAtClose.key());
        context.put("breadth", regimeAtClose == null ? "UNKNOWN" : regimeAtClose.breadth().name());
        // news and event as they stood at the entry (plan M4.5): the latest stored news-bias snapshot, and the event risk then
        money.hejje.news.NewsBias newsAtEntry = news.biasSnapshotAt(trip.instrumentId(), trip.openedAt()).orElse(null);
        context.put("news", newsAtEntry == null ? "UNKNOWN" : newsAtEntry.label().name());
        if (newsAtEntry != null) {
            context.put("newsScore", newsAtEntry.score());
        }
        money.hejje.events.EventRisk eventAtEntry = events.riskAt(trip.instrumentId(), trip.openedAt());
        context.put("event", eventAtEntry.available() && eventAtEntry.level() != null ? eventAtEntry.level().name() : "UNKNOWN");
        if (eventAtEntry.trigger() != null) {
            context.put("eventTrigger", eventAtEntry.trigger().title());
        }
        String notes = sp.isPresent() ? "strategy trade" : version.isPresent() ? "manual trade compared against " + version.get().definition().name() : "manual trade";
        return new TradeReview(Ids.newId(), p.mode(), p.id(), sp.map(StrategyPosition::id).orElse(null), strategyId, version.map(StrategyVersion::id).orElse(null),
                signal.map(Signal::id).orElse(null), trip.instrumentId(), trip.entryOrderId(), trip.side(), trip.quantity(), trip.entryPrice(), trip.exitPrice(),
                trip.openedAt(), trip.closedAt(), trip.grossPnl(), trip.fees(), trip.netPnl(), outcomeR, setupValid, entrySlippage, exitSlippage, adherence, closeReason,
                context, notes, clock.now());
    }

    /** Did the definition's entry rules pass on the last bar closed at or before the entry? Warms up from history. */
    private Boolean setupValid(StrategyDefinition def, RoundTrip trip) {
        try {
            Instant end = trip.openedAt();
            List<Candle> candles = market.candles(trip.instrumentId(), def.timeframe(), end.minus(Duration.ofDays(10)), end).stream()
                    .filter(c -> !c.openTime().plus(def.timeframe().duration()).isAfter(end)).toList();
            if (candles.isEmpty()) {
                return null;
            }
            IndicatorContext ctx = new IndicatorContext(def.timeframe(), clock.zone());
            ctx.registerDefinition(def);
            ctx.warmUp(candles);
            List<EvalResult> results = ConditionEvaluator.evaluateAll(def.entry().conditions(), ctx);
            return def.entry().mode() == StrategyDefinition.RuleMode.ALL ? results.stream().allMatch(EvalResult::passed) : results.stream().anyMatch(EvalResult::passed);
        } catch (RuntimeException e) {
            log.warn("Setup validity check failed for {}: {}", trip.entryOrderId(), e.getMessage());
            return null;
        }
    }

    /** Basis points of {@code actual} versus {@code planned}; positive means worse for the trader. */
    static double bps(BigDecimal actual, BigDecimal planned, boolean buying) {
        if (planned == null || planned.signum() == 0) {
            return 0;
        }
        BigDecimal diff = buying ? actual.subtract(planned) : planned.subtract(actual);
        return diff.divide(planned, 8, RoundingMode.HALF_UP).movePointRight(4).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
    /** The session's final regime label when stored, else the live snapshot when the trip closed today. */
    private money.hejje.regime.RegimeSnapshot regimeAt(Instant closedAt) {
        java.time.LocalDate date = closedAt.atZone(clock.zone()).toLocalDate();
        try {
            return regime.forDate(date).orElseGet(() -> {
                money.hejje.regime.RegimeSnapshot now = regime.current();
                return now.date().equals(date) ? now : null;
            });
        } catch (RuntimeException e) {
            log.warn("Regime lookup for review failed: {}", e.getMessage());
            return null;
        }
    }
}
