package money.hejje.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Price;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.market.indicators.Atr;
import money.hejje.market.indicators.Bar;
import money.hejje.risk.internal.AccountSnapshotBuilder;
import money.hejje.risk.internal.KillSwitchStore;
import money.hejje.risk.internal.RiskLimitsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Public API of the risk module: limits, kill switch, dashboard, position sizing and stop suggestion. */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);
    public static final String CLOSE_ALL_CONFIRMATION = "CLOSE ALL";

    private final RiskLimitsStore limitsStore;
    private final KillSwitchStore killSwitchStore;
    private final AccountSnapshotBuilder snapshots;
    private final AuditService audit;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;
    private final MarketService market;
    private final InstrumentService instruments;

    private final org.springframework.beans.factory.ObjectProvider<SizeFactorSource> sizeSources;
    private final BigDecimal macroEventSizeFactor;

    RiskService(RiskLimitsStore limitsStore, KillSwitchStore killSwitchStore, AccountSnapshotBuilder snapshots, AuditService audit,
            HejjeClock clock, ApplicationEventPublisher events, MarketService market, InstrumentService instruments,
            org.springframework.beans.factory.ObjectProvider<SizeFactorSource> sizeSources,
            @org.springframework.beans.factory.annotation.Value("${hejje.risk.macro-event-size-factor:1.0}") BigDecimal macroEventSizeFactor) {
        if (macroEventSizeFactor.signum() <= 0 || macroEventSizeFactor.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("hejje.risk.macro-event-size-factor must be in (0, 1]; it only ever reduces size");
        }
        this.sizeSources = sizeSources;
        this.macroEventSizeFactor = macroEventSizeFactor;
        this.limitsStore = limitsStore;
        this.killSwitchStore = killSwitchStore;
        this.snapshots = snapshots;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
        this.market = market;
        this.instruments = instruments;
    }

    public RiskLimits limits(ExecutionMode mode) {
        return limitsStore.find(mode);
    }

    public RiskLimits updateLimits(RiskLimits limits, String actor) {
        limitsStore.update(limits);
        audit.record(AuditEvent.of(AuditEventType.RISK_LIMITS_UPDATED, ActorType.USER).withActorId(actor)
                .withPayload(Map.of("mode", limits.mode().name(), "lossStreakMode", limits.lossStreakMode().name(), "lossStreakAllowance",
                        limits.lossStreakAllowance(), "allowanceDrawdown", limits.allowanceDrawdown().toRupeesString(), "tradesPerDayWhenGreen",
                        limits.tradesPerDayWhenGreen().name(), "maxConsecutiveLosses", limits.maxConsecutiveLosses(), "maxTradesPerDay",
                        limits.maxTradesPerDay())));
        return limitsStore.find(limits.mode());
    }

    public KillSwitchState killSwitch(ExecutionMode mode) {
        return killSwitchStore.find(mode);
    }

    /** Applies a kill switch action. CLOSE_ALL_POSITIONS requires the exact confirmation string. */
    public KillSwitchState activate(ExecutionMode mode, KillSwitchAction action, String confirmation, String setBy) {
        if (action == KillSwitchAction.CLOSE_ALL_POSITIONS && !CLOSE_ALL_CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("CLOSE_ALL_POSITIONS requires confirmation \"" + CLOSE_ALL_CONFIRMATION + "\"");
        }
        // every action stops new orders
        killSwitchStore.setStopNewOrders(mode, true, setBy, action.name(), clock.now());
        audit.record(AuditEvent.of(AuditEventType.KILL_SWITCH_ENABLED, ActorType.USER).withActorId(setBy)
                .withPayload(Map.of("mode", mode.name(), "action", action.name())));
        events.publishEvent(new KillSwitchActivated(EventMeta.create(clock), mode, action, "user:" + setBy));
        log.warn("Kill switch {} activated for {} by {}", action, mode, setBy);
        return killSwitchStore.find(mode);
    }

    /** Auto-trips STOP_NEW_ORDERS (e.g. daily loss breach). No-op if already stopping. */
    public void autoTrip(ExecutionMode mode, String reason) {
        if (killSwitchStore.find(mode).stopNewOrders()) {
            return;
        }
        killSwitchStore.setStopNewOrders(mode, true, "system", reason, clock.now());
        audit.record(AuditEvent.of(AuditEventType.KILL_SWITCH_ENABLED, ActorType.SYSTEM).withActorId("risk-engine")
                .withPayload(Map.of("mode", mode.name(), "reason", reason)));
        events.publishEvent(new KillSwitchActivated(EventMeta.create(clock), mode, KillSwitchAction.STOP_NEW_ORDERS, reason));
        log.warn("Kill switch auto-tripped for {}: {}", mode, reason);
    }

    public KillSwitchState rearm(ExecutionMode mode, String actor) {
        killSwitchStore.setStopNewOrders(mode, false, actor, null, clock.now());
        audit.record(AuditEvent.of(AuditEventType.KILL_SWITCH_DISARMED, ActorType.USER).withActorId(actor)
                .withPayload(Map.of("mode", mode.name())));
        return killSwitchStore.find(mode);
    }

    public RiskDashboard dashboard(ExecutionMode mode) {
        AccountSnapshot s = snapshots.build(mode);
        RiskLimits l = limitsStore.find(mode);
        long capital = s.usedMargin().paise() + s.availableCash().paise();
        BigDecimal marginPct = capital <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(s.usedMargin().paise()).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(capital), 2, RoundingMode.HALF_UP);
        money.hejje.risk.internal.RiskControls.Allowance a = l.lossStreakMode() == RiskLimits.LossStreakMode.ALLOWANCE
                ? money.hejje.risk.internal.RiskControls.allowance(s, l) : null;
        return new RiskDashboard(mode, s.realizedPnl(), s.unrealizedPnl(), s.totalPnl(), l.maxLossPerDay(), s.grossExposure(),
                l.maxGrossExposure(), s.openPositionCount(), l.maxOpenPositions(), s.tradesToday(), l.maxTradesPerDay(),
                s.consecutiveLosses(), marginPct, killSwitchStore.find(mode).stopNewOrders(), l.lossStreakMode().name(), a == null ? null : a.used(),
                a == null ? null : a.allowance(), a == null ? null : a.reason());
    }

    /** The risk-money multiplier for new entries on a session and why (plan M9.7). */
    public record SizeFactor(BigDecimal factor, String event) {

        public static final SizeFactor NONE = new SizeFactor(BigDecimal.ONE, null);

        public Money apply(Money risk) {
            return factor.compareTo(BigDecimal.ONE) == 0 ? risk
                    : Money.of(risk.toRupees().multiply(factor).setScale(2, RoundingMode.HALF_UP));
        }
    }

    /**
     * {@code hejje.risk.macro-event-size-factor} on a session with a market-wide macro event (calendar or news-detected),
     * else 1. The factor 1.0 (the default) switches the cut off.
     */
    public SizeFactor sizeFactor(java.time.LocalDate date) {
        if (macroEventSizeFactor.compareTo(BigDecimal.ONE) == 0 || date == null) {
            return SizeFactor.NONE;
        }
        for (SizeFactorSource source : sizeSources.orderedStream().toList()) {
            try {
                java.util.Optional<String> event = source.macroEvent(date);
                if (event.isPresent()) {
                    return new SizeFactor(macroEventSizeFactor, event.get());
                }
            } catch (RuntimeException e) {
                log.warn("Macro event lookup for {} failed: {}", date, e.getMessage());
            }
        }
        return SizeFactor.NONE;
    }

    public int positionSize(money.hejje.common.Price entry, money.hejje.common.Price stop, Money riskMoney, int lotSize, int maxQty) {
        return PositionSizer.size(entry, stop, riskMoney, lotSize, maxQty);
    }

    /**
     * Suggests an initial stop for a new position from the ATR of the last sessions' M5 bars (see {@link StopSuggester}),
     * measured from {@code entry} or, when null, the last traded price.
     */
    public StopSuggestion suggestStop(ExecutionMode mode, UUID instrumentId, Side side, Price entry) {
        Instrument instrument = instruments.findById(instrumentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown instrument " + instrumentId));
        Price reference = entry != null ? entry : market.lastPrice(instrumentId).map(Price::of)
                .orElseThrow(() -> new IllegalArgumentException("No quote for " + instrument.hejjeSymbol() + "; pass an entry price"));
        Instant now = clock.now();
        Atr atr = new Atr(StopSuggester.ATR_PERIOD);
        for (Candle candle : market.candles(instrumentId, Timeframe.M5, now.minus(java.time.Duration.ofDays(10)), now)) {
            atr.update(Bar.of(candle, clock.zone()));
        }
        OptionalDouble atrValue = atr.value(0);
        return StopSuggester.suggest(side, reference, atrValue, limitsStore.find(mode).maxStopDistancePct(), instrument.tickSize());
    }
}
