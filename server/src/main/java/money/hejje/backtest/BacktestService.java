package money.hejje.backtest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import money.hejje.backtest.internal.BacktestRunner;
import money.hejje.backtest.internal.BacktestStore;
import money.hejje.common.Ids;
import money.hejje.common.Money;
import money.hejje.common.Timeframe;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.springframework.stereotype.Service;

/** Public API of the backtest module: submit, inspect, cancel; also the synchronous path used by tests and parity runs. */
@Service
public class BacktestService {

    private final money.hejje.risk.RiskService risk;
    private final BacktestStore store;
    private final BacktestEngine engine;
    private final BacktestRunner runner;
    private final StrategyService strategies;
    private final InstrumentService instruments;
    private final MarketService market;
    private final BacktestProperties properties;
    private final RegimeService regime;
    private final HejjeClock clock;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    BacktestService(BacktestStore store, BacktestEngine engine, BacktestRunner runner, StrategyService strategies, InstrumentService instruments,
            MarketService market, BacktestProperties properties, RegimeService regime, HejjeClock clock,
            org.springframework.context.ApplicationEventPublisher events, org.springframework.transaction.PlatformTransactionManager txManager,
            money.hejje.risk.RiskService risk) {
        this.risk = risk;
        this.store = store;
        this.engine = engine;
        this.runner = runner;
        this.strategies = strategies;
        this.instruments = instruments;
        this.market = market;
        this.properties = properties;
        this.regime = regime;
        this.clock = clock;
        this.events = events;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        runner.attach(this);
    }

    /** Queues a backtest; the bounded runner executes it. */
    public Backtest submit(BacktestSpec spec, String by) {
        StrategyVersion version = requireVersion(spec.versionId());
        BacktestInput input = prepare(spec, version); // fail fast on bad specs / no data
        if (input.candles().values().stream().allMatch(List::isEmpty)) {
            throw new BacktestException("No candles for the requested instruments and range");
        }
        Backtest backtest = new Backtest(Ids.newId(), spec.versionId(), spec, BacktestStatus.QUEUED, 0, clock.now(), null, null, null, Map.of(),
                List.of(), List.of(), 0, 0, 0, null, Engine.JAVA, null, by);
        store.insert(backtest);
        runner.enqueue(backtest.id());
        return backtest;
    }

    /** Runs a queued backtest on the calling thread (used by the runner). */
    public void execute(UUID id, BooleanSupplier cancelled) {
        Backtest backtest = store.find(id).orElse(null);
        if (backtest == null || backtest.status() != BacktestStatus.QUEUED) {
            return;
        }
        store.markRunning(id, clock.now());
        try {
            StrategyVersion version = requireVersion(backtest.versionId());
            BacktestInput input = prepare(backtest.spec(), version);
            int[] last = {0};
            IntConsumer progress = pct -> {
                if (pct - last[0] >= 5) {
                    last[0] = pct;
                    store.progress(id, pct);
                }
            };
            BacktestResult result = engine.run(input, cancelled, progress);
            if (result.trades().size() > properties.maxTradesPerBacktest()) {
                throw new BacktestException("Too many trades (" + result.trades().size() + ")");
            }
            store.insertTrades(id, result.trades().stream().map(t -> withBacktestId(t, id)).toList());
            tx.executeWithoutResult(status -> {
                store.finish(id, result, clock.now());
                events.publishEvent(new BacktestFinished(EventMeta.create(clock), id, backtest.versionId()));
            });
        } catch (BacktestEngine.CancelledException e) {
            store.fail(id, BacktestStatus.CANCELLED, "cancelled", clock.now());
        } catch (RuntimeException e) {
            store.fail(id, BacktestStatus.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), clock.now());
        }
    }

    /** Runs synchronously and persists the result (tests, scripted runs). */
    public Backtest runNow(BacktestSpec spec, String by) {
        Backtest queued = new Backtest(Ids.newId(), spec.versionId(), spec, BacktestStatus.QUEUED, 0, clock.now(), null, null, null, Map.of(),
                List.of(), List.of(), 0, 0, 0, null, Engine.JAVA, null, by);
        requireVersion(spec.versionId());
        store.insert(queued);
        execute(queued.id(), () -> false);
        return store.find(queued.id()).orElseThrow();
    }

    /** Runs a spec on the calling thread without persisting anything (score sensitivity runs, parity checks). */
    public BacktestResult evaluate(BacktestSpec spec) {
        return engine.run(prepare(spec, requireVersion(spec.versionId())));
    }

    /**
     * Runs {@code definition} instead of the version's own (an experiment variant, M4.7) on the calling thread without
     * persisting anything. The spec's version supplies everything else (strategy, risk resolution).
     */
    public BacktestResult evaluate(BacktestSpec spec, StrategyDefinition definition) {
        StrategyVersion base = requireVersion(spec.versionId());
        StrategyVersion variant = new StrategyVersion(base.id(), base.strategyId(), base.version(), base.definitionYaml(), definition, base.definitionHash(),
                "experiment variant", base.parentVersionId(), base.createdBy(), base.createdAt(), base.status());
        return engine.run(prepare(spec, variant));
    }

    /**
     * The backtest a version is judged by: the newest DONE run that validates (out-of-sample slice with enough trades
     * and no FAIL), else the newest DONE run with an out-of-sample slice, else the newest DONE run. A research run with
     * a {@link SessionFilter} is never that backtest: its entries were restricted from outside the strategy.
     */
    public Optional<Backtest> baseBacktest(UUID versionId) {
        List<Backtest> done = store.findByVersion(versionId).stream()
                .filter(b -> b.status() == BacktestStatus.DONE && b.spec().sessionFilter() == null).toList();
        return done.stream().filter(money.hejje.backtest.internal.BacktestEvidenceAdapter::validates).findFirst()
                .or(() -> done.stream().filter(b -> b.spec().splits().hasOutOfSample()).findFirst())
                .or(() -> done.stream().findFirst());
    }

    /** Loads everything the engine needs for a spec (definition, instruments, candles with warm-up). */
    public BacktestInput prepare(BacktestSpec spec, StrategyVersion version) {
        StrategyDefinition def = version.definition();
        if (!def.legs().isEmpty()) {
            throw new BacktestException("Options strategies are PAPER-only: the historical store has no option candles to replay their legs (docs/options.md)");
        }
        Timeframe timeframe = spec.timeframe() == null ? def.timeframe() : spec.timeframe();
        List<InstrumentMeta> resolved = spec.instrumentIds().isEmpty() ? resolveUniverse(def) : spec.instrumentIds().stream().map(this::metaFor).toList();
        if (resolved.isEmpty()) {
            throw new BacktestException("The strategy universe resolved to no instruments");
        }
        ZoneId zone = clock.zone();
        Instant from = spec.from().minusDays(properties.warmupDays()).atStartOfDay(zone).toInstant();
        Instant to = spec.to().plusDays(1).atStartOfDay(zone).toInstant();
        Map<UUID, InstrumentMeta> metas = new LinkedHashMap<>();
        Map<UUID, List<Candle>> candles = new LinkedHashMap<>();
        for (InstrumentMeta meta : resolved) {
            metas.put(meta.id(), meta);
            candles.put(meta.id(), market.candles(meta.id(), timeframe, from, to));
        }
        BacktestSpec effective = spec.timeframe() == null ? new BacktestSpec(spec.versionId(), spec.instrumentIds(), timeframe, spec.from(), spec.to(),
                spec.fillModel(), spec.slippageBps(), spec.costModelVersion(), spec.splits(), spec.initialCapital(), spec.riskPerTrade(),
                spec.sessionFilter()) : spec;
        // plan M9.7: sessions with a market-wide macro event size at hejje.risk.macro-event-size-factor, as live
        Map<java.time.LocalDate, java.math.BigDecimal> factors = new LinkedHashMap<>();
        for (java.time.LocalDate d = spec.from(); !d.isAfter(spec.to()); d = d.plusDays(1)) {
            money.hejje.risk.RiskService.SizeFactor f = risk.sizeFactor(d);
            if (f.event() != null) {
                factors.put(d, f.factor());
            }
        }
        return new BacktestInput(def, effective, metas, candles, riskPerTrade(spec, def), factors);
    }

    /**
     * The universe for a backtest: "nearest future" targets map to the continuous series when one has been built
     * (docs/data.md), otherwise to today's nearest contract; symbols and indices resolve through the instrument master.
     */
    private List<InstrumentMeta> resolveUniverse(StrategyDefinition def) {
        Map<UUID, InstrumentMeta> out = new LinkedHashMap<>();
        for (StrategyDefinition.UniverseEntry target : strategies.universeTargets(def)) {
            Optional<InstrumentMeta> meta = switch (target.kind()) {
                case NEAREST_FUTURE -> market.continuousSeriesFor(target.value()).map(BacktestService::metaOf)
                        .or(() -> instruments.nearestFuture(target.value()).map(BacktestService::metaOf));
                case SYMBOL -> instruments.resolve(target.value()).map(BacktestService::metaOf);
                case INDEX -> instruments.resolve("INDEX:" + target.value()).map(BacktestService::metaOf);
                case ALIAS -> Optional.empty();
            };
            meta.ifPresent(m -> out.putIfAbsent(m.id(), m));
        }
        return new java.util.ArrayList<>(out.values());
    }

    /** An explicit instrument id may be an instrument or a continuous series. */
    private InstrumentMeta metaFor(UUID id) {
        return instruments.findById(id).map(BacktestService::metaOf)
                .or(() -> market.continuousSeries(id).map(BacktestService::metaOf))
                .orElseThrow(() -> new BacktestException("Unknown instrument " + id));
    }

    static InstrumentMeta metaOf(Instrument instrument) {
        return new InstrumentMeta(instrument.id(), instrument.hejjeSymbol().format(), instrument.type(), instrument.lotSize(), instrument.tickSize());
    }

    static InstrumentMeta metaOf(money.hejje.market.ContinuousSeries series) {
        return new InstrumentMeta(series.id(), series.symbol(), money.hejje.common.InstrumentType.FUT, series.lotSize(), series.tickSize());
    }

    private Money riskPerTrade(BacktestSpec spec, StrategyDefinition def) {
        if (spec.riskPerTrade() != null) {
            return spec.riskPerTrade();
        }
        if (def.positionSizing().riskRupees() != null) {
            return def.positionSizing().riskRupees();
        }
        if (def.positionSizing().riskPercentOfCapital() != null) {
            return spec.initialCapital().times(def.positionSizing().riskPercentOfCapital().movePointLeft(2), java.math.RoundingMode.DOWN);
        }
        return Money.ofRupees(properties.defaultRiskRupees());
    }

    public Optional<Backtest> get(UUID id) {
        return store.find(id);
    }

    public List<Backtest> list(UUID versionId) {
        return versionId == null ? store.findRecent(100) : store.findByVersion(versionId);
    }

    public List<BacktestTrade> trades(UUID id, Split split) {
        store.find(id).orElseThrow(() -> new BacktestException("Backtest " + id + " not found"));
        return store.findTrades(id, split);
    }

    /**
     * Regime-conditional statistics (plan M3.1): trades grouped by the regime labels of their entry sessions along
     * {@code dims} (default trend × volatility) and the group matching the current snapshot. Computed on read from
     * the stored labels, so relabelling the past changes it without re-running the backtest.
     */
    public RegimeBreakdown regimeBreakdown(UUID id, List<String> dims) {
        Backtest b = store.find(id).orElseThrow(() -> new BacktestException("Backtest " + id + " not found"));
        List<BacktestTrade> trades = store.findTrades(id, null);
        Map<LocalDate, RegimeSnapshot> labels = regime.labels(b.spec().from(), b.spec().to());
        RegimeSnapshot current = regime.current();
        try {
            return money.hejje.backtest.internal.RegimeGrouping.group(trades, labels, current, dims, clock.zone());
        } catch (IllegalArgumentException e) {
            throw new BacktestException(e.getMessage());
        }
    }

    /** Cancels a queued/running backtest (marks it CANCELLED); deletes a finished one. */
    public Backtest cancel(UUID id) {
        Backtest b = store.find(id).orElseThrow(() -> new BacktestException("Backtest " + id + " not found"));
        if (b.status() == BacktestStatus.QUEUED || b.status() == BacktestStatus.RUNNING) {
            runner.cancel(id);
            if (b.status() == BacktestStatus.QUEUED) {
                store.fail(id, BacktestStatus.CANCELLED, "cancelled before start", clock.now());
            }
        } else {
            store.delete(id);
        }
        return store.find(id).orElse(b);
    }

    private StrategyVersion requireVersion(UUID versionId) {
        return strategies.versionById(versionId).orElseThrow(() -> new BacktestException("Strategy version " + versionId + " not found"));
    }

    private static BacktestTrade withBacktestId(BacktestTrade t, UUID id) {
        return new BacktestTrade(t.id(), id, t.instrumentId(), t.split(), t.entryTime(), t.exitTime(), t.side(), t.qty(), t.entryPrice(), t.exitPrice(),
                t.stop(), t.target(), t.grossPnl(), t.costs(), t.netPnl(), t.rMultiple(), t.exitReason(), t.evidence());
    }

    /** Sessions in the range that are trading days, for callers that size warm-up or splits. */
    public int expectedSessions(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (clock.isTradingDay(d)) {
                n++;
            }
        }
        return n;
    }
}
