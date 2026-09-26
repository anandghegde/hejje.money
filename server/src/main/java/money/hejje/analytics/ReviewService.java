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
import money.hejje.common.ExecutionMode;
import money.hejje.orders.Position;
import money.hejje.signals.CloseReason;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.signals.StrategyPosition;
import money.hejje.signals.StrategyPositionClosedEvent;
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
    private final TradeCauseProperties causeProps;
    private final money.hejje.llm.JevService jev;
    private final money.hejje.llm.JevQuestionSets jevSets;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    ReviewService(AnalyticsService analytics, OrderService orders, SignalService signals, StrategyService strategies, MarketService market, ReviewStore store,
            money.hejje.regime.RegimeService regime, money.hejje.events.EventService events, money.hejje.news.NewsService news, HejjeClock clock,
            TradeCauseProperties causeProps, money.hejje.llm.JevService jev, money.hejje.llm.JevQuestionSets jevSets, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.causeProps = causeProps;
        this.jev = jev;
        this.jevSets = jevSets;
        this.json = json;
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

    /**
     * Reviews the latest closed round trip of the flattened position (idempotent per entry order). A round trip the
     * signal engine still manages is left alone: the engine reviews it through {@link StrategyPositionClosedEvent} once
     * it has recorded the close reason (the flat position and the exit fill reach their listeners in no fixed order).
     */
    public Optional<TradeReview> reviewClosedPosition(UUID positionId) {
        Optional<Position> position = orders.findPosition(positionId);
        if (position.isEmpty()) {
            return Optional.empty();
        }
        Position p = position.get();
        List<RoundTrip> trips = roundTrips(p.mode(), p.instrumentId(), p.strategyId());
        if (trips.isEmpty()) {
            return Optional.empty();
        }
        RoundTrip trip = trips.get(trips.size() - 1);
        Optional<StrategyPosition> managed = signals.positionForOrder(trip.entryOrderId());
        if (managed.isPresent() && managed.get().status().isLive()) {
            log.debug("Round trip of entry order {} is still managed by the signal engine; its review follows the engine's close", trip.entryOrderId());
            return Optional.empty();
        }
        return review(p, trip);
    }

    /** Reviews the round trip of a strategy position the engine has closed (idempotent per entry order). */
    public Optional<TradeReview> reviewClosedStrategyPosition(ExecutionMode mode, UUID instrumentId, UUID strategyId, UUID entryOrderId) {
        Optional<RoundTrip> trip = roundTrips(mode, instrumentId, strategyId).stream().filter(r -> r.entryOrderId().equals(entryOrderId)).findFirst();
        Optional<Position> position = orders.positions(mode).stream()
                .filter(p -> p.instrumentId().equals(instrumentId) && java.util.Objects.equals(p.strategyId(), strategyId)).findFirst();
        if (trip.isEmpty() || position.isEmpty()) {
            return Optional.empty(); // e.g. ENTRY_FAILED, or a DEPLOYMENT_STOPPED position still open at the broker: reviewed when it goes flat
        }
        return review(position.get(), trip.get());
    }

    private List<RoundTrip> roundTrips(ExecutionMode mode, UUID instrumentId, UUID strategyId) {
        Instant now = clock.now();
        return analytics.roundTrips(mode, now.minus(LOOKBACK), now.plusSeconds(60)).stream()
                .filter(r -> r.instrumentId().equals(instrumentId) && java.util.Objects.equals(r.strategyId(), strategyId)).toList();
    }

    private Optional<TradeReview> review(Position p, RoundTrip trip) {
        if (store.findByEntryOrder(trip.entryOrderId()).isPresent()) {
            return store.findByEntryOrder(trip.entryOrderId());
        }
        TradeReview review = build(p, trip);
        store.insert(review);
        classify(review, false); // provisional; completed after the post-exit window (plan M9.6)
        return store.findByEntryOrder(trip.entryOrderId()).or(() -> Optional.of(review));
    }

    // --- trade cause and entry timing (plan M9.6) ---

    /** Completes the causes whose post-exit window has passed (or whose session has closed); returns how many. */
    public int completeCauses() {
        Instant now = clock.now();
        int n = 0;
        for (TradeReview r : store.causePending(now, 200)) {
            try {
                TradeCause c = classify(r, true);
                if (c != null && c.complete()) {
                    n++;
                }
            } catch (RuntimeException e) {
                log.warn("Trade cause of review {} failed: {}", r.id(), e.getMessage());
            }
        }
        return n;
    }

    /** Classifies from the candles that exist now; asks Jev once the window is complete. Stored; null without an instrument session. */
    TradeCause classify(TradeReview r, boolean withJev) {
        java.time.LocalDate day = r.openedAt().atZone(clock.zone()).toLocalDate();
        Instant open = clock.sessionWindow(day).open().toInstant();
        Instant close = day.atTime(money.hejje.common.time.HejjeClock.SESSION_CLOSE).atZone(clock.zone()).toInstant();
        Instant now = clock.now();
        Instant until = r.closedAt().plus(Duration.ofMinutes(causeProps.postExitMinutes()));
        List<Candle> bars = market.candles(r.instrumentId(), money.hejje.common.Timeframe.M1, open, until.isBefore(now) ? until : now);
        money.hejje.analytics.internal.TradeCauseClassifier.Trade t = new money.hejje.analytics.internal.TradeCauseClassifier.Trade(r.side() == Side.BUY,
                r.entryPrice(), r.exitPrice(), stopOf(r.entryOrderId()), r.closeReason(), r.openedAt(), r.closedAt(), close, now);
        TradeCause c = money.hejje.analytics.internal.TradeCauseClassifier.classify(t, bars, causeProps);
        if (withJev && c.complete() && jev.enabled() && c.cause() != TradeCause.Cause.UNKNOWN) {
            c = withJev(r, c);
        }
        store.updateCause(r.id(), c);
        return c;
    }

    /** Jev's reading ({@code config/jev/trade-cause.yaml}) beside the rules; unchanged when Jev fails. */
    private TradeCause withJev(TradeReview r, TradeCause c) {
        var state = json.createObjectNode();
        var trade = state.putObject("trade");
        trade.put("side", r.side() == Side.BUY ? "long" : "short");
        trade.put("exit_reason", r.closeReason() == null ? "manual" : r.closeReason().toLowerCase(java.util.Locale.ROOT));
        trade.put("minutes_held", Duration.between(r.openedAt(), r.closedAt()).toMinutes());
        Map<String, Object> ev = c.evidence();
        trade.put("result", bucketR(ev.get("outcomeR")));
        trade.put("best_move_while_open", bucketR(c.mfeR()));
        trade.put("worst_move_while_open", bucketR(c.maeR()));
        if (ev.get("preEntryMoveAtr") instanceof Number m) {
            trade.put("move_before_entry", m.doubleValue() >= causeProps.extendedAtr() ? "stretched" : m.doubleValue() <= -causeProps.extendedAtr() ? "against" : "normal");
        }
        if (ev.get("fromVwapAtr") instanceof Number v) {
            trade.put("entry_vs_vwap", v.doubleValue() >= causeProps.vwapAtr() ? "far_in_trade_direction" : "normal");
        }
        if (ev.get("postExitBestR") instanceof Number post) {
            trade.put("after_the_stop", post.doubleValue() >= causeProps.noiseRecoveryR() ? "went_the_trade_way" : "did_not_recover");
        }
        money.hejje.llm.JevResult res = jev.evaluate("trade-cause", r.id().toString(), state, jevSets.get("trade-cause"));
        if (!res.ok()) {
            return c;
        }
        String jevCause = res.answer("cause").map(money.hejje.llm.JevAnswer::choice).orElse(null);
        String jevTiming = res.answer("entry_timing").map(money.hejje.llm.JevAnswer::score)
                .map(s -> TradeCause.Timing.values()[(int) Math.max(0, Math.min(2, Math.round(s)))].name()).orElse(null);
        return new TradeCause(c.cause(), c.entryTiming(), c.mfeR(), c.maeR(), c.evidence(), jevCause, jevTiming, c.complete());
    }

    private static String bucketR(Object r) {
        if (!(r instanceof Number n)) {
            return "unknown";
        }
        double v = n.doubleValue();
        return v >= 1 ? "over_1r_gain" : v >= 0.3 ? "small_gain" : v > -0.3 ? "flat" : v > -1 ? "small_loss" : "full_loss";
    }

    /** The entry's initial stop: the strategy position's, else the order intent's. */
    private BigDecimal stopOf(UUID entryOrderId) {
        Optional<StrategyPosition> sp = signals.positionForOrder(entryOrderId);
        if (sp.isPresent()) {
            return sp.get().initialStop();
        }
        return orders.findById(entryOrderId).flatMap(o -> orders.findIntent(o.intentId())).map(i -> i.stopPrice() == null ? null : i.stopPrice().value())
                .orElse(null);
    }

    /** Rules against Jev over completed reviews closed in {@code [from, to]}: counts, agreement and the confusion matrix (rules → Jev). */
    public record CauseAgreement(java.time.LocalDate from, java.time.LocalDate to, int trades, Double causeAgreement, Double timingAgreement,
            Map<String, Map<String, Integer>> causeMatrix) {}

    public CauseAgreement causeAgreement(java.time.LocalDate from, java.time.LocalDate to) {
        List<TradeReview> rs = store.withJevCause(from.atStartOfDay(clock.zone()).toInstant(), to.plusDays(1).atStartOfDay(clock.zone()).toInstant());
        Map<String, Map<String, Integer>> matrix = new java.util.TreeMap<>();
        int same = 0;
        int timingSame = 0;
        int timed = 0;
        for (TradeReview r : rs) {
            TradeCause c = r.cause();
            matrix.computeIfAbsent(c.cause().name(), k -> new java.util.TreeMap<>()).merge(c.jevCause(), 1, Integer::sum);
            if (c.cause().name().equals(c.jevCause())) {
                same++;
            }
            if (c.entryTiming() != null && c.jevTiming() != null) {
                timed++;
                if (c.entryTiming().name().equals(c.jevTiming())) {
                    timingSame++;
                }
            }
        }
        return new CauseAgreement(from, to, rs.size(), rs.isEmpty() ? null : Math.round(1000.0 * same / rs.size()) / 1000.0,
                timed == 0 ? null : Math.round(1000.0 * timingSame / timed) / 1000.0, matrix);
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
