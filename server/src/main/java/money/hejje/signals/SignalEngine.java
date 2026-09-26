package money.hejje.signals;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.backtest.InstrumentMeta;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.Money;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.event.EventMeta;
import money.hejje.common.event.MarketEvent;
import money.hejje.common.event.MarketTick;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import money.hejje.market.MarketService;
import money.hejje.market.indicators.Bar;
import money.hejje.orders.OrderState;
import money.hejje.signals.internal.LiveExecutionPort;
import money.hejje.signals.internal.SignalStore;
import money.hejje.signals.internal.StrategyRunner;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.dsl.EvalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Owns the runners (one per enabled deployment × instrument in the current mode), feeds them bus events on a single
 * engine thread, persists what they produce and routes order events back to them. Start/stop/refresh are idempotent
 * so a restart (or a deployment change) always converges on the same set of runners (docs/signals.md).
 */
@Component
public class SignalEngine implements money.hejje.common.Drainable {

    private static final Logger log = LoggerFactory.getLogger(SignalEngine.class);

    private final SignalStore store;
    private final StrategyService strategies;
    private final InstrumentService instruments;
    private final MarketService market;
    private final TickBus bus;
    private final LiveExecutionPort livePort;
    private final money.hejje.orders.OrderService orders;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final HejjeProperties properties;
    private final SignalProperties signalProperties;
    private final HejjeClock clock;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final Map<String, StrategyRunner> runners = new ConcurrentHashMap<>();
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "signal-engine");
        t.setDaemon(true);
        return t;
    });
    private volatile AutoCloseable subscription;
    private volatile boolean running;

    SignalEngine(SignalStore store, StrategyService strategies, InstrumentService instruments, MarketService market, TickBus bus, LiveExecutionPort livePort,
            money.hejje.orders.OrderService orders, AuditService audit, ApplicationEventPublisher events, HejjeProperties properties,
            SignalProperties signalProperties, HejjeClock clock, org.springframework.transaction.PlatformTransactionManager txManager) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.orders = orders;
        this.store = store;
        this.strategies = strategies;
        this.instruments = instruments;
        this.market = market;
        this.bus = bus;
        this.livePort = livePort;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.signalProperties = signalProperties;
        this.clock = clock;
    }

    // --- lifecycle ---

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        subscription = bus.subscribe(this::onMarketEvent);
        run(() -> {
            refreshRunners();
            restorePositions();
        });
        log.info("Signal engine started with {} runner(s)", runners.size());
    }

    public synchronized void stop() {
        running = false;
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // nothing to release
            }
            subscription = null;
        }
        run(runners::clear);
    }

    public boolean isRunning() {
        return running;
    }

    /** Re-reads deployments: starts runners for enabled ones, pauses the rest (open positions stay managed). */
    public void refresh() {
        run(this::refreshRunners);
    }

    private void refreshRunners() {
        List<StrategyDeployment> deployments = strategies.deployments(null, properties.mode(), null);
        Map<String, StrategyDeployment> wanted = new LinkedHashMap<>();
        for (StrategyDeployment d : deployments) {
            for (UUID instrumentId : d.instrumentIds()) {
                wanted.put(key(d.id(), instrumentId), d);
            }
        }
        // remove runners for deleted deployments (when flat)
        runners.keySet().removeIf(k -> !wanted.containsKey(k) && runners.get(k).position().isEmpty());
        for (Map.Entry<String, StrategyDeployment> e : wanted.entrySet()) {
            StrategyDeployment d = e.getValue();
            StrategyRunner runner = runners.get(e.getKey());
            if (runner == null) {
                if (!d.enabled()) {
                    continue;
                }
                runner = createRunner(d, instrumentOf(e.getKey()));
                if (runner == null) {
                    continue;
                }
                runners.put(e.getKey(), runner);
            }
            runner.setPaused(!d.enabled());
            StrategyRunner r = runner;
            strategies.versionById(d.versionId()).ifPresent(v -> r.setRiskPerTrade(riskPerTrade(d, v))); // size multiplier changes (M5.1)
        }
        // paused runners are kept (their indicator state stays warm for a resume); only deleted deployments are dropped above
    }

    private static UUID instrumentOf(String key) {
        return UUID.fromString(key.substring(key.indexOf('|') + 1));
    }

    private static String key(UUID deploymentId, UUID instrumentId) {
        return deploymentId + "|" + instrumentId;
    }

    private StrategyRunner createRunner(StrategyDeployment d, UUID instrumentId) {
        Optional<StrategyVersion> version = strategies.versionById(d.versionId());
        Optional<Instrument> instrument = instruments.findById(instrumentId);
        if (version.isEmpty() || instrument.isEmpty()) {
            log.warn("Deployment {} references a missing version or instrument {}", d.id(), instrumentId);
            return null;
        }
        Instrument i = instrument.get();
        InstrumentMeta meta = new InstrumentMeta(i.id(), i.hejjeSymbol().format(), i.type(), i.lotSize(), i.tickSize());
        StrategyRunner runner = new StrategyRunner(d, version.get(), meta, clock, livePort, new EngineCallbacks(), signalProperties.defaultValidityMinutes(),
                riskPerTrade(d, version.get()));
        Instant now = clock.now();
        List<Candle> history = market.candles(instrumentId, version.get().definition().timeframe(), now.minus(java.time.Duration.ofDays(signalProperties.warmupDays())), now);
        runner.warmUp(history);
        if (version.get().definition().entryOrderOrMarket().passive()) {
            market.subscribeFull(java.util.Set.of(instrumentId)); // plan M9.8: a passive entry needs the touch (bid/ask)
        } else {
            market.subscribe(java.util.Set.of(instrumentId));
        }
        log.info("Runner started for {} v{} on {} ({} warm-up bars)", version.get().definition().name(), version.get().version(), meta.symbol(), history.size());
        return runner;
    }

    /** Deployment {@code risk_rupees}, else the definition's, else the module default; times the deployment's size multiplier (M5.1). */
    public Money riskPerTrade(StrategyDeployment d, StrategyVersion version) {
        Money base = baseRiskPerTrade(d, version);
        if (d == null || d.sizeMultiplier().compareTo(BigDecimal.ONE) >= 0) {
            return base;
        }
        return Money.of(base.toRupees().multiply(d.sizeMultiplier()).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    private Money baseRiskPerTrade(StrategyDeployment d, StrategyVersion version) {
        Object param = d == null ? null : d.params().get("risk_rupees");
        if (param instanceof Number n && n.doubleValue() > 0) {
            return Money.of(BigDecimal.valueOf(n.doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        if (version.definition().positionSizing().riskRupees() != null) {
            return version.definition().positionSizing().riskRupees();
        }
        return Money.ofRupees(signalProperties.defaultRiskRupees());
    }

    private void restorePositions() {
        for (StrategyPosition p : store.livePositions(properties.mode())) {
            if (p.deploymentId() == null) {
                continue;
            }
            StrategyRunner runner = runners.get(key(p.deploymentId(), p.instrumentId()));
            if (runner == null) {
                Optional<StrategyDeployment> d = strategies.deployment(p.deploymentId());
                if (d.isEmpty()) {
                    continue;
                }
                runner = createRunner(d.get(), p.instrumentId());
                if (runner == null) {
                    continue;
                }
                runner.setPaused(!d.get().enabled());
                runners.put(key(p.deploymentId(), p.instrumentId()), runner);
            }
            LocalDate today = clock.today();
            int opened = store.openedSince(p.deploymentId(), p.instrumentId(), today.atStartOfDay(clock.zone()).toInstant());
            runner.attach(p, opened);
            runner.verifyStop();
            log.info("Restored strategy position {} ({} {} x{}) on runner {}", p.id(), p.side(), p.instrumentId(), p.quantity(), key(p.deploymentId(), p.instrumentId()));
        }
    }

    // --- events in ---

    private void onMarketEvent(MarketEvent event) {
        if (!running) {
            return;
        }
        if (event instanceof CandleClosedEvent closed) {
            run(() -> dispatchCandle(closed.candle(), closed.micro()));
        } else if (event instanceof MarketTick tick) {
            run(() -> dispatchTick(tick));
        }
    }

    private void dispatchCandle(Candle candle, money.hejje.market.BarMicro micro) {
        for (StrategyRunner runner : runners.values()) {
            if (runner.instrumentId().equals(candle.instrumentId())) {
                try {
                    runner.onCandleClosed(candle, micro);
                } catch (RuntimeException e) {
                    log.error("Runner {} failed on candle {}", runner.meta().symbol(), candle.openTime(), e);
                }
            }
        }
    }

    private void dispatchTick(MarketTick tick) {
        for (StrategyRunner runner : runners.values()) {
            if (runner.instrumentId().equals(tick.instrumentId()) && runner.position().isPresent()) {
                try {
                    runner.onTick(tick.lastPrice());
                } catch (RuntimeException e) {
                    log.error("Runner {} failed on tick", runner.meta().symbol(), e);
                }
            }
        }
    }

    /** Routes an order fill to the position that owns the order (by order id, else by the order's signal), by order role. */
    public void onOrderFilled(UUID orderId, int filledQuantity, BigDecimal averagePrice, boolean complete) {
        if (!complete) {
            return;
        }
        run(() -> resolve(orderId).ifPresent(owned -> {
            StrategyRunner runner = runnerOf(owned.position());
            if (runner == null) {
                return;
            }
            switch (owned.role()) {
                case ENTRY -> runner.onEntryFilled(filledQuantity, averagePrice);
                case STOP -> runner.onStopFilled(averagePrice);
                case EXIT, TARGET -> runner.onExitFilled(averagePrice);
            }
        }));
    }

    public void onOrderStateChanged(UUID orderId, OrderState to) {
        if (to != OrderState.CANCELLED && to != OrderState.REJECTED && to != OrderState.RISK_REJECTED) {
            return;
        }
        run(() -> resolve(orderId).ifPresent(owned -> {
            StrategyRunner runner = runnerOf(owned.position());
            if (runner == null) {
                return;
            }
            StrategyPosition p = owned.position();
            Optional<money.hejje.orders.HejjeOrder> order = orders.findById(orderId);
            if (owned.role() == money.hejje.orders.OrderRole.ENTRY && p.status() == PositionStatus.PENDING_ENTRY && to == OrderState.CANCELLED
                    && order.isPresent() && order.get().filledQuantity() > 0) {
                // plan M9.8: a passive entry cancelled after a partial fill opens with the filled quantity (and its stop)
                runner.onEntryFilled(order.get().filledQuantity(), order.get().averagePrice());
            } else if (owned.role() == money.hejje.orders.OrderRole.ENTRY && p.status() == PositionStatus.PENDING_ENTRY) {
                runner.onEntryFailed("entry order " + to);
            } else if (owned.role() == money.hejje.orders.OrderRole.STOP && p.status() == PositionStatus.OPEN && orderId.equals(p.stopOrderId())) {
                runner.onStopOrderDead(orderId, "stop order " + to);
            }
        }));
    }

    private record OwnedOrder(StrategyPosition position, money.hejje.orders.OrderRole role) {}

    /** The strategy position an order belongs to: by recorded order id, else through the order's intent and signal. */
    private Optional<OwnedOrder> resolve(UUID orderId) {
        Optional<money.hejje.orders.HejjeOrder> order = orders.findById(orderId);
        if (order.isEmpty()) {
            return Optional.empty();
        }
        money.hejje.orders.OrderRole role = order.get().role() == null ? money.hejje.orders.OrderRole.ENTRY : order.get().role();
        Optional<StrategyPosition> byOrder = store.findByOrder(orderId);
        if (byOrder.isPresent()) {
            return Optional.of(new OwnedOrder(byOrder.get(), role));
        }
        return orders.findIntent(order.get().intentId()).map(money.hejje.orders.OrderIntent::signalId).flatMap(store::findBySignal)
                .map(p -> new OwnedOrder(p, role));
    }

    private StrategyRunner runnerOf(StrategyPosition p) {
        if (p.deploymentId() == null) {
            return null;
        }
        return runners.get(key(p.deploymentId(), p.instrumentId()));
    }

    // --- runner access for the service and tests ---

    public Optional<StrategyRunner> runner(UUID deploymentId, UUID instrumentId) {
        return Optional.ofNullable(runners.get(key(deploymentId, instrumentId)));
    }

    public Collection<StrategyRunner> runners() {
        return List.copyOf(runners.values());
    }

    /** The open (or pending) position of a deployment on an instrument, as its runner holds it (plan M7.3). */
    public Optional<StrategyPosition> position(UUID deploymentId, UUID instrumentId) {
        Optional<StrategyPosition>[] out = new Optional[]{Optional.empty()};
        run(() -> {
            StrategyRunner runner = runners.get(key(deploymentId, instrumentId));
            out[0] = runner == null ? Optional.empty() : runner.position();
        });
        return out[0];
    }

    /** A bot's EXIT or TAKE_PROFIT (plan M7.3): flattens the deployment's open position at market; false when there is none. */
    public boolean exitPosition(UUID deploymentId, UUID instrumentId, CloseReason reason) {
        boolean[] out = new boolean[1];
        run(() -> {
            StrategyRunner runner = runners.get(key(deploymentId, instrumentId));
            if (runner != null && runner.position().filter(p -> p.status() == PositionStatus.OPEN).isPresent()) {
                runner.exitNow(reason);
                out[0] = true;
            }
        });
        return out[0];
    }

    /** A bot's MOVE_STOP (plan M7.3): tightens the deployment's stop; false when there is no open position or it would loosen. */
    public boolean tightenStop(UUID deploymentId, UUID instrumentId, BigDecimal stop) {
        boolean[] out = new boolean[1];
        run(() -> {
            StrategyRunner runner = runners.get(key(deploymentId, instrumentId));
            out[0] = runner != null && runner.tightenStop(stop);
        });
        return out[0];
    }

    /** Waits for the engine thread to finish everything submitted so far (SIM replay, plan M7.2). */
    @Override
    public void drain() {
        run(() -> { });
    }

    /** Runs work on the engine thread (or inline under the test profile) and waits for it. */
    public void run(Runnable task) {
        if (signalProperties.inlineDispatch() || Thread.currentThread().getName().equals("signal-engine")) {
            task.run();
            return;
        }
        Future<?> f = thread.submit(task);
        try {
            f.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("Signal engine task did not complete", e);
        }
    }

    /** Called by the service before submitting the entry: the runner consumes the signal and records a pending position. */
    StrategyPosition signalExecuting(Signal signal) {
        StrategyPosition[] out = new StrategyPosition[1];
        run(() -> {
            StrategyRunner runner = signal.deploymentId() == null ? null : runners.get(key(signal.deploymentId(), signal.instrumentId()));
            if (runner != null) {
                runner.onSignalExecuted(signal, null);
                out[0] = runner.position().orElse(null);
            } else {
                // no runner (deployment gone): still persist the pending position so fills are tracked
                out[0] = new EngineCallbacks().positionOpened(null, signal, 0, null, signal.stop(), signal.target(), null);
            }
        });
        return out[0];
    }

    /** The entry order was accepted by the execution pipeline: remember its id on the pending position. */
    void entrySubmitted(StrategyPosition pending, UUID entryOrderId) {
        run(() -> {
            store.setEntryOrder(pending.id(), entryOrderId); // patch only the id: the fill may already have moved the row on
            StrategyRunner runner = runnerOf(pending);
            if (runner != null) {
                runner.setEntryOrderId(pending.id(), entryOrderId);
            }
        });
    }

    /** The entry could not be submitted: drop the pending position. */
    void entryRefused(StrategyPosition pending, String detail) {
        run(() -> {
            StrategyRunner runner = runnerOf(pending);
            if (runner != null && runner.position().isPresent()) {
                runner.onEntryFailed(detail);
            } else {
                new EngineCallbacks().positionClosed(pending, CloseReason.ENTRY_FAILED, null);
            }
        });
    }

    void signalGone(Signal signal) {
        run(() -> {
            if (signal.deploymentId() != null) {
                StrategyRunner runner = runners.get(key(signal.deploymentId(), signal.instrumentId()));
                if (runner != null) {
                    runner.onSignalGone(signal.id());
                }
            }
        });
    }

    /** Persistence + audit + events for what runners do. */
    final class EngineCallbacks implements StrategyRunner.Callbacks {

        @Override
        public Signal signalCreated(StrategyRunner runner, Side side, Bar bar, double stop, BigDecimal target, Instant validUntil, List<EvalResult> evidence) {
            Instant now = clock.now();
            BigDecimal reference = BigDecimal.valueOf(bar.close()).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal stopPrice = BigDecimal.valueOf(stop).setScale(2, java.math.RoundingMode.HALF_UP);
            Signal signal = new Signal(Ids.newId(), runner.version().id(), runner.version().strategyId(), runner.deployment().id(), runner.instrumentId(),
                    properties.mode(), side, reference, stopPrice, target, reference.subtract(stopPrice).abs(), bar.closeTime(), validUntil,
                    StrategyRunner.evidenceOf(evidence), SignalStatus.ACTIVE, null, null, null, now, now);
            store.insert(signal);
            audit.record(AuditEvent.of(AuditEventType.SIGNAL_CREATED, ActorType.STRATEGY).withActorId(runner.version().definition().name())
                    .withStrategyId(signal.strategyId()).withSignalId(signal.id()).withPayload(Map.of("instrumentId", signal.instrumentId().toString(),
                            "side", side.name(), "reference", reference.toPlainString(), "stop", stopPrice.toPlainString(),
                            "target", target == null ? "none" : target.toPlainString(), "validUntil", validUntil.toString(), "barTime", bar.closeTime().toString())));
            audit.record(AuditEvent.of(AuditEventType.STRATEGY_RECOMMENDED, ActorType.STRATEGY).withActorId(runner.version().definition().name())
                    .withStrategyId(signal.strategyId()).withSignalId(signal.id()).withPayload(Map.of("evidence", signal.evidence())));
            events.publishEvent(new SignalGeneratedEvent(EventMeta.create(clock), signal.id(), signal.versionId(), signal.instrumentId()));
            return signal;
        }

        @Override
        public void signalConsumed(Signal signal) {
            store.find(signal.id()).filter(s -> s.status().isActionable()).ifPresent(s -> {
                store.update(s.with(SignalStatus.EXPIRED, "next bar closed", null, null, clock.now()));
                audit.record(AuditEvent.of(AuditEventType.SIGNAL_EXPIRED, ActorType.SYSTEM).withStrategyId(s.strategyId()).withSignalId(s.id()));
            });
        }

        @Override
        public StrategyPosition positionOpened(StrategyRunner runner, Signal signal, int qty, BigDecimal entry, BigDecimal stop, BigDecimal target, UUID entryOrderId) {
            Instant now = clock.now();
            StrategyPosition p = new StrategyPosition(Ids.newId(), signal.id(), signal.deploymentId(), signal.versionId(), signal.strategyId(), signal.instrumentId(),
                    signal.mode(), signal.side(), qty, entry, stop, stop, target, entryOrderId, null, null, PositionStatus.PENDING_ENTRY, null, null, now, null, now);
            store.insert(p);
            return p;
        }

        @Override
        public void positionUpdated(StrategyPosition position) {
            store.update(position);
        }

        @Override
        public void stopPlaced(StrategyPosition position, UUID stopOrderId) {
            audit.record(AuditEvent.of(AuditEventType.STRATEGY_STOP_PLACED, ActorType.STRATEGY).withStrategyId(position.strategyId()).withSignalId(position.signalId())
                    .withOrderId(stopOrderId).withPayload(Map.of("positionId", position.id().toString(), "stop", position.stop().toPlainString(),
                            "quantity", position.quantity())));
            if (position.stop().compareTo(position.initialStop()) != 0) {
                audit.record(AuditEvent.of(AuditEventType.STOP_MODIFIED, ActorType.STRATEGY).withStrategyId(position.strategyId()).withSignalId(position.signalId())
                        .withOrderId(stopOrderId).withPayload(Map.of("positionId", position.id().toString(), "stop", position.stop().toPlainString())));
            }
        }

        @Override
        public void stopMissing(StrategyPosition position, String detail) {
            audit.record(AuditEvent.of(AuditEventType.STOP_MISSING, ActorType.SYSTEM).withStrategyId(position.strategyId()).withSignalId(position.signalId())
                    .withPayload(Map.of("positionId", position.id().toString(), "detail", detail)));
        }

        @Override
        public void exitTriggered(StrategyPosition position, CloseReason reason, UUID exitOrderId) {
            AuditEvent event = AuditEvent.of(AuditEventType.STRATEGY_EXIT_TRIGGERED, ActorType.STRATEGY).withStrategyId(position.strategyId())
                    .withSignalId(position.signalId()).withPayload(Map.of("positionId", position.id().toString(), "reason", reason.name(),
                            "exitOrderId", exitOrderId == null ? "refused" : exitOrderId.toString()));
            audit.record(exitOrderId == null ? event : event.withOrderId(exitOrderId));
        }

        @Override
        public void positionClosed(StrategyPosition position, CloseReason reason, BigDecimal exitPrice) {
            Instant now = clock.now();
            StrategyPosition closed = new StrategyPosition(position.id(), position.signalId(), position.deploymentId(), position.versionId(), position.strategyId(),
                    position.instrumentId(), position.mode(), position.side(), position.quantity(), position.entryPrice(), position.initialStop(), position.stop(),
                    position.target(), position.entryOrderId(), position.stopOrderId(), position.exitOrderId(), PositionStatus.CLOSED, reason, exitPrice,
                    position.openedAt(), now, now);
            // the close and its event commit together: the event is published durably (the engine thread has no
            // transaction of its own) and the review it triggers always sees the close reason
            tx.executeWithoutResult(status -> {
                store.update(closed);
                events.publishEvent(new StrategyPositionClosedEvent(EventMeta.create(clock), closed.id(), closed.entryOrderId(), closed.instrumentId(),
                        closed.mode(), closed.strategyId(), reason));
            });
            if (position.stopOrderId() != null && reason != CloseReason.STOP && reason != CloseReason.TRAILING_STOP) {
                livePort.cancelOrder(position.stopOrderId()); // cancel remaining children after an exit fill
            }
            audit.record(AuditEvent.of(AuditEventType.POSITION_CLOSED, ActorType.STRATEGY).withStrategyId(position.strategyId()).withSignalId(position.signalId())
                    .withPayload(Map.of("positionId", position.id().toString(), "reason", reason.name(), "exitPrice", exitPrice == null ? "n/a" : exitPrice.toPlainString())));
        }
    }

    /** Positions currently managed (live), for the API. */
    public List<StrategyPosition> livePositions() {
        return store.livePositions(properties.mode());
    }

    public List<StrategyPosition> positions(boolean liveOnly, int limit) {
        return store.positions(properties.mode(), liveOnly, limit);
    }

    /** Test/replay helper: how many runners exist. */
    public int runnerCount() {
        return runners.size();
    }

    List<Signal> actionableSignals() {
        return new ArrayList<>(store.actionable(properties.mode()));
    }
}
