package money.hejje.bots.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import money.hejje.bots.Bot;
import money.hejje.bots.BotDecision;
import money.hejje.bots.BotHub;
import money.hejje.bots.BotService;
import money.hejje.calibration.CalibrationService;
import money.hejje.calibration.LabelRule;
import money.hejje.calibration.Prediction;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.jev.JevBotRules;
import money.hejje.jev.JevBotState;
import money.hejje.jev.JevMarketState;
import money.hejje.llm.JevQuestion;
import money.hejje.llm.JevQuestionSet;
import money.hejje.llm.JevQuestionSets;
import money.hejje.llm.JevResult;
import money.hejje.llm.JevService;
import money.hejje.market.Candle;
import money.hejje.signals.PositionStatus;
import money.hejje.signals.SignalEngine;
import money.hejje.signals.StrategyPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs bots of kind {@code JEV} in-process (plan M9.5, docs/jev.md "The Jev bot"). Each is connected to the
 * {@link BotHub} like a WebSocket bot; at a decision point it builds a state from Hejje's own candles, order-book data
 * and context, asks Jev (open positions and stage 1 at once, then stage 2 for the kept candidates in parallel) within
 * {@code answerWithinMs − 300 ms} (else {@code hejje.jev.timeout}), and answers with decisions. Everything downstream
 * (backing strategy, sizing, risk, lockstep, reports) is the protocol's. A Jev failure answers NONE for entries and HOLD
 * for positions; stops and force exits still protect. Every stage's answers are recorded for calibration.
 */
@Component
class JevBotRunner {

    private static final Logger log = LoggerFactory.getLogger(JevBotRunner.class);
    static final long MARGIN_MS = 300;

    private final BotService bots;
    private final BotHub hub;
    private final JevService jev;
    private final JevQuestionSets sets;
    private final JevMarketState marketState;
    private final InstrumentService instruments;
    private final SignalEngine engine;
    private final CalibrationService calibration;
    private final HejjeClock clock;
    private final HejjeProperties properties;
    private final ObjectMapper json;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, AutoCloseable> connected = new ConcurrentHashMap<>();

    JevBotRunner(BotService bots, BotHub hub, JevService jev, JevQuestionSets sets, JevMarketState marketState, InstrumentService instruments,
            SignalEngine engine, CalibrationService calibration, HejjeClock clock, HejjeProperties properties, ObjectMapper json) {
        this.bots = bots;
        this.hub = hub;
        this.jev = jev;
        this.sets = sets;
        this.marketState = marketState;
        this.instruments = instruments;
        this.engine = engine;
        this.calibration = calibration;
        this.clock = clock;
        this.properties = properties;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        bots.onRegistered(this::connect);
        bots.list().forEach(this::connect);
    }

    void connect(Bot bot) {
        if (bot.kind() != Bot.Kind.JEV || connected.containsKey(bot.id())) {
            return;
        }
        connected.put(bot.id(), hub.connect(bot.id(), message -> calls.submit(() -> answer(bot, message))));
        log.info("Jev bot {} v{} running in-process", bot.name(), bot.version());
    }

    private void answer(Bot bot, Map<String, Object> message) {
        String pointId = String.valueOf(message.get("pointId"));
        BotDecision.Reply reply;
        try {
            reply = decide(bot, message);
        } catch (RuntimeException e) {
            log.warn("Jev bot {} failed at {}: {}", bot.name(), pointId, e.getMessage());
            reply = new BotDecision.Reply(pointId, List.of(none(bot.universe().get(0), "error: " + e.getMessage(), List.of())));
        }
        try {
            hub.answer(bot.id(), reply);
        } catch (RuntimeException e) {
            log.warn("Jev bot {} answer at {} failed: {}", bot.name(), pointId, e.getMessage());
        }
    }

    // --- one decision point ---

    BotDecision.Reply decide(Bot bot, Map<String, Object> message) {
        String pointId = String.valueOf(message.get("pointId"));
        long budgetMs = message.get("answerWithinMs") instanceof Number n ? n.longValue() - MARGIN_MS : jev.timeout().toMillis();
        long endNanos = System.nanoTime() + Duration.ofMillis(Math.max(100, budgetMs)).toNanos();
        Instant now = clock.now();
        Map<String, JevMarketState.Stock> stocks = marketState.stocks(bot.universe());
        Map<String, Instrument> universe = new LinkedHashMap<>();
        Map<String, JevBotState.Features> features = new LinkedHashMap<>();
        Map<String, List<Candle>> bars = new LinkedHashMap<>();
        stocks.forEach((symbol, stock) -> {
            universe.put(symbol, stock.instrument());
            features.put(symbol, stock.features());
            bars.put(symbol, stock.today());
        });
        ObjectNode index = marketState.index(stocks);

        // open positions: one call each, in parallel with stage 1
        JevQuestionSet positionSet = sets.get(bot.questionSet() + "-position");
        List<Future<BotDecision.Input>> positionCalls = new ArrayList<>();
        java.util.Set<String> held = new java.util.HashSet<>();
        for (StrategyPosition p : engine.positions(true, 200)) {
            if (!p.strategyId().equals(bot.strategyId()) || p.status() != PositionStatus.OPEN) {
                continue;
            }
            String symbol = instruments.findById(p.instrumentId()).map(i -> i.hejjeSymbol().format()).orElse(null);
            if (symbol == null || !features.containsKey(symbol)) {
                continue;
            }
            held.add(symbol);
            positionCalls.add(calls.submit(() -> position(bot, p, symbol, features.get(symbol), bars.get(symbol), index, positionSet, endNanos, now)));
        }

        // stage 1 over the symbols without a position
        List<String> free = features.keySet().stream().filter(s -> !held.contains(s)).toList();
        List<JevBotRules.Candidate> candidates = List.of();
        if (!free.isEmpty()) {
            JevQuestionSet stage1 = sets.get(bot.questionSet() + "-stage1");
            ObjectNode state = json.createObjectNode();
            ObjectNode stockBlocks = state.putObject("stocks");
            free.forEach(s -> {
                ObjectNode block = JevBotState.stock(features.get(s));
                ObjectNode book = JevBotState.book(features.get(s));
                if (book != null) {
                    block.set("book", book);
                }
                stockBlocks.set(s, block);
            });
            state.set("index", index);
            Map<String, JevQuestion> perSymbol = new LinkedHashMap<>();
            for (int i = 0; i < free.size(); i++) {
                String s = free.get(i);
                perSymbol.put("long_" + i, JevQuestion.noul(stage1.params().path("long-template").asText().replace("{symbol}", s)));
                perSymbol.put("short_" + i, JevQuestion.noul(stage1.params().path("short-template").asText().replace("{symbol}", s)));
            }
            JevQuestionSet asked = stage1.with(perSymbol);
            JevResult r = jev.evaluate("bot-stage1", bot.name(), state, asked, remaining(endNanos, 2));
            if (r.ok()) {
                candidates = JevBotRules.stage1(r, free, stage1.params());
                for (int i = 0; i < free.size(); i++) {
                    UUID id = universe.get(free.get(i)).id();
                    record(r, "long_" + i, "bot-stage1", asked.version(), r.noul("long_" + i, 0), id, LabelRule.DIRECTION, Side.BUY, null, now);
                    record(r, "short_" + i, "bot-stage1", asked.version(), r.noul("short_" + i, 0), id, LabelRule.DIRECTION, Side.SELL, null, now);
                }
            }
        }
        List<Object> candidateRows = candidates.stream().map(c -> (Object) Map.of("instrument", c.symbol(), "side", c.longSide() ? "long" : "short",
                "score", c.p())).toList();

        // stage 2 per candidate, in parallel
        JevQuestionSet stage2 = sets.get(bot.questionSet() + "-stage2");
        Map<String, Future<BotDecision.Input>> entryCalls = new LinkedHashMap<>();
        for (JevBotRules.Candidate c : candidates) {
            if (entryCalls.containsKey(c.symbol())) {
                continue; // one entry per symbol per point: the stronger side came first
            }
            entryCalls.put(c.symbol(), calls.submit(() -> entry(bot, c, universe.get(c.symbol()), features.get(c.symbol()), bars.get(c.symbol()), index,
                    stage2, endNanos, now, candidateRows)));
        }

        List<BotDecision.Input> out = new ArrayList<>();
        for (Future<BotDecision.Input> f : positionCalls) {
            out.add(get(f, endNanos, null));
        }
        for (Map.Entry<String, Future<BotDecision.Input>> f : entryCalls.entrySet()) {
            BotDecision.Input in = get(f.getValue(), endNanos, null);
            if (in != null && !in.action().equals("NONE")) {
                out.add(in);
            }
        }
        out.removeIf(java.util.Objects::isNull);
        if (out.stream().noneMatch(d -> d.candidates() != null)) {
            // candidates travel with a NONE decision when nothing was entered, so the harness shows them
            String first = candidates.isEmpty() ? bot.universe().get(0) : candidates.get(0).symbol();
            if (out.stream().noneMatch(d -> d.instrument().equals(first))) {
                out.add(none(first, candidates.isEmpty() ? "no stage-1 candidate" : "no candidate passed stage 2", candidateRows));
            }
        }
        return new BotDecision.Reply(pointId, out);
    }

    private BotDecision.Input entry(Bot bot, JevBotRules.Candidate c, Instrument instrument, JevBotState.Features f, List<Candle> today, ObjectNode index,
            JevQuestionSet set, long endNanos, Instant now, List<Object> candidateRows) {
        ObjectNode book = JevBotState.book(f);
        ObjectNode state = json.createObjectNode();
        state.put("symbol", c.symbol());
        state.put("side", c.longSide() ? "long" : "short");
        state.set("stock", JevBotState.stock(f));
        state.set("index", index);
        List<String> bookQuestions = new ArrayList<>();
        set.params().path("book-questions").forEach(q -> bookQuestions.add(q.asText()));
        JevQuestionSet asked = set;
        if (book != null) {
            state.set("book", book);
        } else {
            asked = set.without(bookQuestions);
        }
        JevResult r = jev.evaluate("bot-stage2", c.symbol(), state, asked, remaining(endNanos, 1));
        if (!r.ok()) {
            return none(c.symbol(), "Jev " + r.outcome(), candidateRows);
        }
        Map<String, Integer> levels = new LinkedHashMap<>();
        asked.questions().forEach((k, q) -> {
            if (q instanceof JevQuestion.Score s) {
                levels.put(k, s.levels().size());
            }
        });
        JevBotRules.Entry e = JevBotRules.stage2(r, c.longSide(), levels, asked.params());
        Side side = c.longSide() ? Side.BUY : Side.SELL;
        BigDecimal stop = marketState.stop(side, instrument, f.price(), bot.timeframe(), properties.mode());
        record(r, "setup", "bot-stage2", asked.version(), e.pSetup(), instrument.id(), LabelRule.ENTRY_1R, side, stop, now);
        Map<String, Object> scores = new LinkedHashMap<>(e.scores());
        scores.put("pSetup", e.pSetup());
        scores.put("composite", e.composite());
        if (!e.enter()) {
            return new BotDecision.Input(c.symbol(), "NONE", null, null, e.pSetup(), "stage 2: " + e.reason(), "stage2", scores, candidateRows);
        }
        return new BotDecision.Input(c.symbol(), c.longSide() ? "ENTER_LONG" : "ENTER_SHORT", stop, null, e.pSetup(),
                "jev " + e.setup() + " p=" + e.pSetup() + " composite " + e.composite(), "stage2", scores, candidateRows);
    }

    private BotDecision.Input position(Bot bot, StrategyPosition p, String symbol, JevBotState.Features f, List<Candle> today, ObjectNode index,
            JevQuestionSet set, long endNanos, Instant now) {
        boolean longSide = p.side() == Side.BUY;
        double best = f.price();
        for (Candle c : today) {
            if (!c.openTime().plus(Duration.ofMinutes(1)).isAfter(p.openedAt())) {
                continue;
            }
            best = longSide ? Math.max(best, c.high().doubleValue()) : Math.min(best, c.low().doubleValue());
        }
        JevBotState.PositionFacts facts = new JevBotState.PositionFacts(longSide, p.entryPrice().doubleValue(), p.stop().doubleValue(),
                p.initialStop().doubleValue(), f.price(), best, Duration.between(p.openedAt(), now).toMinutes());
        ObjectNode state = json.createObjectNode();
        state.put("symbol", symbol);
        state.set("position", JevBotState.position(facts));
        state.set("stock", JevBotState.stock(f));
        state.set("index", index);
        JevResult r = jev.evaluate("bot-position", symbol, state, set, remaining(endNanos, 1));
        if (!r.ok()) {
            return new BotDecision.Input(symbol, "HOLD", null, null, null, "Jev " + r.outcome() + ": hold (the stop protects)", "position", null, null);
        }
        record(r, "exit_now", "bot-position", set.version(), r.noul("exit_now", 0), p.instrumentId(), LabelRule.EXIT, p.side(), null, now);
        record(r, "take_profit", "bot-position", set.version(), r.noul("take_profit", 0), p.instrumentId(), LabelRule.EXIT, p.side(), null, now);
        JevBotRules.PositionCall call = JevBotRules.position(r, facts, set.params());
        return switch (call.action()) {
            case EXIT -> new BotDecision.Input(symbol, "EXIT", null, null, null, call.reason(), "position", null, null);
            case TAKE_PROFIT -> new BotDecision.Input(symbol, "TAKE_PROFIT", null, null, null, call.reason(), "position", null, null);
            case MOVE_STOP_TO_ENTRY -> new BotDecision.Input(symbol, "MOVE_STOP", p.entryPrice(), null, null, call.reason(), "position", null, null);
            case HOLD -> new BotDecision.Input(symbol, "HOLD", null, null, null, call.reason(), "position", null, null);
        };
    }

    // --- helpers ---

    private void record(JevResult r, String key, String purpose, String version, double p, UUID instrumentId, LabelRule rule, Side side, BigDecimal stop,
            Instant at) {
        if (r.callId() == null || r.answer(key).isEmpty() || (rule == LabelRule.ENTRY_1R && stop == null)) {
            return;
        }
        calibration.record(new Prediction("jev", r.callId().toString(), key, purpose, version, Math.max(0, Math.min(1, p)), instrumentId, rule, side, stop, at));
    }

    /** The time left, split over {@code parts} sequential calls still to come. */
    private static Duration remaining(long endNanos, int parts) {
        long left = Math.max(0, endNanos - System.nanoTime());
        return Duration.ofNanos(Math.max(Duration.ofMillis(50).toNanos(), left / Math.max(1, parts)));
    }

    private static <T> T get(Future<T> f, long endNanos, T fallback) {
        try {
            return f.get(Math.max(1, endNanos - System.nanoTime() + Duration.ofMillis(100).toNanos()), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return fallback;
        }
    }

    private static BotDecision.Input none(String symbol, String why, List<Object> candidates) {
        return new BotDecision.Input(symbol, "NONE", null, null, null, why, "stage1", null, candidates);
    }

}
