package money.hejje.agent.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.agent.AgentProperties;
import money.hejje.agent.Approval;
import money.hejje.agent.ApprovalKind;
import money.hejje.agent.Proposal;
import money.hejje.agent.ProposalSpec;
import money.hejje.agent.ToolException;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.time.HejjeClock;
import money.hejje.events.EventService;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.MarketService;
import money.hejje.orders.IntentStatus;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderReason;
import money.hejje.risk.PositionSizer;
import money.hejje.risk.RiskCheck;
import money.hejje.risk.RiskDecision;
import money.hejje.risk.RiskEngine;
import money.hejje.risk.policy.PolicyAction;
import money.hejje.risk.policy.PolicyDecision;
import money.hejje.risk.policy.PolicyEngine;
import money.hejje.risk.policy.PolicyRequest;
import money.hejje.risk.policy.PolicyResult;
import money.hejje.scoring.ScoreBreakdown;
import money.hejje.scoring.ScoringService;
import money.hejje.signals.PreparedOrder;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.springframework.stereotype.Component;

/**
 * Turns an agent's {@link ProposalSpec} into a sized order proposal: quantity from the deterministic sizer (the signal's
 * own dry run for signal proposals), a dry-run risk decision, and the policy decision with the deployment's autonomy
 * level, the instrument's event risk, the score and whether the version is LIVE. Also re-validates at approval time.
 */
@Component
public class OrderProposals {

    public record Prepared(Proposal proposal, OrderIntentCommand command, RiskDecision risk, PolicyResult policy, Signal signal) {}

    public record Revalidation(boolean ok, String reason, List<String> failures) {}

    private final SignalService signals;
    private final StrategyService strategies;
    private final ScoringService scoring;
    private final EventService events;
    private final MarketService market;
    private final InstrumentService instruments;
    private final RiskEngine riskEngine;
    private final PolicyEngine policies;
    private final AgentProperties props;
    private final HejjeClock clock;

    OrderProposals(SignalService signals, StrategyService strategies, ScoringService scoring, EventService events, MarketService market,
            InstrumentService instruments, RiskEngine riskEngine, PolicyEngine policies, AgentProperties props, HejjeClock clock) {
        this.signals = signals;
        this.strategies = strategies;
        this.scoring = scoring;
        this.events = events;
        this.market = market;
        this.instruments = instruments;
        this.riskEngine = riskEngine;
        this.policies = policies;
        this.props = props;
        this.clock = clock;
    }

    public ExecutionMode mode() {
        return signals.mode();
    }

    public Prepared prepare(ProposalSpec spec, HejjePrincipal principal) {
        return spec.signalId() != null ? fromSignal(UUID.fromString(spec.signalId()), principal) : manual(spec, principal);
    }

    private Prepared fromSignal(UUID signalId, HejjePrincipal principal) {
        return fromSignal(signalId, principal, null);
    }

    /**
     * A strategy signal the AUTO policy sent to a human (plan M5.2): the same sizing and dry-run risk as an agent's signal
     * proposal, with the AUTO policy result kept as the reason instead of an agent policy decision.
     */
    public Prepared forHeldSignal(UUID signalId, PolicyResult autoPolicy) {
        return fromSignal(signalId, null, autoPolicy);
    }

    private Prepared fromSignal(UUID signalId, HejjePrincipal principal, PolicyResult autoPolicy) {
        Signal signal = signals.find(signalId).orElseThrow(() -> ToolException.notFound("Unknown signal " + signalId));
        if (signal.mode() != mode()) {
            throw ToolException.conflict("Signal belongs to mode " + signal.mode() + "; the server runs in " + mode());
        }
        if (signal.isExpiredAt(clock.now())) {
            throw ToolException.conflict("Signal expired at " + signal.validUntil());
        }
        if (!signal.status().isActionable()) {
            throw ToolException.conflict("Signal is " + signal.status());
        }
        PreparedOrder p = signals.dryRun(signal, principal);
        OrderIntentCommand c = autoPolicy != null ? p.proposal() : agentCommand(p.proposal(), principal);
        int qty = p.sizing().get("quantity") instanceof Integer q ? q : 0;
        StrategyVersion version = strategies.versionById(signal.versionId()).orElse(null);
        Strategy strategy = strategies.find(signal.strategyId()).orElse(null);
        Integer autonomy = signals.deploymentOf(signal).map(StrategyDeployment::autonomyLevel).orElse(props.approvals().accountAutonomyLevel());
        String eventRisk = eventRisk(signal.instrumentId());
        Integer score = scoring.latest(signal.versionId(), signal.instrumentId()).map(ScoreBreakdown::finalScore).orElse(null);
        boolean newVersion = version != null && version.status() != VersionStatus.LIVE;
        PolicyResult policy = autoPolicy != null ? autoPolicy : policies.decide(new PolicyRequest(PolicyAction.ORDER_NEW, ActorType.AGENT, mode(), autonomy,
                eventRisk, score, newVersion, signal.strategyId(), signal.instrumentId()));
        BigDecimal entry = new BigDecimal(String.valueOf(p.sizing().get("entryReference")));
        BigDecimal riskRupees = new BigDecimal(String.valueOf(p.sizing().get("riskRupees")));
        String symbol = symbol(signal.instrumentId());
        String label = strategy == null ? null : strategy.slug() + (version == null ? "" : " v" + version.version());
        Proposal proposal = proposal(signal.id(), signal.strategyId(), label, signal.versionId(), signal.instrumentId(), symbol, c, qty, entry, riskRupees, autonomy,
                eventRisk, score, newVersion, p.risk(), policy, p.notes());
        return new Prepared(proposal, c, p.risk(), policy, signal);
    }

    private Prepared manual(ProposalSpec s, HejjePrincipal principal) {
        if (s.instrument() == null || s.side() == null || s.riskRupees() == null || s.stop() == null) {
            throw ToolException.invalid("Give signalId, or instrument, side, riskRupees and stop (entry defaults to the last price)");
        }
        Instrument instrument = instrument(s.instrument());
        String symbol = instrument.hejjeSymbol().format();
        Side side = Side.valueOf(s.side());
        BigDecimal entry = s.entry() != null ? s.entry() : market.lastPrice(instrument.id()).filter(p -> p.signum() > 0)
                .orElseThrow(() -> ToolException.invalid("No last price for " + symbol + "; give entry"));
        List<String> notes = new ArrayList<>();
        boolean stopValid = side == Side.BUY ? s.stop().compareTo(entry) < 0 : s.stop().compareTo(entry) > 0;
        int qty = 0;
        if (!stopValid) {
            notes.add("the stop " + s.stop().toPlainString() + " is not on the losing side of the entry " + entry.toPlainString());
        } else {
            qty = PositionSizer.size(price(entry), price(s.stop()), Money.of(s.riskRupees()), instrument.lotSize(), 0);
            if (qty <= 0) {
                notes.add("risk of " + s.riskRupees().toPlainString() + " rupees is below one lot (" + instrument.lotSize() + ")");
            }
        }
        Strategy strategy = s.strategy() == null ? null : strategy(s.strategy());
        Optional<StrategyDeployment> deployment = strategy == null ? Optional.empty() : deploymentOf(strategy.id());
        Integer autonomy = strategy == null ? Integer.valueOf(props.approvals().accountAutonomyLevel())
                : deployment.map(StrategyDeployment::autonomyLevel).orElse(0);
        if (strategy != null && deployment.isEmpty()) {
            notes.add(strategy.slug() + " has no enabled deployment in " + mode() + ", so its autonomy level is 0");
        }
        boolean newVersion = deployment.flatMap(d -> strategies.versionById(d.versionId())).map(v -> v.status() != VersionStatus.LIVE).orElse(false);
        String eventRisk = eventRisk(instrument.id());
        Integer score = deployment.flatMap(d -> scoring.latest(d.versionId(), instrument.id())).map(ScoreBreakdown::finalScore).orElse(null);
        BigDecimal perUnit = entry.subtract(s.stop()).abs();
        Money maxRisk = Money.of(perUnit.multiply(BigDecimal.valueOf(Math.max(qty, 0))).setScale(2, RoundingMode.HALF_UP));
        Product product = s.product() == null ? Product.MIS : Product.valueOf(s.product());
        OrderIntentCommand c = new OrderIntentCommand(principal.id(), "proposal:" + Ids.newId(), ActorType.AGENT, principal.name(),
                strategy == null ? null : strategy.id(), null, instrument.id(), side, Quantity.of(Math.max(qty, 1)), OrderType.MARKET, product, null, null,
                price(s.stop()), s.target() == null ? null : price(s.target()), maxRisk, OrderReason.AGENT_PROPOSAL);
        RiskDecision risk = qty <= 0 ? RiskDecision.rejected(List.of(new RiskCheck("positionSize", false, "0", ">= 1 lot", notes.get(notes.size() - 1))))
                : riskEngine.evaluate(toIntent(c));
        PolicyResult policy = policies.decide(new PolicyRequest(PolicyAction.ORDER_NEW, ActorType.AGENT, mode(), autonomy, eventRisk, score, newVersion,
                strategy == null ? null : strategy.id(), instrument.id()));
        Proposal proposal = proposal(null, strategy == null ? null : strategy.id(), strategy == null ? null : strategy.slug(),
                deployment.map(StrategyDeployment::versionId).orElse(null), instrument.id(), symbol, c, qty, entry, s.riskRupees(), autonomy, eventRisk, score,
                newVersion, risk, policy, notes);
        return new Prepared(proposal, c, risk, policy, null);
    }

    /** Policy (fresh context) and, for new orders, risk on the proposed order as stored. */
    public Revalidation revalidate(Approval a) {
        PolicyAction action = switch (a.kind()) {
            case ORDER_NEW -> PolicyAction.ORDER_NEW;
            case ORDER_MODIFY -> PolicyAction.ORDER_MODIFY;
            case ORDER_CANCEL -> PolicyAction.ORDER_CANCEL;
            case POSITION_CLOSE -> PolicyAction.POSITION_CLOSE;
            case BASKET_NEW -> PolicyAction.ORDER_NEW; // legs are risk-checked one by one in the pipeline
        };
        Integer autonomy = autonomyFor(a);
        Integer score = a.proposal().hasNonNull("score") ? a.proposal().get("score").asInt() : null;
        PolicyResult policy = policies.decide(new PolicyRequest(action, ActorType.AGENT, ExecutionMode.valueOf(a.mode()), autonomy,
                a.instrumentId() == null ? null : eventRisk(a.instrumentId()), score, a.proposal().path("newStrategyVersion").asBoolean(false), a.strategyId(),
                a.instrumentId()));
        if (policy.decision() == PolicyDecision.DENY) {
            return new Revalidation(false, "Policy denies: " + policy.reason(), List.of(policy.reason()));
        }
        if (a.kind() != ApprovalKind.ORDER_NEW) {
            return new Revalidation(true, null, List.of());
        }
        RiskDecision risk = riskEngine.evaluate(toIntent(command(a, a.requestedByPrincipal(), "revalidate:" + a.id(), ActorType.AGENT, a.requestedBy())));
        if (!risk.isApproved()) {
            return new Revalidation(false, "Risk rejects: " + String.join("; ", risk.failures()), risk.failures());
        }
        return new Revalidation(true, null, List.of());
    }

    /** The executable command of an ORDER_NEW approval, rebuilt from the stored proposal. */
    public OrderIntentCommand command(Approval a, UUID clientId, String idempotencyKey, ActorType source, String actorId) {
        var p = a.proposal();
        return new OrderIntentCommand(clientId, idempotencyKey, source, actorId, a.strategyId(), a.signalId(), a.instrumentId(), Side.valueOf(p.path("side").asText()),
                Quantity.of(p.path("quantity").asInt()), OrderType.valueOf(p.path("orderType").asText()), Product.valueOf(p.path("product").asText()), null, null,
                p.hasNonNull("stop") ? price(p.get("stop").decimalValue()) : null, p.hasNonNull("target") ? price(p.get("target").decimalValue()) : null,
                p.hasNonNull("maxRisk") ? Money.of(p.get("maxRisk").decimalValue().setScale(2, RoundingMode.HALF_UP)) : null, OrderReason.AGENT_PROPOSAL);
    }

    public String eventRisk(UUID instrumentId) {
        try {
            var risk = events.risk(instrumentId);
            return risk.level() == null ? null : risk.level().name();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Integer autonomyFor(Approval a) {
        if (a.signalId() != null) {
            return signals.find(a.signalId()).flatMap(signals::deploymentOf).map(StrategyDeployment::autonomyLevel)
                    .orElse(props.approvals().accountAutonomyLevel());
        }
        if (a.strategyId() != null) {
            return deploymentOf(a.strategyId()).map(StrategyDeployment::autonomyLevel).orElse(0);
        }
        return props.approvals().accountAutonomyLevel();
    }

    private Optional<StrategyDeployment> deploymentOf(UUID strategyId) {
        return strategies.deployments(null, mode(), true).stream().filter(d -> d.strategyId().equals(strategyId))
                .max(Comparator.comparingInt(StrategyDeployment::autonomyLevel));
    }

    private Proposal proposal(UUID signalId, UUID strategyId, String strategy, UUID versionId, UUID instrumentId, String symbol, OrderIntentCommand c, int qty,
            BigDecimal entry, BigDecimal riskRupees, Integer autonomy, String eventRisk, Integer score, boolean newVersion, RiskDecision risk, PolicyResult policy,
            List<String> notes) {
        List<Proposal.RiskCheckView> checks = risk.checks().stream()
                .map(k -> new Proposal.RiskCheckView(k.name(), k.passed(), k.observed(), k.limit(), k.message())).toList();
        String summary = c.side() + " " + qty + " " + symbol + " " + c.orderType() + " " + c.product() + " · entry ≈ " + entry.toPlainString()
                + (c.stopPrice() == null ? "" : " · stop " + c.stopPrice().value().toPlainString())
                + (c.targetPrice() == null ? "" : " · target " + c.targetPrice().value().toPlainString())
                + (c.maxRisk() == null ? "" : " · risk ≈ " + c.maxRisk().toRupeesString() + " rupees") + (strategy == null ? "" : " · " + strategy)
                + (signalId == null ? "" : " signal");
        return new Proposal("ORDER_NEW", signalId, strategyId, strategy, versionId, instrumentId, symbol, c.side().name(), qty, c.orderType().name(),
                c.product().name(), entry, c.stopPrice() == null ? null : c.stopPrice().value(), c.targetPrice() == null ? null : c.targetPrice().value(), riskRupees,
                c.maxRisk() == null ? null : c.maxRisk().toRupees(), autonomy, eventRisk, score, newVersion, risk.outcome().name(), checks,
                policy.decision().name(), policy.rule(), policy.reason(), notes, summary);
    }

    private OrderIntentCommand agentCommand(OrderIntentCommand c, HejjePrincipal principal) {
        return new OrderIntentCommand(principal.id(), c.idempotencyKey(), ActorType.AGENT, principal.name(), c.strategyId(), c.signalId(), c.instrumentId(), c.side(),
                c.quantity(), c.orderType(), c.product(), c.limitPrice(), c.triggerPrice(), c.stopPrice(), c.targetPrice(), c.maxRisk(), OrderReason.AGENT_PROPOSAL);
    }

    private OrderIntent toIntent(OrderIntentCommand c) {
        return new OrderIntent(Ids.newId(), c.idempotencyKey(), c.clientId(), c.source(), c.actorId(), c.strategyId(), c.signalId(), c.instrumentId(), c.side(),
                c.quantity(), c.orderType(), c.product(), c.limitPrice(), c.triggerPrice(), c.stopPrice(), c.targetPrice(), c.maxRisk(), c.reason(), mode(),
                IntentStatus.CREATED, List.of(), clock.now());
    }

    private Instrument instrument(String ref) {
        String r = ref.trim();
        try {
            return instruments.findById(UUID.fromString(r)).orElseThrow(() -> ToolException.notFound("Unknown instrument " + r));
        } catch (IllegalArgumentException notAnId) {
            return instruments.resolve(r).orElseThrow(() -> ToolException.notFound("Unknown instrument " + r + " (use a Hejje symbol such as NSE:RELIANCE)"));
        }
    }

    private Strategy strategy(String ref) {
        String r = ref.trim();
        try {
            return strategies.find(UUID.fromString(r)).orElseThrow(() -> ToolException.notFound("Unknown strategy " + r));
        } catch (IllegalArgumentException notAnId) {
            return strategies.findBySlug(r).orElseThrow(() -> ToolException.notFound("Unknown strategy " + r));
        }
    }

    public String symbol(UUID instrumentId) {
        return instruments.findById(instrumentId).map(i -> i.hejjeSymbol().format()).orElse(instrumentId.toString());
    }

    private static Price price(BigDecimal value) {
        return Price.of(value.setScale(2, RoundingMode.HALF_UP));
    }
}
