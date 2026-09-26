package money.hejje.swing.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.event.MarketEvent;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import money.hejje.market.MarketService;
import money.hejje.ratings.Base;
import money.hejje.ratings.BaseStatus;
import money.hejje.ratings.RatingsService;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.strategy.DeploymentChanged;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.swing.SwingEntries;
import money.hejje.swing.SwingPosition;
import money.hejje.swing.SwingProperties;
import money.hejje.swing.SwingTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Watches the READY trade plans of the swing deployments during the session (plan M11.4). The setups of a session are
 * the bases and reversals whose status as of the previous session is FORMING or NEAR_PIVOT (detected, not triggered, not
 * closed) on an instrument of an enabled swing deployment and with no open swing position; their instruments are
 * subscribed. Every closed M1 bar of a watched instrument goes through {@link SwingTrigger}; a trigger becomes one signal
 * per setup on the normal signal path ({@link SignalService#createExternal}), with the plan's stop and goal and the entry
 * LIMIT in its evidence. Works the same on replayed bars in SIM.
 */
@Component
public class SwingWatcher {

    private static final Logger log = LoggerFactory.getLogger(SwingWatcher.class);
    static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);
    static final Set<BaseStatus> READY = Set.of(BaseStatus.FORMING, BaseStatus.NEAR_PIVOT);

    private final TickBus bus;
    private final SwingEntries entries;
    private final RatingsService ratings;
    private final MarketService market;
    private final InstrumentService instruments;
    private final SignalService signals;
    private final SwingStore book;
    private final SwingProperties properties;
    private final HejjeClock clock;

    /** One watched setup; guarded by the watcher's lock. */
    private static final class W {
        final StrategyDeployment deployment;
        final SwingTrigger.Setup setup;
        final SwingTrigger.Params params;
        final BigDecimal tick;
        long volume;
        LocalDate volumeDay;
        String state = "WATCHING";
        BigDecimal lastClose;
        BigDecimal pace;
        UUID signalId;

        W(StrategyDeployment deployment, SwingTrigger.Setup setup, SwingTrigger.Params params, BigDecimal tick) {
            this.deployment = deployment;
            this.setup = setup;
            this.params = params;
            this.tick = tick;
        }
    }

    private final Map<UUID, List<W>> watched = new LinkedHashMap<>();
    private LocalDate day;
    private volatile boolean stale = true;

    SwingWatcher(TickBus bus, SwingEntries entries, RatingsService ratings, MarketService market, InstrumentService instruments, SignalService signals,
            SwingStore book, SwingProperties properties, HejjeClock clock) {
        this.bus = bus;
        this.entries = entries;
        this.ratings = ratings;
        this.market = market;
        this.instruments = instruments;
        this.signals = signals;
        this.book = book;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        bus.subscribe(this::onMarketEvent);
        try {
            prepare();
        } catch (RuntimeException e) {
            log.warn("Swing setups not loaded at startup: {}", e.getMessage());
        }
    }

    @EventListener
    void onDeploymentChanged(DeploymentChanged event) {
        invalidate();
    }

    /** The deployments or the plans changed: the next bar (or {@link #prepare()}) reloads the setups. */
    public void invalidate() {
        stale = true;
    }

    /** Loads today's setups and subscribes their instruments (before the open, at startup, after a deployment). */
    public synchronized void prepare() {
        reload(clock.today());
    }

    void onMarketEvent(MarketEvent event) {
        if (!(event instanceof CandleClosedEvent closed) || closed.candle().timeframe() != Timeframe.M1) {
            return;
        }
        Candle c = closed.candle();
        LocalDate date = c.openTime().atZone(clock.zone()).toLocalDate();
        try {
            synchronized (this) {
                if (stale || !date.equals(day)) {
                    reload(date);
                }
                List<W> ws = watched.get(c.instrumentId());
                if (ws != null) {
                    for (W w : ws) {
                        evaluate(w, c, date);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Swing watch of {} failed: {}", c.instrumentId(), e.getMessage());
        }
    }

    private void evaluate(W w, Candle c, LocalDate date) {
        if (!date.equals(w.volumeDay)) {
            w.volume = 0;
            w.volumeDay = date;
        }
        w.volume += c.volume();
        w.lastClose = c.close();
        if (w.signalId != null) {
            return;
        }
        LocalTime closeTime = c.openTime().plus(Duration.ofMinutes(1)).atZone(clock.zone()).toLocalTime();
        int minutes = (int) Duration.between(SESSION_OPEN, closeTime).toMinutes();
        SwingTrigger.Result r = SwingTrigger.evaluate(w.setup, c.close(), w.volume, minutes, w.params, w.tick);
        w.pace = r.pace();
        w.state = r.verdict().name();
        if (r.verdict() != SwingTrigger.Verdict.TRIGGER) {
            return;
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("baseId", w.setup.baseId().toString());
        plan.put("type", w.setup.type().name());
        plan.put("pivot", w.setup.pivot().toPlainString());
        plan.put("buyHigh", w.setup.buyHigh().toPlainString());
        plan.put("limit", r.limit().toPlainString());
        plan.put("pace", r.pace() == null ? null : r.pace().toPlainString());
        plan.put("avgVolume50", w.setup.avgVolume50());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "swing");
        evidence.put("rule", w.setup.type().reversal() ? "reversal: close at or above the pivot inside the buy zone"
                : "base breakout: close at or above the pivot inside the buy zone on volume pace " + (r.pace() == null ? "?" : r.pace().toPlainString()) + "x");
        evidence.put("swing", plan);
        Signal signal = signals.createExternal(w.deployment, w.setup.instrumentId(), Side.BUY, c.close(), w.setup.stop(), w.setup.goal(),
                Duration.ofMinutes(properties.signalValidityMinutes()), "swing", evidence);
        w.signalId = signal.id();
        w.state = "TRIGGERED";
        log.info("Swing setup {} {} triggered at {} (pace {}): signal {}", w.setup.symbol(), w.setup.type(), c.close(), r.pace(), signal.id());
    }

    private void reload(LocalDate date) {
        stale = false;
        day = date;
        watched.clear();
        List<StrategyDeployment> deployments = entries.deployments().stream().filter(StrategyDeployment::enabled).toList();
        if (deployments.isEmpty()) {
            return;
        }
        LocalDate asOf = clock.previousTradingDay(date);
        List<Base> ready = ratings.bases(null, null, asOf).stream().filter(b -> READY.contains(b.status())).toList();
        Set<UUID> held = new HashSet<>();
        for (SwingPosition p : book.list(deployments.get(0).mode(), SwingPosition.Status.OPEN, 1000)) {
            held.add(p.instrumentId());
        }
        Instant dayStart = date.atStartOfDay(clock.zone()).toInstant();
        Map<String, UUID> firedToday = new LinkedHashMap<>();
        for (Signal s : signals.list(null, dayStart, 500)) {
            s.evidence().stream().map(e -> e.get("swing")).filter(m -> m instanceof Map<?, ?>).map(m -> ((Map<?, ?>) m).get("baseId"))
                    .filter(java.util.Objects::nonNull).findFirst().ifPresent(id -> firedToday.put(s.deploymentId() + "|" + id, s.id()));
        }
        Set<UUID> subscribe = new HashSet<>();
        for (StrategyDeployment d : deployments) {
            SwingTrigger.Params params = entries.params(d);
            Set<UUID> members = new HashSet<>(d.instrumentIds());
            for (Base b : ready) {
                if (!members.contains(b.instrumentId()) || held.contains(b.instrumentId())) {
                    continue;
                }
                Instrument instrument = instruments.findById(b.instrumentId()).orElse(null);
                if (instrument == null) {
                    continue;
                }
                SwingTrigger.Setup setup = new SwingTrigger.Setup(b.id(), b.instrumentId(), b.symbol(), b.type(), b.pivot(), b.buyHigh(), b.stop(), b.goal(),
                        avgVolume50(b.instrumentId(), date));
                W w = new W(d, setup, params, instrument.tickSize());
                UUID fired = firedToday.get(d.id() + "|" + b.id());
                if (fired != null) {
                    w.signalId = fired;
                    w.state = "TRIGGERED";
                }
                w.volumeDay = date;
                w.volume = market.candles(b.instrumentId(), Timeframe.M1, dayStart, clock.now()).stream().mapToLong(Candle::volume).sum();
                watched.computeIfAbsent(b.instrumentId(), k -> new ArrayList<>()).add(w);
                subscribe.add(b.instrumentId());
            }
        }
        if (!subscribe.isEmpty()) {
            try {
                market.subscribe(subscribe);
            } catch (RuntimeException e) {
                log.warn("Subscribing the swing setups failed: {}", e.getMessage());
            }
        }
        log.info("Swing: {} setup(s) on {} instrument(s) watched for {}", watched.values().stream().mapToInt(List::size).sum(), watched.size(), date);
    }

    /** The mean daily volume of the 50 sessions before {@code date}; null with fewer than 50. */
    private Long avgVolume50(UUID instrumentId, LocalDate date) {
        Instant start = date.minusDays(120).atStartOfDay(clock.zone()).toInstant();
        Instant end = date.atStartOfDay(clock.zone()).toInstant().minusSeconds(1);
        List<Candle> daily = market.candles(instrumentId, Timeframe.D1, start, end).stream()
                .filter(c -> c.openTime().atZone(clock.zone()).toLocalDate().isBefore(date)).sorted(Comparator.comparing(Candle::openTime)).toList();
        if (daily.size() < 50) {
            return null;
        }
        return BigDecimal.valueOf(daily.subList(daily.size() - 50, daily.size()).stream().mapToLong(Candle::volume).sum())
                .divide(BigDecimal.valueOf(50), 0, RoundingMode.HALF_UP).longValue();
    }

    /** What the watcher holds now, by symbol. */
    public synchronized List<SwingEntries.Watched> watched() {
        if (stale || day == null || !day.equals(clock.today())) {
            reload(clock.today());
        }
        List<SwingEntries.Watched> out = new ArrayList<>();
        for (List<W> ws : watched.values()) {
            for (W w : ws) {
                SwingTrigger.Setup s = w.setup;
                out.add(new SwingEntries.Watched(w.deployment.id(), s.baseId(), s.instrumentId(), s.symbol(), s.type().name(), s.pivot(), s.buyHigh(), s.stop(),
                        s.goal(), s.avgVolume50(), w.state, w.lastClose, w.pace, w.signalId));
            }
        }
        out.sort(Comparator.comparing(SwingEntries.Watched::symbol));
        return out;
    }
}
