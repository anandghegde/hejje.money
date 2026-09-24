package money.hejje.harness;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import money.hejje.bots.Bot;
import money.hejje.bots.BotDecision;
import money.hejje.bots.BotDecisions;
import money.hejje.bots.BotHub;
import money.hejje.bots.BotService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.costs.CostFill;
import money.hejje.common.costs.CostModel;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.orders.OrderService;
import money.hejje.pulse.PulseService;
import money.hejje.regime.RegimeService;
import money.hejje.risk.RiskLimits;
import money.hejje.risk.RiskService;
import money.hejje.signals.PositionStatus;
import money.hejje.signals.SignalEngine;
import money.hejje.signals.StrategyPosition;
import money.hejje.sim.SimSession;
import money.hejje.sim.SimSessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The harness screen's data (plan M7.4): one snapshot of a bot at work — session and replay state (SIM), header, context,
 * stat tiles, equity curve, positions and working orders, the bot's candidates, trades with their thesis, decisions and a
 * log. Scoped to the bot's strategy (every strategy position when no bot is given) over the SIM session's days, or today
 * in PAPER and live modes. Everything is read through the guarded market reads, so nothing is later than the clock.
 */
@Service
public class HarnessService {

    private final ObjectProvider<SimSessionService> sim;
    private final BotService bots;
    private final BotHub hub;
    private final BotDecisions decisions;
    private final SignalEngine engine;
    private final OrderService orders;
    private final MarketService market;
    private final InstrumentService instruments;
    private final RiskService risk;
    private final RegimeService regime;
    private final PulseService pulse;
    private final BrokerAdapter broker;
    private final CostModel costs;
    private final HejjeClock clock;
    private final HejjeProperties properties;

    HarnessService(ObjectProvider<SimSessionService> sim, BotService bots, BotHub hub, BotDecisions decisions, SignalEngine engine, OrderService orders,
            MarketService market, InstrumentService instruments, RiskService risk, RegimeService regime, PulseService pulse, BrokerAdapter broker,
            CostModel costs, HejjeClock clock, HejjeProperties properties) {
        this.sim = sim;
        this.bots = bots;
        this.hub = hub;
        this.decisions = decisions;
        this.engine = engine;
        this.orders = orders;
        this.market = market;
        this.instruments = instruments;
        this.risk = risk;
        this.regime = regime;
        this.pulse = pulse;
        this.broker = broker;
        this.costs = costs;
        this.clock = clock;
        this.properties = properties;
    }

    /** One closed trade with its costs and outcome. */
    private record Closed(StrategyPosition p, String symbol, BigDecimal gross, BigDecimal cost, BigDecimal net, double r) {}

    /**
     * @param botId     the bot to show; null takes the session's first bot, else the only enabled bot, else every strategy
     * @param sessionId a SIM session (null: the active one, else the latest)
     */
    public Map<String, Object> snapshot(UUID botId, UUID sessionId) {
        ExecutionMode mode = properties.mode();
        Instant now = clock.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode.name());
        out.put("clock", now.toString());

        // session (SIM only) and the window the numbers cover
        SimSession session = null;
        SimSessionService sessions = sim.getIfAvailable();
        if (sessions != null) {
            session = (sessionId != null ? sessions.find(sessionId) : sessions.active().or(() -> sessions.list(1).stream().findFirst())).orElse(null);
        }
        Bot bot = bot(botId, session);
        Instant from;
        Instant to;
        Money capital;
        if (session != null) {
            LocalDate first = session.spec().dates().isEmpty() ? session.sessionDate() : session.spec().dates().stream().min(Comparator.naturalOrder()).get();
            from = first.atStartOfDay(clock.zone()).toInstant();
            to = now.plus(Duration.ofDays(1));
            capital = Money.ofRupees(session.spec().capitalRupees() == null ? 1_000_000 : session.spec().capitalRupees());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", session.id().toString());
            s.put("state", session.state().name());
            s.put("speed", session.speed().label());
            s.put("sessionDate", String.valueOf(session.sessionDate()));
            s.put("day", session.dayIndex() + 1);
            s.put("days", session.days());
            s.put("step", session.step());
            s.put("steps", SimSession.STEPS_PER_DAY);
            s.put("resultHash", session.resultHash());
            s.put("warnings", session.warnings());
            out.put("session", s);
        } else {
            from = clock.today().atStartOfDay(clock.zone()).toInstant();
            to = now.plus(Duration.ofDays(1));
            capital = safeCapital();
        }

        List<StrategyPosition> all = engine.positions(false, 2000).stream()
                .filter(p -> p.mode() == mode && (bot == null || p.strategyId().equals(bot.strategyId())))
                .filter(p -> p.openedAt() != null && !p.openedAt().isBefore(from) && p.openedAt().isBefore(to)).toList();
        Map<UUID, Instrument> instrumentCache = new HashMap<>();
        List<Closed> closed = new ArrayList<>();
        List<StrategyPosition> open = new ArrayList<>();
        for (StrategyPosition p : all) {
            if (p.status() == PositionStatus.CLOSED && p.exitPrice() != null && p.entryPrice() != null) {
                closed.add(closedTrade(p, instrument(instrumentCache, p.instrumentId())));
            } else if (p.status() == PositionStatus.OPEN || p.status() == PositionStatus.EXITING) {
                open.add(p);
            }
        }
        closed.sort(Comparator.comparing(c -> c.p().closedAt() == null ? Instant.EPOCH : c.p().closedAt()));

        // header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("bot", bot == null ? "all strategies" : bot.name() + " v" + bot.version());
        header.put("botKind", bot == null ? null : bot.kind().name());
        header.put("botEnabled", bot == null ? null : bot.enabled());
        header.put("fillSource", switch (mode) {
            case SIM -> "replay · paper fills";
            case PAPER -> "paper · live quotes";
            default -> broker.brokerCode();
        });
        Set<UUID> universe = bot == null ? Set.of() : bot.universe().stream().map(instruments::resolve).flatMap(Optional::stream).map(Instrument::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        int quotes = universe.isEmpty() ? 0 : market.quotes(universe).size();
        boolean fresh = universe.isEmpty() || market.quotes(universe).values().stream()
                .allMatch(q -> q.ts() != null && Duration.between(q.ts(), now).abs().compareTo(Duration.ofSeconds(90)) <= 0);
        header.put("dataHealth", (mode == ExecutionMode.SIM ? "HIST" : "LIVE") + " " + (fresh ? "OK" : "STALE") + " · " + universe.size() + " names · "
                + quotes + " quotes");
        if (bot != null) {
            BotHub.Stats stats = hub.stats(bot.id());
            header.put("latencyP50Ms", stats.latencyP50Ms());
            header.put("latencyP90Ms", stats.latencyP90Ms());
            header.put("skipped", stats.skipped());
            header.put("connected", stats.connected());
            header.put("botId", bot.id().toString());
        }
        RiskLimits limits = risk.limits(mode);
        header.put("killSwitch", risk.killSwitch(mode).stopNewOrders());
        out.put("header", header);

        // context
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("regime", safe(() -> {
            var r = regime.current();
            return r == null ? null : r.trend().name() + " × " + r.volatility().name() + " · " + r.opening().name();
        }));
        context.put("pulse", safe(() -> {
            var p = pulse.current();
            return p == null || p.technical() == null ? null : p.technical().direction().name() + " " + p.technical().strength().name() + " ("
                    + p.technical().score() + ")";
        }));
        Timeframe tf = bot == null ? Timeframe.M5 : bot.timeframe();
        long tfSeconds = tf.duration().toSeconds();
        long sinceOpen = Duration.between(clock.today().atTime(HejjeClock.SESSION_OPEN).atZone(clock.zone()).toInstant(), now).toSeconds();
        context.put("nextDecisionInSeconds", sinceOpen < 0 ? -sinceOpen + tfSeconds : tfSeconds - Math.floorMod(sinceOpen, tfSeconds));
        out.put("context", context);

        // tiles
        BigDecimal realized = closed.stream().map(Closed::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal realizedToday = closed.stream().filter(c -> c.p().closedAt() != null && c.p().closedAt().atZone(clock.zone()).toLocalDate().equals(clock.today()))
                .map(Closed::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openPnl = BigDecimal.ZERO;
        BigDecimal inUse = BigDecimal.ZERO;
        List<Map<String, Object>> positionRows = new ArrayList<>();
        List<Map<String, Object>> working = new ArrayList<>();
        for (StrategyPosition p : open) {
            Instrument i = instrument(instrumentCache, p.instrumentId());
            String symbol = i == null ? p.instrumentId().toString() : i.hejjeSymbol().format();
            BigDecimal entry = p.entryPrice() == null ? BigDecimal.ZERO : p.entryPrice();
            BigDecimal ltp = market.lastPrice(p.instrumentId()).orElse(entry);
            int sign = p.side() == Side.BUY ? 1 : -1;
            BigDecimal pnl = ltp.subtract(entry).multiply(BigDecimal.valueOf((long) sign * p.quantity()));
            openPnl = openPnl.add(pnl);
            BigDecimal notional = entry.multiply(BigDecimal.valueOf(p.quantity()));
            inUse = inUse.add(notional);
            BigDecimal riskPerUnit = entry.subtract(p.initialStop() == null ? entry : p.initialStop()).abs();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            row.put("side", p.side().name());
            row.put("quantity", p.quantity());
            row.put("entry", entry);
            row.put("ltp", ltp);
            row.put("stop", p.stop());
            row.put("stopLocation", p.stopOrderId() == null ? "software" : mode.simulated() ? "simulated" : "exchange");
            row.put("notional", notional.setScale(2, RoundingMode.HALF_UP));
            row.put("openPnl", pnl.setScale(2, RoundingMode.HALF_UP));
            row.put("r", riskPerUnit.signum() == 0 ? null : ltp.subtract(entry).multiply(BigDecimal.valueOf(sign)).divide(riskPerUnit, 2, RoundingMode.HALF_UP));
            row.put("thesis", thesis(p));
            List<Candle> since = p.openedAt() == null ? List.of() : market.candles(p.instrumentId(), Timeframe.M1, p.openedAt(), now);
            if (!since.isEmpty()) {
                BigDecimal hi = since.stream().map(Candle::high).max(Comparator.naturalOrder()).get();
                BigDecimal lo = since.stream().map(Candle::low).min(Comparator.naturalOrder()).get();
                row.put("mfe", (sign > 0 ? hi.subtract(entry) : entry.subtract(lo)).setScale(2, RoundingMode.HALF_UP));
                row.put("mae", (sign > 0 ? lo.subtract(entry) : entry.subtract(hi)).setScale(2, RoundingMode.HALF_UP));
            }
            positionRows.add(row);
            for (UUID orderId : new UUID[]{p.entryOrderId(), p.stopOrderId(), p.exitOrderId()}) {
                if (orderId != null) {
                    orders.findById(orderId).filter(o -> !o.state().isTerminal()).ifPresent(o -> {
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("symbol", symbol);
                        w.put("side", o.side().name());
                        w.put("type", o.orderType().name());
                        w.put("quantity", o.quantity());
                        w.put("trigger", o.triggerPrice());
                        w.put("limit", o.limitPrice());
                        w.put("state", o.state().name());
                        w.put("role", o.role() == null ? null : o.role().name());
                        working.add(w);
                    });
                }
            }
        }
        List<Closed> wins = closed.stream().filter(c -> c.net().signum() > 0).toList();
        List<Closed> losses = closed.stream().filter(c -> c.net().signum() <= 0).toList();
        BigDecimal winSum = wins.stream().map(Closed::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lossSum = losses.stream().map(Closed::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal friction = closed.stream().map(Closed::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> equity = new ArrayList<>();
        BigDecimal level = capital.toRupees();
        BigDecimal peak = level;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        equity.add(Map.of("t", from.toString(), "equity", level));
        for (Closed c : closed) {
            level = level.add(c.net());
            peak = peak.max(level);
            maxDrawdown = maxDrawdown.max(peak.subtract(level));
            equity.add(Map.of("t", String.valueOf(c.p().closedAt()), "equity", level.setScale(2, RoundingMode.HALF_UP)));
        }
        BigDecimal last = level.add(openPnl);
        maxDrawdown = maxDrawdown.max(peak.subtract(last));
        equity.add(Map.of("t", now.toString(), "equity", last.setScale(2, RoundingMode.HALF_UP)));

        Map<String, Object> tiles = new LinkedHashMap<>();
        tiles.put("dayPnl", money(realizedToday.add(openPnl)));
        tiles.put("openPnl", money(openPnl));
        tiles.put("totalPnl", money(realized.add(openPnl)));
        tiles.put("capital", money(capital.toRupees()));
        tiles.put("inUse", money(inUse));
        tiles.put("free", money(capital.toRupees().subtract(inUse)));
        tiles.put("riskPerTrade", money(limits.maxRiskPerTrade().toRupees()));
        tiles.put("trades", closed.size());
        tiles.put("hitRate", closed.isEmpty() ? null : round((double) wins.size() / closed.size(), 3));
        tiles.put("expectancyR", closed.isEmpty() ? null : round(closed.stream().mapToDouble(Closed::r).average().orElse(0), 3));
        tiles.put("profitFactor", lossSum.signum() == 0 ? null : round(winSum.doubleValue() / lossSum.abs().doubleValue(), 2));
        tiles.put("maxDrawdown", money(maxDrawdown));
        tiles.put("avgWin", wins.isEmpty() ? null : money(winSum.divide(BigDecimal.valueOf(wins.size()), 2, RoundingMode.HALF_UP)));
        tiles.put("avgLoss", losses.isEmpty() ? null : money(lossSum.divide(BigDecimal.valueOf(losses.size()), 2, RoundingMode.HALF_UP)));
        tiles.put("avgHoldMinutes", closed.isEmpty() ? null : Math.round(closed.stream().filter(c -> c.p().closedAt() != null)
                .mapToLong(c -> Duration.between(c.p().openedAt(), c.p().closedAt()).toMinutes()).average().orElse(0)));
        tiles.put("frictionPaid", money(friction));
        tiles.put("lossHalt", money(limits.maxLossPerDay().toRupees()));
        if (bot != null) {
            BotHub.Stats stats = hub.stats(bot.id());
            tiles.put("llmTokens", stats.llmTokens());
            tiles.put("llmCostRupees", money(stats.llmCostRupees()));
        }
        out.put("tiles", tiles);
        out.put("equity", equity);
        out.put("positions", positionRows);
        out.put("workingOrders", working);

        // decisions, candidates, trades, log
        List<BotDecision> recent = bot == null ? List.of() : decisions.recent(bot.id(), 200);
        String latestPoint = recent.stream().filter(d -> d.action() != BotDecision.Action.SKIPPED).map(BotDecision::pointId).findFirst().orElse(null);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (BotDecision d : recent) {
            if (!d.pointId().equals(latestPoint) || d.candidates() == null) {
                continue;
            }
            for (Object c : d.candidates()) {
                Map<String, Object> row = new LinkedHashMap<>();
                if (c instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> row.put(String.valueOf(k), v));
                } else {
                    row.put("instrument", String.valueOf(c));
                }
                row.put("sent", d.instrument().equals(row.get("instrument")) && d.action() != BotDecision.Action.NONE && d.action() != BotDecision.Action.HOLD);
                latestMicro(String.valueOf(row.get("instrument")), bot.timeframe()).ifPresent(m -> { // plan M9.4
                    row.put("imbalance", m.imbalanceClose());
                    row.put("flowShare", m.upVolumeShare());
                });
                candidates.add(row);
            }
        }
        out.put("candidates", candidates);
        List<Map<String, Object>> trades = new ArrayList<>();
        for (Closed c : closed) {
            StrategyPosition p = c.p();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", String.valueOf(p.closedAt()));
            row.put("leg", p.side() == Side.BUY ? "long" : "short");
            row.put("symbol", c.symbol());
            row.put("side", p.side().name());
            row.put("quantity", p.quantity());
            row.put("entry", p.entryPrice());
            row.put("exit", p.exitPrice());
            row.put("pnl", money(c.net()));
            row.put("r", round(c.r(), 3));
            row.put("holdMinutes", p.closedAt() == null ? null : Duration.between(p.openedAt(), p.closedAt()).toMinutes());
            row.put("why", thesis(p));
            row.put("exitReason", p.closeReason() == null ? null : p.closeReason().name());
            row.put("attribution", decisionOf(p).map(d -> d.id().toString().substring(0, 8) + " @ " + d.pointId()).orElse(null));
            trades.add(row);
        }
        java.util.Collections.reverse(trades);
        out.put("trades", trades);
        List<Map<String, Object>> decisionRows = new ArrayList<>();
        List<String> log = new ArrayList<>();
        for (BotDecision d : recent) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", d.pointId());
            row.put("stage", d.stage());
            row.put("symbol", d.instrument());
            row.put("action", d.action().name());
            row.put("scores", d.scores());
            row.put("confidence", d.confidence());
            row.put("latencyMs", d.latencyMs());
            row.put("outcome", d.outcome().name());
            decisionRows.add(row);
            if (d.action() != BotDecision.Action.NONE && d.action() != BotDecision.Action.HOLD) {
                log.add(d.decidedAt() + " " + d.action() + " " + d.instrument() + " → " + d.outcome() + (d.detail() == null ? "" : ": " + d.detail()));
            }
        }
        for (Closed c : closed) {
            log.add(c.p().closedAt() + " closed " + c.symbol() + " " + c.p().closeReason() + " net " + money(c.net()));
        }
        log.sort(Comparator.reverseOrder());
        out.put("decisions", decisionRows);
        out.put("log", log.subList(0, Math.min(log.size(), 50)));
        return out;
    }

    private Bot bot(UUID botId, SimSession session) {
        if (botId != null) {
            return bots.find(botId).orElseThrow(() -> new IllegalArgumentException("Unknown bot " + botId));
        }
        if (session != null && !session.spec().bots().isEmpty()) {
            Object id = session.spec().bots().get(0).get("botId");
            Optional<Bot> first = id == null ? Optional.empty() : bots.find(UUID.fromString(String.valueOf(id)));
            if (first.isPresent()) {
                return first.get();
            }
        }
        List<Bot> enabled = bots.list().stream().filter(Bot::enabled).toList();
        return enabled.size() == 1 ? enabled.get(0) : null;
    }

    private Closed closedTrade(StrategyPosition p, Instrument i) {
        int sign = p.side() == Side.BUY ? 1 : -1;
        BigDecimal gross = p.exitPrice().subtract(p.entryPrice()).multiply(BigDecimal.valueOf((long) sign * p.quantity()));
        BigDecimal cost = BigDecimal.ZERO;
        if (i != null) {
            Side exitSide = p.side() == Side.BUY ? Side.SELL : Side.BUY;
            cost = costs.compute(new CostFill(i.type(), Product.MIS, p.side(), p.quantity(), p.entryPrice())).total().toRupees()
                    .add(costs.compute(new CostFill(i.type(), Product.MIS, exitSide, p.quantity(), p.exitPrice())).total().toRupees());
        }
        BigDecimal net = gross.subtract(cost);
        BigDecimal risk = p.entryPrice().subtract(p.initialStop() == null ? p.entryPrice() : p.initialStop()).abs().multiply(BigDecimal.valueOf(p.quantity()));
        double r = risk.signum() == 0 ? 0 : net.doubleValue() / risk.doubleValue();
        return new Closed(p, i == null ? p.instrumentId().toString() : i.hejjeSymbol().format(), gross, cost, net, r);
    }

    private Optional<BotDecision> decisionOf(StrategyPosition p) {
        return p.signalId() == null ? Optional.empty() : decisions.bySignal(p.signalId());
    }

    private String thesis(StrategyPosition p) {
        return decisionOf(p).map(BotDecision::thesis).orElse(null);
    }

    private Instrument instrument(Map<UUID, Instrument> cache, UUID id) {
        return cache.computeIfAbsent(id, k -> instruments.findById(k).orElse(null));
    }

    private Money safeCapital() {
        try {
            return broker.getFunds().net();
        } catch (RuntimeException e) {
            return Money.ZERO;
        }
    }

    private static BigDecimal money(BigDecimal rupees) {
        return rupees == null ? null : rupees.setScale(2, RoundingMode.HALF_UP);
    }

    private static Double round(double v, int places) {
        return BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }

    private static Object safe(java.util.function.Supplier<Object> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The latest bar with order-book data of a symbol on the bot's timeframe in the last day, if any. */
    private java.util.Optional<money.hejje.market.BarMicro> latestMicro(String symbol, Timeframe timeframe) {
        return instruments.resolve(symbol).flatMap(i -> {
            Instant now = clock.now();
            List<money.hejje.market.BarMicro> m = market.micro(i.id(), timeframe, now.minus(Duration.ofDays(1)), now);
            return m.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(m.get(m.size() - 1));
        });
    }
}
