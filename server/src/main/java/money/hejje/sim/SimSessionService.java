package money.hejje.sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import money.hejje.broker.BrokerSimulation;
import money.hejje.common.Drainable;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.SessionReplay;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.orders.Trade;
import money.hejje.risk.RiskLimits;
import money.hejje.risk.RiskService;
import money.hejje.signals.SignalEngine;
import money.hejje.sim.internal.SimSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.stereotype.Service;

/**
 * Replay sessions of a SIM instance (plan M7.2). One session at a time. Creating one resets the simulated trading ledger
 * (orders, fills, positions, signals of earlier sessions), the paper broker (to the session's capital), the market
 * pipeline and the strategy runners, applies the session's risk settings to the SIM limits and starts simulation time at
 * the first session's 09:15. Each step replays one minute: the clock moves to every tick's time (running the scheduled
 * jobs due), the tick goes into the market pipeline and the simulated broker, and the replay waits until every
 * asynchronous consequence (signals, AUTO decisions, fills, module events) has settled; the step ends at the minute's
 * close. Speed only paces steps against the wall clock, so it never changes a result.
 */
@Service
@ConditionalOnProperty(name = "hejje.mode", havingValue = "SIM")
public class SimSessionService {

    private static final Logger log = LoggerFactory.getLogger(SimSessionService.class);
    /** Ledger tables of simulated trading, cleared when a session starts (the SIM database only). */
    static final String LEDGER = "options_position, basket_leg, basket, split_order, trade, order_event, hejje_order, risk_decision, order_intent, "
            + "position, idempotency_record, strategy_position, signal";
    static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(5);

    private final SimTime time;
    private final SessionReplay replay;
    private final BrokerSimulation broker;
    private final InstrumentService instruments;
    private final OrderService orders;
    private final RiskService risk;
    private final SignalEngine engine;
    private final List<Drainable> drainables;
    private final ObjectProvider<EventPublicationRegistry> publications;
    private final JdbcClient jdbc;
    private final SimSessionStore store;
    private final HejjeClock clock;
    private final money.hejje.bots.BotService bots;
    private final org.springframework.context.ApplicationEventPublisher events;
    /** Session bookkeeping (created, finished) is wall time; everything the replay does runs on simulation time. */
    private final Clock wall = Clock.systemUTC();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sim-replay");
        t.setDaemon(true);
        return t;
    });

    private volatile Run current;

    SimSessionService(SimTime time, SessionReplay replay, BrokerSimulation broker, InstrumentService instruments, OrderService orders, RiskService risk,
            SignalEngine engine, List<Drainable> drainables, ObjectProvider<EventPublicationRegistry> publications, JdbcClient jdbc, SimSessionStore store,
            HejjeClock clock, money.hejje.bots.BotService bots, org.springframework.context.ApplicationEventPublisher events) {
        this.bots = bots;
        this.events = events;
        this.time = time;
        this.replay = replay;
        this.broker = broker;
        this.instruments = instruments;
        this.orders = orders;
        this.risk = risk;
        this.engine = engine;
        this.drainables = drainables;
        this.publications = publications;
        this.jdbc = jdbc;
        this.store = store;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void failInterrupted() {
        int n = store.failInterrupted(wall.instant());
        if (n > 0) {
            log.warn("{} replay session(s) left open by a previous process were marked FAILED", n);
        }
    }

    public Optional<SimSession> find(UUID id) {
        Run run = current;
        if (run != null && run.session.id().equals(id)) {
            return Optional.of(run.session);
        }
        return store.find(id);
    }

    /** The session being replayed, if it is not finished. */
    public Optional<SimSession> active() {
        Run run = current;
        return run == null || run.session.state().finished() ? Optional.empty() : Optional.of(run.session);
    }

    public List<SimSession> list(int limit) {
        return store.list(Math.max(1, Math.min(limit, 200)));
    }

    /** Validates and prepares a session (PAUSED at step 0 of its first day). */
    public synchronized SimSession create(SimSessionSpec spec, String actor) {
        Run running = current;
        if (running != null && !running.session.state().finished()) {
            throw new IllegalStateException("Session " + running.session.id() + " is " + running.session.state() + "; one replay at a time");
        }
        List<LocalDate> days = days(spec);
        List<String> warnings = bots(spec, days);
        List<Instrument> resolved = instruments(spec);
        List<UUID> ids = resolved.stream().map(Instrument::id).toList();
        List<String> missing = new ArrayList<>();
        for (LocalDate day : days) {
            for (Instrument i : resolved) {
                if (replay.source(i.id(), day) == SessionReplay.Source.NONE) {
                    missing.add(i.hejjeSymbol().format() + " " + day);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("No M1 candles or recorded ticks for " + String.join(", ", missing.subList(0, Math.min(10, missing.size())))
                    + (missing.size() > 10 ? " and " + (missing.size() - 10) + " more" : "") + " (backfill M1 first, docs/data.md)");
        }

        // a clean simulated account for this session
        engine.stop();
        jdbc.sql("TRUNCATE " + LEDGER + " CASCADE").update();
        Money capital = Money.ofRupees(spec.capitalRupees() == null ? 1_000_000 : spec.capitalRupees());
        broker.reset(capital);
        replay.reset();
        applyRiskSettings(spec, actor);
        time.startAt(open(days.get(0)));
        engine.start();
        settle();

        Instant now = wall.instant();
        SimSession session = new SimSession(UUID.randomUUID(), spec, SimSession.State.PAUSED, SimSession.Speed.MAX, 0, days.size(), days.get(0), 0, 0,
                Money.ZERO, Money.ZERO, null, null, actor, now, null, now, warnings);
        store.insert(session);
        current = new Run(session, days, ids);
        log.info("SIM session {} created: {} day(s) from {}, {} instrument(s)", session.id(), days.size(), days.get(0), ids.size());
        return session;
    }

    public enum Action { PLAY, PAUSE, STEP, CANCEL }

    /**
     * {@code play} replays at the session's speed until paused, cancelled or done; {@code pause} returns once the step in
     * progress has finished; {@code step} replays exactly one minute of a paused session and returns after it;
     * {@code cancel} stops the session. {@code speed} (1, 10, 60, 300, MAX) may accompany any action.
     */
    public SimSession control(UUID id, Action action, String speed) {
        return control(id, action, speed, null);
    }

    /**
     * As {@link #control(UUID, Action, String)}; {@code capitalRupees} (plan M7.4, the harness {@code c} key) resets the
     * simulated account to that capital, only before the first step of the session.
     */
    public SimSession control(UUID id, Action action, String speed, Long capitalRupees) {
        Run run = current;
        if (run == null || !run.session.id().equals(id)) {
            throw new IllegalStateException("Session " + id + " is not the active replay");
        }
        if (capitalRupees != null) {
            if (run.session.dayIndex() != 0 || run.session.step() != 0 || run.session.state() != SimSession.State.PAUSED) {
                throw new IllegalStateException("capital can only change before the session's first step");
            }
            if (capitalRupees <= 0) {
                throw new IllegalArgumentException("capitalRupees must be positive");
            }
            broker.reset(Money.ofRupees(capitalRupees));
            run.withCapital(capitalRupees);
        }
        if (speed != null && !speed.isBlank()) {
            run.update(run.session.state(), SimSession.Speed.of(speed));
        }
        if (action == null) {
            return run.session;
        }
        if (run.session.state().finished()) {
            throw new IllegalStateException("Session " + id + " is " + run.session.state());
        }
        switch (action) {
            case PLAY -> {
                if (run.session.state() != SimSession.State.PLAYING) {
                    run.update(SimSession.State.PLAYING, run.session.speed());
                    run.loop = worker.submit(() -> play(run));
                }
            }
            case PAUSE -> {
                if (run.session.state() == SimSession.State.PLAYING) {
                    run.update(SimSession.State.PAUSED, run.session.speed());
                }
                await(run.loop);
            }
            case STEP -> {
                if (run.session.state() == SimSession.State.PLAYING) {
                    throw new IllegalStateException("Pause the session before stepping");
                }
                await(worker.submit(() -> guarded(run, () -> step(run))));
            }
            case CANCEL -> {
                run.update(SimSession.State.CANCELLED, run.session.speed());
                await(run.loop);
                run.finish(SimSession.State.CANCELLED, null);
            }
        }
        return run.session;
    }

    private void play(Run run) {
        while (run.session.state() == SimSession.State.PLAYING) {
            long started = System.nanoTime();
            if (!guarded(run, () -> step(run))) {
                return;
            }
            long wait = run.session.speed().wallMillisPerStep() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            while (wait > 0 && run.session.state() == SimSession.State.PLAYING) {
                sleep(Math.min(wait, 50));
                wait -= 50;
            }
        }
    }

    /** Runs a replay step; a failure ends the session as FAILED. Returns false when the session is over. */
    private boolean guarded(Run run, Runnable step) {
        try {
            step.run();
            return !run.session.state().finished();
        } catch (RuntimeException e) {
            log.warn("SIM session {} failed at {} step {}", run.session.id(), run.session.sessionDate(), run.session.step(), e);
            run.finish(SimSession.State.FAILED, e.getMessage());
            return false;
        }
    }

    /** One replayed minute of the current day (the first step of a day starts the day). */
    private void step(Run run) {
        if (run.session.state().finished()) {
            return;
        }
        LocalDate day = run.days.get(run.session.dayIndex());
        if (run.ticks == null) {
            beginDay(run, day);
        }
        int step = run.session.step();
        Instant minute = open(day).plus(Duration.ofMinutes(step));
        for (MarketTick tick : run.ticks.getOrDefault(step, List.of())) {
            advanceTo(tick.ts());
            replay.feed(tick);
            broker.onTick(tick);
            settle();
        }
        advanceTo(minute.plus(Duration.ofMinutes(1)));
        settle();
        int next = step + 1;
        if (next < SimSession.STEPS_PER_DAY) {
            run.progress(run.session.dayIndex(), day, next);
            return;
        }
        // end of the day: past the 15:35 regime label, then the next session or the result
        advanceTo(open(day).plus(Duration.ofMinutes(SimSession.STEPS_PER_DAY + 6)));
        settle();
        run.ticks = null;
        int nextDay = run.session.dayIndex() + 1;
        if (nextDay < run.days.size()) {
            run.progress(nextDay, run.days.get(nextDay), 0);
        } else {
            run.progress(run.session.dayIndex(), day, SimSession.STEPS_PER_DAY);
            run.finish(SimSession.State.DONE, null);
        }
    }

    private void beginDay(Run run, LocalDate day) {
        if (run.session.dayIndex() > 0) {
            time.startAt(open(day));
        }
        Map<Integer, List<MarketTick>> byMinute = new TreeMap<>();
        Instant open = open(day);
        for (MarketTick t : replay.ticks(run.instrumentIds, day)) {
            long minute = Duration.between(open, t.ts()).toMinutes();
            if (minute >= 0 && minute < SimSession.STEPS_PER_DAY) {
                byMinute.computeIfAbsent((int) minute, k -> new ArrayList<>()).add(t);
            }
        }
        run.ticks = byMinute;
        settle();
    }

    private void advanceTo(Instant at) {
        Instant now = clock.now();
        if (at.isAfter(now)) {
            time.advance(Duration.between(now, at));
        }
    }

    /**
     * Waits until the step's consequences have settled: every {@link Drainable} (signal engine, AUTO worker, simulated
     * broker) drained and no module event published during this session left in flight, twice in a row.
     */
    void settle() {
        EventPublicationRegistry registry = publications.getIfAvailable();
        Instant since = current == null ? Instant.EPOCH : current.startedWall;
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        int quiet = 0;
        while (quiet < 2) {
            for (Drainable d : drainables) {
                d.drain();
            }
            boolean idle = registry == null || registry.findIncompletePublications().stream().noneMatch(p -> !p.getPublicationDate().isBefore(since));
            quiet = idle ? quiet + 1 : 0;
            if (!idle) {
                if (System.nanoTime() > deadline) {
                    log.warn("SIM step did not settle within {}: a module event is still in flight", SETTLE_TIMEOUT);
                    return;
                }
                sleep(2);
            }
        }
    }

    private SimSession.Result result(Run run) {
        Instant from = open(run.days.get(0)).minus(Duration.ofHours(1));
        Instant to = open(run.days.get(run.days.size() - 1)).plus(Duration.ofHours(8));
        List<Trade> fills = new ArrayList<>(orders.trades(ExecutionMode.SIM, from, to));
        fills.sort(Comparator.comparing(Trade::ts).thenComparing(t -> t.instrumentId().toString()).thenComparing(t -> t.side().name())
                .thenComparingInt(Trade::quantity).thenComparing(Trade::price));
        StringBuilder text = new StringBuilder();
        for (Trade t : fills) {
            text.append(t.ts()).append('|').append(t.instrumentId()).append('|').append(t.side()).append('|').append(t.quantity()).append('|')
                    .append(t.price().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()).append('\n');
        }
        Money friction = Money.ZERO;
        Money net = Money.ZERO;
        for (Position p : orders.positions(ExecutionMode.SIM)) {
            friction = friction.plus(p.fees());
            net = net.plus(p.netRealizedPnl());
        }
        return new SimSession.Result(fills.size(), friction, net, sha256(text.toString()));
    }

    private void applyRiskSettings(SimSessionSpec spec, String actor) {
        RiskLimits l = risk.limits(ExecutionMode.SIM);
        Money loss = spec.lossHaltRupees() == null ? l.maxLossPerDay() : Money.ofRupees(spec.lossHaltRupees());
        RiskLimits updated = new RiskLimits(l.mode(), loss, spec.lossHaltRupees() == null ? l.maxRealizedLoss() : loss,
                spec.lossHaltRupees() == null ? l.maxTotalLossInclUnrealized() : loss, l.maxCapitalDeployed(), l.maxMarginUtilizationPct(),
                spec.maxPositions() == null ? l.maxOpenPositions() : spec.maxPositions(), l.maxGrossExposure(), l.maxTradesPerDay(),
                spec.riskPerTradeRupees() == null ? l.maxRiskPerTrade() : Money.ofRupees(spec.riskPerTradeRupees()), l.maxQuantity(), l.maxNotional(),
                l.minRewardRisk(), l.mandatoryStop(), l.maxStopDistancePct(), l.noNewTradesAfter(), l.noAveragingDown(), l.noReentryMinutes(),
                l.maxConsecutiveLosses(), l.lossStreakMode(), l.allowanceDrawdown(), l.lossStreakAllowance(), l.tradesPerDayWhenGreen());
        if (!updated.equals(l)) {
            risk.updateLimits(updated, actor);
        }
    }

    /**
     * The session's bots (plan M7.3): each must exist, be enabled and allowed in SIM. An LLM bot on a day before its
     * knowledge cutoff is flagged: the model may already know what happened that day.
     */
    private List<String> bots(SimSessionSpec spec, List<LocalDate> days) {
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> entry : spec.bots()) {
            Object id = entry.get("botId");
            money.hejje.bots.Bot bot;
            try {
                bot = bots.find(UUID.fromString(String.valueOf(id))).orElseThrow(() -> new IllegalArgumentException("unknown bot " + id));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage() == null || !e.getMessage().startsWith("unknown bot") ? "bots[].botId must be a bot id" : e.getMessage());
            }
            if (!bot.enabled() || !bot.allowedModes().contains(ExecutionMode.SIM)) {
                throw new IllegalArgumentException("bot " + bot.name() + " is disabled or not allowed in SIM");
            }
            if (bot.knowledgeCutoff() != null) {
                List<LocalDate> known = days.stream().filter(d -> d.isBefore(bot.knowledgeCutoff())).toList();
                if (!known.isEmpty()) {
                    warnings.add("bot " + bot.name() + " (knowledge cutoff " + bot.knowledgeCutoff() + ") may already know " + known.size()
                            + " of the session's days (" + known.get(0) + (known.size() > 1 ? " … " + known.get(known.size() - 1) : "") + ")");
                }
            }
        }
        return warnings;
    }

    private List<LocalDate> days(SimSessionSpec spec) {
        List<LocalDate> days = new ArrayList<>();
        if (!spec.dates().isEmpty()) {
            spec.dates().stream().sorted().distinct().forEach(days::add);
        } else if (spec.from() != null && spec.to() != null) {
            for (LocalDate d = spec.from(); !d.isAfter(spec.to()); d = d.plusDays(1)) {
                if (clock.isTradingDay(d)) {
                    days.add(d);
                }
            }
        }
        if (days.isEmpty()) {
            throw new IllegalArgumentException("dates or from/to naming at least one trading day are required");
        }
        List<LocalDate> closed = days.stream().filter(d -> !clock.isTradingDay(d)).toList();
        if (!closed.isEmpty()) {
            throw new IllegalArgumentException("not trading days: " + closed);
        }
        return days;
    }

    private List<Instrument> instruments(SimSessionSpec spec) {
        List<String> symbols = new ArrayList<>(spec.instruments());
        if (spec.universe() != null && !spec.universe().isBlank()) {
            if (!"nifty50".equalsIgnoreCase(spec.universe().trim())) {
                throw new IllegalArgumentException("universe must be nifty50 (or list instruments)");
            }
            symbols.addAll(nifty50());
        }
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("instruments or universe are required");
        }
        Map<String, Instrument> out = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String s : symbols) {
            Optional<Instrument> i = instruments.resolve(s);
            if (i.isPresent()) {
                out.putIfAbsent(s, i.get());
            } else {
                unknown.add(s);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown instruments: " + unknown);
        }
        return List.copyOf(out.values());
    }

    private static List<String> nifty50() {
        try (var in = SimSessionService.class.getResourceAsStream("/universe/nifty50.yaml")) {
            if (in == null) {
                throw new IllegalStateException("universe/nifty50.yaml is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::trim).filter(l -> l.startsWith("- NSE:"))
                    .map(l -> l.substring(2).trim()).toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant open(LocalDate day) {
        return day.atTime(HejjeClock.SESSION_OPEN).atZone(clock.zone()).toInstant();
    }

    private static void await(Future<?> f) {
        if (f == null) {
            return;
        }
        try {
            f.get(10, TimeUnit.MINUTES);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("replay step did not finish", e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The replay state of the active session (in memory; a restart fails the session). */
    private final class Run {
        volatile SimSession session;
        final List<LocalDate> days;
        final List<UUID> instrumentIds;
        final Instant startedWall = wall.instant();
        Map<Integer, List<MarketTick>> ticks;
        volatile Future<?> loop;

        Run(SimSession session, List<LocalDate> days, List<UUID> instrumentIds) {
            this.session = session;
            this.days = days;
            this.instrumentIds = instrumentIds;
        }

        synchronized void update(SimSession.State state, SimSession.Speed speed) {
            if (session.state().finished()) {
                return;
            }
            SimSession s = session;
            session = new SimSession(s.id(), s.spec(), state, speed, s.dayIndex(), s.days(), s.sessionDate(), s.step(), s.fills(), s.friction(), s.netPnl(),
                    s.resultHash(), s.error(), s.createdBy(), s.createdAt(), s.finishedAt(), wall.instant(), s.warnings());
            store.update(session);
        }

        synchronized void withCapital(long rupees) {
            SimSession s = session;
            SimSessionSpec p = s.spec();
            SimSessionSpec spec = new SimSessionSpec(p.dates(), p.from(), p.to(), p.instruments(), p.universe(), rupees, p.riskPerTradeRupees(),
                    p.lossHaltRupees(), p.maxPositions(), p.bots());
            session = new SimSession(s.id(), spec, s.state(), s.speed(), s.dayIndex(), s.days(), s.sessionDate(), s.step(), s.fills(), s.friction(), s.netPnl(),
                    s.resultHash(), s.error(), s.createdBy(), s.createdAt(), s.finishedAt(), wall.instant(), s.warnings());
            store.updateSpec(session);
        }

        synchronized void progress(int dayIndex, LocalDate day, int step) {
            SimSession s = session;
            session = new SimSession(s.id(), s.spec(), s.state(), s.speed(), dayIndex, s.days(), day, step, s.fills(), s.friction(), s.netPnl(),
                    s.resultHash(), s.error(), s.createdBy(), s.createdAt(), s.finishedAt(), wall.instant(), s.warnings());
            store.update(session);
        }

        synchronized void finish(SimSession.State state, String error) {
            if (session.finishedAt() != null) {
                return;
            }
            SimSession.Result r = result(this);
            SimSession s = session;
            Instant now = wall.instant();
            session = new SimSession(s.id(), s.spec(), state, s.speed(), s.dayIndex(), s.days(), s.sessionDate(), s.step(), r.fills(), r.friction(), r.netPnl(),
                    r.hash(), error, s.createdBy(), s.createdAt(), now, now, s.warnings());
            store.update(session);
            log.info("SIM session {} {}: {} fill(s), friction {}, net {}, hash {}", s.id(), state, r.fills(), r.friction().toRupeesString(),
                    r.netPnl().toRupeesString(), r.hash());
            try {
                events.publishEvent(new SimSessionFinished(s.id(), state)); // reports (M7.5) read the ledger now, before the next session clears it
            } catch (RuntimeException e) {
                log.warn("Reporting SIM session {} failed", s.id(), e);
            }
        }
    }
}
