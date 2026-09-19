package money.hejje.bots;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.agent.Approval;
import money.hejje.agent.ApprovalService;
import money.hejje.auto.AutoDecision;
import money.hejje.auto.AutoExecutor;
import money.hejje.bots.internal.BotStore;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.MarketService;
import money.hejje.orders.HejjeOrder;
import money.hejje.risk.RiskService;
import money.hejje.risk.policy.PolicyAction;
import money.hejje.risk.policy.PolicyEngine;
import money.hejje.risk.policy.PolicyRequest;
import money.hejje.risk.policy.PolicyResult;
import money.hejje.signals.CloseReason;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalEngine;
import money.hejje.signals.SignalService;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What Hejje does with a bot's decisions (plan M7.3). Every decision is recorded once per (bot, decision point,
 * instrument): a repeat returns the recorded outcome. Entries need a stop on the losing side of the current price and
 * become signals of the bot's deployment on the instrument (Hejje sizes them from the risk per trade); SIM and PAPER
 * execute them at once through risk, the kill switch and the gate, CONFIRM asks for an approval, AUTO decides as for any
 * strategy (autonomy 4-5 and eligibility, else an approval). EXIT and TAKE_PROFIT flatten the deployment's position, MOVE_STOP
 * only tightens it, HOLD and NONE are noted.
 */
@Service
public class BotDecisions {

    private static final Logger log = LoggerFactory.getLogger(BotDecisions.class);
    static final Duration SIGNAL_VALIDITY = Duration.ofMinutes(5);

    private final BotStore store;
    private final StrategyService strategies;
    private final SignalService signals;
    private final SignalEngine engine;
    private final ApprovalService approvals;
    private final AutoExecutor auto;
    private final PolicyEngine policies;
    private final RiskService risk;
    private final MarketService market;
    private final InstrumentService instruments;
    private final HejjeProperties properties;
    private final HejjeClock clock;

    BotDecisions(BotStore store, StrategyService strategies, SignalService signals, SignalEngine engine, ApprovalService approvals, AutoExecutor auto,
            PolicyEngine policies, RiskService risk, MarketService market, InstrumentService instruments, HejjeProperties properties, HejjeClock clock) {
        this.store = store;
        this.strategies = strategies;
        this.signals = signals;
        this.engine = engine;
        this.approvals = approvals;
        this.auto = auto;
        this.policies = policies;
        this.risk = risk;
        this.market = market;
        this.instruments = instruments;
        this.properties = properties;
        this.clock = clock;
    }

    /** How an entry executes in a mode: at once (SIM, PAPER), through AUTO, or as an approval (CONFIRM). */
    public enum Route { EXECUTE, AUTO, APPROVAL }

    public static Route route(ExecutionMode mode) {
        return switch (mode) {
            case SIM, PAPER -> Route.EXECUTE;
            case AUTO -> Route.AUTO;
            case CONFIRM -> Route.APPROVAL;
        };
    }

    /** Applies a bot's answer to a decision point; returns the recorded decisions in the order given. */
    public List<BotDecision> apply(Bot bot, BotDecision.Reply reply, Long latencyMs) {
        if (reply.pointId() == null || reply.pointId().isBlank()) {
            throw new IllegalArgumentException("pointId is required");
        }
        List<BotDecision> out = new ArrayList<>();
        for (BotDecision.Input in : reply.decisions()) {
            out.add(applyOne(bot, reply.pointId(), in, latencyMs));
        }
        return out;
    }

    /** Records a decision point the bot did not answer in time. */
    public BotDecision skipped(Bot bot, String pointId, long latencyMs, String detail) {
        BotDecision d = new BotDecision(UUID.randomUUID(), bot.id(), pointId, "*", BotDecision.Action.SKIPPED, null, null, null, null, null, null, null,
                latencyMs, BotDecision.Outcome.SKIPPED, detail, null, null, properties.mode(), clock.now());
        return store.insert(d) ? d : store.find(bot.id(), pointId, "*").orElse(d);
    }

    private BotDecision applyOne(Bot bot, String pointId, BotDecision.Input in, Long latencyMs) {
        String symbol = in.instrument() == null ? "" : in.instrument().trim();
        BotDecision.Action action;
        try {
            action = BotDecision.Action.valueOf(in.action() == null ? "" : in.action().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            action = null;
        }
        BotDecision recorded = new BotDecision(UUID.randomUUID(), bot.id(), pointId, symbol.isEmpty() ? "*" : symbol,
                action == null || action == BotDecision.Action.SKIPPED ? BotDecision.Action.NONE : action, in.stop(), in.target(), in.confidence(), in.thesis(),
                in.stage(), in.scores(), in.candidates(), latencyMs, BotDecision.Outcome.NOTED, null, null, null, properties.mode(), clock.now());
        if (!store.insert(recorded)) {
            return store.find(bot.id(), pointId, recorded.instrument()).orElse(recorded); // idempotent per decision point
        }
        BotDecision result;
        try {
            if (action == null || action == BotDecision.Action.SKIPPED) {
                result = refuse(recorded, "action must be one of ENTER_LONG, ENTER_SHORT, EXIT, TAKE_PROFIT, MOVE_STOP, HOLD, NONE");
            } else if (action == BotDecision.Action.HOLD || action == BotDecision.Action.NONE) {
                result = recorded;
            } else {
                result = act(bot, recorded, action);
            }
        } catch (RuntimeException e) {
            log.debug("Bot {} decision refused: {}", bot.name(), e.getMessage());
            result = refuse(recorded, e.getMessage());
        }
        if (result != recorded) {
            store.update(result);
        }
        return result;
    }

    private BotDecision act(Bot bot, BotDecision d, BotDecision.Action action) {
        if (!bot.enabled()) {
            return refuse(d, "the bot is disabled");
        }
        if (!bot.allowedModes().contains(properties.mode())) {
            return refuse(d, "the bot is not allowed in " + properties.mode());
        }
        if (d.confidence() != null && (d.confidence() < 0 || d.confidence() > 1)) {
            return refuse(d, "confidence must be between 0 and 1");
        }
        Instrument instrument = instruments.resolve(d.instrument()).orElse(null);
        if (instrument == null) {
            return refuse(d, "unknown instrument " + d.instrument());
        }
        Optional<StrategyDeployment> deployment = strategies.deployments(null, properties.mode(), true).stream()
                .filter(x -> x.strategyId().equals(bot.strategyId()) && x.pausedAt() == null && x.instrumentIds().contains(instrument.id())).findFirst();
        if (deployment.isEmpty()) {
            return refuse(d, "the bot's strategy has no enabled " + properties.mode() + " deployment on " + d.instrument());
        }
        StrategyDeployment dep = deployment.get();
        return switch (action) {
            case ENTER_LONG, ENTER_SHORT -> enter(bot, d, dep, instrument, action == BotDecision.Action.ENTER_LONG ? Side.BUY : Side.SELL);
            case EXIT, TAKE_PROFIT -> engine.exitPosition(dep.id(), instrument.id(), action == BotDecision.Action.EXIT ? CloseReason.MANUAL : CloseReason.TARGET)
                    ? with(d, BotDecision.Outcome.EXITING, "closing at market", null, null) : refuse(d, "no open position on " + d.instrument());
            case MOVE_STOP -> {
                if (d.stop() == null) {
                    yield refuse(d, "MOVE_STOP needs the new stop");
                }
                yield engine.tightenStop(dep.id(), instrument.id(), d.stop()) ? with(d, BotDecision.Outcome.MOVED, "stop moved to " + d.stop().toPlainString(), null, null)
                        : refuse(d, "no open position, or " + d.stop().toPlainString() + " would loosen the stop (stops only tighten)");
            }
            default -> d;
        };
    }

    private BotDecision enter(Bot bot, BotDecision d, StrategyDeployment dep, Instrument instrument, Side side) {
        if (d.stop() == null) {
            return refuse(d, "an entry needs a stop");
        }
        if (risk.killSwitch(properties.mode()).stopNewOrders()) {
            return refuse(d, "kill switch STOP_NEW_ORDERS is active");
        }
        if (engine.position(dep.id(), instrument.id()).isPresent()) {
            return refuse(d, "the bot already has a position on " + d.instrument());
        }
        BigDecimal entry = market.lastPrice(instrument.id()).filter(p -> p.signum() > 0).orElse(null);
        if (entry == null) {
            return refuse(d, "no price for " + d.instrument());
        }
        boolean stopOk = side == Side.BUY ? d.stop().compareTo(entry) < 0 : d.stop().compareTo(entry) > 0;
        if (!stopOk) {
            return refuse(d, "the stop " + d.stop().toPlainString() + " is not on the losing side of " + entry.toPlainString());
        }
        if (d.target() != null && (side == Side.BUY ? d.target().compareTo(entry) <= 0 : d.target().compareTo(entry) >= 0)) {
            return refuse(d, "the target " + d.target().toPlainString() + " is not on the winning side of " + entry.toPlainString());
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "bot");
        evidence.put("botId", bot.id().toString());
        evidence.put("bot", bot.name() + " v" + bot.version());
        evidence.put("decisionId", d.id().toString());
        evidence.put("pointId", d.pointId());
        if (d.thesis() != null) {
            evidence.put("thesis", d.thesis());
        }
        if (d.confidence() != null) {
            evidence.put("confidence", d.confidence());
        }
        Signal s = signals.createExternal(dep, instrument.id(), side, entry, d.stop(), d.target(), SIGNAL_VALIDITY, "bot:" + bot.name(), evidence);
        return switch (route(properties.mode())) {
            case EXECUTE -> {
                Map<String, Object> context = new LinkedHashMap<>(evidence);
                context.put("rule", "bot_" + properties.mode().name().toLowerCase(Locale.ROOT));
                HejjeOrder order = signals.executeAuto(s.id(), "bot:" + bot.name(), context);
                yield with(d, BotDecision.Outcome.EXECUTED, "order " + order.id() + " " + order.state(), s.id(), order.id());
            }
            case AUTO -> {
                AutoDecision a = auto.onSignal(s.id());
                yield switch (a.outcome()) {
                    case EXECUTED -> with(d, BotDecision.Outcome.EXECUTED, a.reason(), s.id(), a.orderId());
                    case HELD -> with(d, BotDecision.Outcome.APPROVAL, a.reason(), s.id(), null);
                    case SKIPPED -> approval(bot, d, s, dep);
                    default -> with(d, BotDecision.Outcome.REFUSED, a.reason(), s.id(), null);
                };
            }
            case APPROVAL -> approval(bot, d, s, dep);
        };
    }

    private BotDecision approval(Bot bot, BotDecision d, Signal s, StrategyDeployment dep) {
        PolicyResult policy = policies.decide(new PolicyRequest(PolicyAction.ORDER_NEW, ActorType.WEBHOOK, properties.mode(), dep.autonomyLevel(), null, null,
                false, dep.strategyId(), s.instrumentId()));
        Optional<Approval> a = approvals.proposeHeldSignal(s.id(), "bot:" + bot.name(), "BOT", policy,
                "Bot " + bot.name() + ": " + (d.thesis() == null ? d.action().name() : d.thesis()));
        return a.map(x -> with(d, BotDecision.Outcome.APPROVAL, "approval " + x.id() + " waits in the inbox", s.id(), null))
                .orElseGet(() -> with(d, BotDecision.Outcome.REFUSED, "no approval: it cannot be sized or risk would reject it", s.id(), null));
    }

    private static BotDecision refuse(BotDecision d, String why) {
        return with(d, BotDecision.Outcome.REFUSED, why, d.signalId(), d.orderId());
    }

    private static BotDecision with(BotDecision d, BotDecision.Outcome outcome, String detail, UUID signalId, UUID orderId) {
        return new BotDecision(d.id(), d.botId(), d.pointId(), d.instrument(), d.action(), d.stop(), d.target(), d.confidence(), d.thesis(), d.stage(), d.scores(),
                d.candidates(), d.latencyMs(), outcome, detail, signalId, orderId, d.mode(), d.decidedAt());
    }

    /**
     * A STRATEGY bot's decision in SIM (plan M7.3): its deployment's signal, recorded as the bot's entry (the rules' evidence
     * as the thesis) and executed like any bot entry, so strategies and bots compete on the same sessions.
     */
    public BotDecision strategySignal(Bot bot, Signal s) {
        String symbol = instruments.findById(s.instrumentId()).map(i -> i.hejjeSymbol().format()).orElse(s.instrumentId().toString());
        BotDecision d = new BotDecision(UUID.randomUUID(), bot.id(), s.barTime().toString(), symbol,
                s.side() == Side.BUY ? BotDecision.Action.ENTER_LONG : BotDecision.Action.ENTER_SHORT, s.stop(), s.target(), null,
                "strategy rules: " + s.evidence().stream().map(e -> String.valueOf(e.get("condition"))).toList(), null, null, null, 0L,
                BotDecision.Outcome.NOTED, null, s.id(), null, properties.mode(), clock.now());
        if (!store.insert(d)) {
            return store.find(bot.id(), d.pointId(), symbol).orElse(d);
        }
        BotDecision result;
        try {
            if (risk.killSwitch(properties.mode()).stopNewOrders()) {
                result = refuse(d, "kill switch STOP_NEW_ORDERS is active");
            } else {
                HejjeOrder order = signals.executeAuto(s.id(), "bot:" + bot.name(), Map.of("rule", "bot_strategy", "botId", bot.id().toString()));
                result = with(d, BotDecision.Outcome.EXECUTED, "order " + order.id() + " " + order.state(), s.id(), order.id());
            }
        } catch (RuntimeException e) {
            result = refuse(d, e.getMessage());
        }
        store.update(result);
        return result;
    }

    public List<BotDecision> recent(UUID botId, int limit) {
        return store.decisions(botId, Math.max(1, Math.min(limit, 500)));
    }

    /** The decision behind a signal, for trade attribution. */
    public Optional<BotDecision> bySignal(UUID signalId) {
        return store.bySignal(signalId);
    }
}
