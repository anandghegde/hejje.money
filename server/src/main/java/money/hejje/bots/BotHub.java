package money.hejje.bots;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import money.hejje.common.Drainable;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.event.MarketEvent;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import money.hejje.market.MarketService;
import money.hejje.market.QuoteSnapshot;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderService;
import money.hejje.pulse.PulseService;
import money.hejje.regime.RegimeService;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalEngine;
import money.hejje.signals.SignalGeneratedEvent;
import money.hejje.signals.SignalService;
import money.hejje.signals.StrategyPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Decision points and bot connections (plan M7.3). At every closed bar of a bot's timeframe (or every
 * {@code decisionEveryMinutes} of M1 bars) on its universe, a connected bot gets one message with the clock, the bars just
 * closed, the quotes of its universe, its positions and working orders, its deployments' day, and the regime and Pulse.
 * Points are queued when the bars close and delivered by a sender thread; in SIM the replay delivers them itself when it
 * settles a step ({@link #drain}) and waits for each bot's answer up to {@code hejje.sim.decision-timeout} (lockstep), a
 * missed answer being recorded as SKIPPED. Latency p50/p90 and skipped points are tracked per bot. STRATEGY bots need no
 * connection: in SIM their deployment's signals are executed as the bot's decisions.
 */
@Component
public class BotHub implements Drainable {

    private static final Logger log = LoggerFactory.getLogger(BotHub.class);
    private static final int LATENCY_WINDOW = 500;

    /** Where a bot's decision points go (a WebSocket session, a test's in-process bot). */
    public interface Channel {
        void send(Map<String, Object> message);
    }

    /** A bot's decision-point statistics. */
    public record Stats(int points, int answered, int skipped, Long latencyP50Ms, Long latencyP90Ms, boolean connected, long llmTokens,
            java.math.BigDecimal llmCostRupees) {}

    private record Point(Bot bot, String pointId, Map<String, Object> message) {}

    private final BotService bots;
    private final BotDecisions decisions;
    private final InstrumentService instruments;
    private final MarketService market;
    private final SignalEngine engine;
    private final SignalService signals;
    private final OrderService orders;
    private final RegimeService regime;
    private final PulseService pulse;
    private final TickBus bus;
    private final HejjeClock clock;
    private final HejjeProperties properties;
    private final Duration decisionTimeout;

    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> universes = new ConcurrentHashMap<>();
    /** Bars closed per bot and close time, until every instrument of the bot's universe has one. */
    private final Map<UUID, TreeMap<Instant, List<Candle>>> pendingBars = new ConcurrentHashMap<>();
    private final Deque<Point> outbox = new ArrayDeque<>();
    /** Points sent and not answered yet: bot → (point id → sent at, nanos, and the answer's completion). */
    private final Map<UUID, Map<String, Outstanding>> outstanding = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> latencies = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> counts = new ConcurrentHashMap<>(); // points, answered, skipped
    private final Map<UUID, long[]> tokens = new ConcurrentHashMap<>();
    private final Map<UUID, java.math.BigDecimal> llmCost = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bot-hub");
        t.setDaemon(true);
        return t;
    });

    private record Outstanding(long sentNanos, CompletableFuture<Void> answered) {}

    BotHub(BotService bots, BotDecisions decisions, InstrumentService instruments, MarketService market, SignalEngine engine, SignalService signals,
            OrderService orders, RegimeService regime, PulseService pulse, TickBus bus, HejjeClock clock, HejjeProperties properties,
            @Value("${hejje.sim.decision-timeout:PT5S}") Duration decisionTimeout) {
        this.bots = bots;
        this.decisions = decisions;
        this.instruments = instruments;
        this.market = market;
        this.engine = engine;
        this.signals = signals;
        this.orders = orders;
        this.regime = regime;
        this.pulse = pulse;
        this.bus = bus;
        this.clock = clock;
        this.properties = properties;
        this.decisionTimeout = decisionTimeout;
    }

    @EventListener(ApplicationReadyEvent.class)
    void subscribe() {
        bus.subscribe(this::onMarketEvent);
    }

    private boolean lockstep() {
        return properties.mode() == ExecutionMode.SIM;
    }

    // --- connections ---

    /** Connects a bot; a later connection replaces an earlier one. Returns the handle that disconnects it. */
    public AutoCloseable connect(UUID botId, Channel channel) {
        Bot bot = bots.find(botId).orElseThrow(() -> new IllegalArgumentException("Unknown bot " + botId));
        if (bot.kind() == Bot.Kind.STRATEGY) {
            throw new IllegalArgumentException("a STRATEGY bot runs its strategy's rules and takes no connection");
        }
        universes.put(botId, resolve(bot));
        channels.put(botId, channel);
        log.info("Bot {} v{} connected", bot.name(), bot.version());
        return () -> {
            if (channels.remove(botId, channel)) {
                log.info("Bot {} disconnected", bot.name());
            }
        };
    }

    public boolean connected(UUID botId) {
        return channels.containsKey(botId);
    }

    private Set<UUID> resolve(Bot bot) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (String s : bot.universe()) {
            instruments.resolve(s).map(Instrument::id).ifPresent(ids::add);
        }
        return ids;
    }

    // --- decision points ---

    private void onMarketEvent(MarketEvent event) {
        if (!(event instanceof CandleClosedEvent closed) || channels.isEmpty()) {
            return;
        }
        Candle c = closed.candle();
        for (UUID botId : channels.keySet()) {
            Set<UUID> universe = universes.getOrDefault(botId, Set.of());
            if (!universe.contains(c.instrumentId())) {
                continue;
            }
            Optional<Bot> bot = bots.find(botId).filter(Bot::enabled);
            if (bot.isEmpty() || !wanted(bot.get(), c)) {
                continue;
            }
            Instant closeTime = c.openTime().plus(c.timeframe().duration());
            TreeMap<Instant, List<Candle>> pending = pendingBars.computeIfAbsent(botId, k -> new TreeMap<>());
            List<Point> ready = new ArrayList<>();
            synchronized (pending) {
                pending.computeIfAbsent(closeTime, k -> new ArrayList<>()).add(c);
                // earlier close times will not complete any more; the current one completes with every instrument's bar
                for (Instant t : List.copyOf(pending.keySet())) {
                    boolean complete = pending.get(t).stream().map(Candle::instrumentId).distinct().count() >= universe.size();
                    if (t.isBefore(closeTime) || complete) {
                        ready.add(point(bot.get(), t, pending.remove(t)));
                    }
                }
            }
            synchronized (outbox) {
                outbox.addAll(ready);
            }
        }
        if (!lockstep()) {
            worker.submit(this::deliver);
        }
    }

    private static boolean wanted(Bot bot, Candle c) {
        if (bot.decisionEveryMinutes() == null) {
            return c.timeframe() == bot.timeframe();
        }
        if (c.timeframe() != money.hejje.common.Timeframe.M1) {
            return false;
        }
        long minuteOfDay = c.openTime().plus(Duration.ofMinutes(1)).getEpochSecond() / 60;
        return minuteOfDay % bot.decisionEveryMinutes() == 0;
    }

    private Point point(Bot bot, Instant closeTime, List<Candle> bars) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "decision_point");
        m.put("botId", bot.id().toString());
        m.put("pointId", closeTime.toString());
        m.put("clock", clock.now().toString());
        m.put("mode", properties.mode().name());
        m.put("timeframe", bars.isEmpty() ? bot.timeframe().name() : bars.get(0).timeframe().name());
        List<Map<String, Object>> b = new ArrayList<>();
        Map<UUID, String> symbols = new HashMap<>();
        for (Candle c : bars) {
            String symbol = symbols.computeIfAbsent(c.instrumentId(), id -> instruments.findById(id).map(i -> i.hejjeSymbol().format()).orElse(id.toString()));
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("instrument", symbol);
            bar.put("openTime", c.openTime().toString());
            bar.put("open", c.open());
            bar.put("high", c.high());
            bar.put("low", c.low());
            bar.put("close", c.close());
            bar.put("volume", c.volume());
            b.add(bar);
        }
        m.put("bars", b);
        Map<String, Object> quotes = new LinkedHashMap<>();
        for (Map.Entry<UUID, QuoteSnapshot> q : market.quotes(universes.getOrDefault(bot.id(), Set.of())).entrySet()) {
            String symbol = symbols.computeIfAbsent(q.getKey(), id -> instruments.findById(id).map(i -> i.hejjeSymbol().format()).orElse(id.toString()));
            quotes.put(symbol, Map.of("last", q.getValue().lastPrice(), "ts", String.valueOf(q.getValue().ts())));
        }
        m.put("quotes", quotes);
        List<Map<String, Object>> positions = new ArrayList<>();
        List<Map<String, Object>> working = new ArrayList<>();
        for (StrategyPosition p : engine.positions(true, 200)) {
            if (!p.strategyId().equals(bot.strategyId())) {
                continue;
            }
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("instrument", symbols.computeIfAbsent(p.instrumentId(), id -> instruments.findById(id).map(i -> i.hejjeSymbol().format()).orElse(id.toString())));
            pos.put("side", p.side().name());
            pos.put("quantity", p.quantity());
            pos.put("entry", p.entryPrice());
            pos.put("stop", p.stop());
            pos.put("target", p.target());
            pos.put("status", p.status().name());
            positions.add(pos);
            for (UUID orderId : new UUID[]{p.entryOrderId(), p.stopOrderId(), p.exitOrderId()}) {
                if (orderId == null) {
                    continue;
                }
                orders.findById(orderId).filter(o -> !o.state().isTerminal()).ifPresent(o -> working.add(order(o, pos.get("instrument"))));
            }
        }
        m.put("positions", positions);
        m.put("workingOrders", working);
        m.put("regime", safe(() -> {
            var r = regime.current();
            return r == null ? null : Map.of("trend", r.trend().name(), "volatility", r.volatility().name(), "opening", r.opening().name(), "breadth",
                    r.breadth().name());
        }));
        m.put("pulse", safe(() -> {
            var p = pulse.current();
            return p == null || p.technical() == null ? null : Map.of("direction", p.technical().direction().name(), "strength", p.technical().strength().name(),
                    "score", p.technical().score());
        }));
        if (lockstep()) {
            m.put("answerWithinMs", decisionTimeout.toMillis());
        }
        return new Point(bot, closeTime.toString(), m);
    }

    private static Map<String, Object> order(HejjeOrder o, Object symbol) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.id().toString());
        m.put("instrument", symbol);
        m.put("side", o.side().name());
        m.put("type", o.orderType().name());
        m.put("quantity", o.quantity());
        m.put("trigger", o.triggerPrice());
        m.put("limit", o.limitPrice());
        m.put("state", o.state().name());
        m.put("role", o.role() == null ? null : o.role().name());
        return m;
    }

    private static Object safe(java.util.function.Supplier<Object> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sends every queued point; in SIM waits for each answer (or the timeout). */
    private void deliver() {
        while (true) {
            Point p;
            synchronized (outbox) {
                p = outbox.poll();
            }
            if (p == null) {
                return;
            }
            Channel channel = channels.get(p.bot().id());
            if (channel == null) {
                continue;
            }
            Outstanding o = new Outstanding(System.nanoTime(), new CompletableFuture<>());
            outstanding.computeIfAbsent(p.bot().id(), k -> new ConcurrentHashMap<>()).put(p.pointId(), o);
            counts.computeIfAbsent(p.bot().id(), k -> new int[3])[0]++;
            try {
                channel.send(p.message());
            } catch (RuntimeException e) {
                log.warn("Decision point to bot {} failed: {}", p.bot().name(), e.getMessage());
            }
            if (!lockstep()) {
                continue;
            }
            try {
                o.answered().get(decisionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                if (outstanding.getOrDefault(p.bot().id(), Map.of()).remove(p.pointId()) != null) {
                    decisions.skipped(p.bot(), p.pointId(), decisionTimeout.toMillis(), "no answer within " + decisionTimeout.toMillis() + " ms");
                    counts.computeIfAbsent(p.bot().id(), k -> new int[3])[2]++;
                }
            } catch (Exception e) {
                log.warn("Waiting for bot {} failed: {}", p.bot().name(), e.getMessage());
            }
        }
    }

    /** A bot's answer (WebSocket or REST): applies the decisions and releases a waiting replay. */
    public List<BotDecision> answer(UUID botId, BotDecision.Reply reply) {
        Bot bot = bots.find(botId).orElseThrow(() -> new IllegalArgumentException("Unknown bot " + botId));
        Outstanding o = reply.pointId() == null ? null : outstanding.getOrDefault(botId, Map.of()).remove(reply.pointId());
        Long latency = o == null ? null : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - o.sentNanos());
        try {
            List<BotDecision> out = decisions.apply(bot, reply, latency);
            if (reply.usage() != null) {
                BotDecision.Usage u = reply.usage();
                tokens.computeIfAbsent(botId, k -> new long[1])[0] += (u.inputTokens() == null ? 0 : u.inputTokens()) + (u.outputTokens() == null ? 0 : u.outputTokens());
                if (u.costRupees() != null) {
                    llmCost.merge(botId, u.costRupees(), java.math.BigDecimal::add);
                }
            }
            if (latency != null) {
                Deque<Long> window = latencies.computeIfAbsent(botId, k -> new ArrayDeque<>());
                synchronized (window) {
                    window.addLast(latency);
                    while (window.size() > LATENCY_WINDOW) {
                        window.removeFirst();
                    }
                }
                counts.computeIfAbsent(botId, k -> new int[3])[1]++;
            }
            return out;
        } finally {
            if (o != null) {
                o.answered().complete(null);
            }
        }
    }

    public Stats stats(UUID botId) {
        int[] c = counts.getOrDefault(botId, new int[3]);
        List<Long> sorted;
        Deque<Long> window = latencies.getOrDefault(botId, new ArrayDeque<>());
        synchronized (window) {
            sorted = window.stream().sorted().toList();
        }
        return new Stats(c[0], c[1], c[2], percentile(sorted, 0.5), percentile(sorted, 0.9), connected(botId), tokens.getOrDefault(botId, new long[1])[0],
                llmCost.getOrDefault(botId, java.math.BigDecimal.ZERO));
    }

    static Long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return null;
        }
        int i = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    // --- STRATEGY bots ---

    /** In SIM a STRATEGY bot's signals execute as its decisions (on the hub's thread, drained by the replay). */
    @EventListener
    void onSignal(SignalGeneratedEvent event) {
        if (!lockstep()) {
            return;
        }
        worker.submit(() -> {
            try {
                Optional<Signal> s = signals.find(event.signalId());
                if (s.isEmpty()) {
                    return;
                }
                for (Bot bot : bots.list()) {
                    if (bot.kind() == Bot.Kind.STRATEGY && bot.enabled() && bot.strategyId().equals(s.get().strategyId())) {
                        decisions.strategySignal(bot, s.get());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("STRATEGY bot signal {} failed", event.signalId(), e);
            }
        });
    }

    /** SIM: delivers the queued decision points (waiting for the answers) and finishes the STRATEGY bots' work. */
    @Override
    public void drain() {
        if (lockstep()) {
            deliver();
        }
        try {
            worker.submit(() -> { }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("bot hub did not drain", e);
        }
    }
}
