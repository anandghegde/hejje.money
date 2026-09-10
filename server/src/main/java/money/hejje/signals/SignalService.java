package money.hejje.signals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Quantity;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.IntentStatus;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderReason;
import money.hejje.risk.PositionSizer;
import money.hejje.risk.RiskDecision;
import money.hejje.risk.RiskEngine;
import money.hejje.signals.internal.SignalStore;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.springframework.stereotype.Service;

/** Public API of the signals module: list, prepare (dry-run), execute (the user's confirmation), skip. */
@Service
public class SignalService {

    private final money.hejje.options.OptionsExecutor options;

    /** Client id of AUTO submissions (plan M5.2). */
    public static final UUID AUTO_CLIENT = UUID.nameUUIDFromBytes("hejje:auto".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final SignalStore store;
    private final SignalEngine engine;
    private final StrategyService strategies;
    private final InstrumentService instruments;
    private final RiskEngine risk;
    private final ExecutionEngine execution;
    private final AuditService audit;
    private final HejjeProperties properties;
    private final HejjeClock clock;
    private final money.hejje.market.MarketService market;

    SignalService(SignalStore store, SignalEngine engine, StrategyService strategies, InstrumentService instruments, RiskEngine risk, ExecutionEngine execution,
            AuditService audit, HejjeProperties properties, HejjeClock clock, money.hejje.market.MarketService market, money.hejje.options.OptionsExecutor options) {
        this.options = options;
        this.market = market;
        this.store = store;
        this.engine = engine;
        this.strategies = strategies;
        this.instruments = instruments;
        this.risk = risk;
        this.execution = execution;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Signal> list(SignalStatus status, Instant from, int limit) {
        return store.query(properties.mode(), status, from, Math.max(1, Math.min(limit, 500)));
    }

    public Optional<Signal> find(UUID id) {
        return store.find(id);
    }

    /** ACTIVE/PREPARED signals that have not expired (expiring stale ones on the way). */
    public List<Signal> active() {
        List<Signal> out = new ArrayList<>();
        Instant now = clock.now();
        for (Signal s : store.actionable(properties.mode())) {
            if (s.isExpiredAt(now)) {
                expire(s, "validity window passed");
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** Marks ACTIVE/PREPARED signals past their validity as EXPIRED. Returns the count. */
    public int expireStale() {
        int n = 0;
        Instant now = clock.now();
        for (Signal s : store.actionable(properties.mode())) {
            if (s.isExpiredAt(now)) {
                expire(s, "validity window passed");
                n++;
            }
        }
        return n;
    }

    private void expire(Signal s, String note) {
        store.update(s.with(SignalStatus.EXPIRED, note, null, null, clock.now()));
        audit.record(AuditEvent.of(AuditEventType.SIGNAL_EXPIRED, ActorType.SYSTEM).withStrategyId(s.strategyId()).withSignalId(s.id()).withPayload(Map.of("note", note)));
        engine.signalGone(s);
    }

    /** Sizes the order and runs the risk checks without submitting anything; marks the signal PREPARED. */
    public PreparedOrder prepare(UUID signalId, HejjePrincipal principal) {
        Signal signal = actionable(signalId);
        PreparedOrder prepared = dryRun(signal, principal);
        if (signal.status() == SignalStatus.ACTIVE) {
            store.update(signal.with(SignalStatus.PREPARED, null, null, null, clock.now()));
            audit.record(AuditEvent.of(AuditEventType.SIGNAL_PREPARED, ActorType.USER).withActorId(prepared.proposal().actorId()).withStrategyId(signal.strategyId())
                    .withSignalId(signal.id()).withPayload(Map.of("quantity", prepared.sizing().get("quantity"), "risk", prepared.risk().outcome().name())));
        }
        return new PreparedOrder(store.find(signal.id()).orElse(signal), prepared.proposal(), prepared.risk(), prepared.sizing(), prepared.notes());
    }

    /** Sizing plus a dry-run risk decision for a signal, with no side effects (the Today screen calls this for every ranked signal). */
    public PreparedOrder dryRun(Signal signal, HejjePrincipal principal) {
        StrategyVersion version = strategies.versionById(signal.versionId()).orElseThrow(() -> new SignalException.NotFound("Version missing"));
        Instrument instrument = instruments.findById(signal.instrumentId()).orElseThrow(() -> new SignalException.NotFound("Instrument missing"));
        Money riskMoney = signal.deploymentId() == null ? engine.riskPerTrade(null, version)
                : strategies.deployment(signal.deploymentId()).map(d -> engine.riskPerTrade(d, version)).orElseGet(() -> engine.riskPerTrade(null, version));
        int maxQty = version.definition().riskOverrides().maxQuantity() == null ? 0 : version.definition().riskOverrides().maxQuantity();
        // size on the current quote when there is one (the market has moved since the signal bar closed), else on the signal's reference
        BigDecimal entryReference = market.lastPrice(signal.instrumentId()).filter(q -> q.signum() > 0).orElse(signal.referencePrice());
        BigDecimal perUnit = entryReference.subtract(signal.stop()).abs();
        List<String> notes = new ArrayList<>();
        boolean stopValid = signal.side() == money.hejje.common.Side.BUY ? signal.stop().compareTo(entryReference) < 0 : signal.stop().compareTo(entryReference) > 0;
        int qty = 0;
        if (!stopValid) {
            notes.add("price " + entryReference.toPlainString() + " is already beyond the stop " + signal.stop().toPlainString());
        } else {
            qty = PositionSizer.size(Price.of(entryReference), Price.of(signal.stop()), riskMoney, instrument.lotSize(), maxQty);
            if (qty <= 0) {
                notes.add("risk budget " + riskMoney.toRupees().toPlainString() + " is below one lot (" + instrument.lotSize() + ") at " + perUnit.toPlainString() + " per unit");
            }
        }
        Money maxRisk = Money.of(perUnit.multiply(BigDecimal.valueOf(Math.max(qty, 0))).setScale(2, java.math.RoundingMode.HALF_UP));
        OrderIntentCommand proposal = new OrderIntentCommand(principal == null ? null : principal.id(), "signal:" + signal.id(), ActorType.USER,
                principal == null ? "system" : principal.name(), signal.strategyId(), signal.id(), signal.instrumentId(), signal.side(), Quantity.of(Math.max(qty, 1)),
                OrderType.MARKET, version.definition().product(), null, null, Price.of(signal.stop()), signal.target() == null ? null : Price.of(signal.target()),
                maxRisk, OrderReason.STRATEGY_SIGNAL);
        RiskDecision decision = qty <= 0 ? RiskDecision.rejected(List.of(new money.hejje.risk.RiskCheck("positionSize", false, "0", ">= 1 lot", notes.get(0))))
                : risk.evaluate(toIntent(proposal));
        Map<String, Object> sizing = new LinkedHashMap<>();
        sizing.put("riskRupees", riskMoney.toRupees().toPlainString());
        sizing.put("entryReference", entryReference.toPlainString());
        sizing.put("riskPerUnit", perUnit.toPlainString());
        sizing.put("lotSize", instrument.lotSize());
        sizing.put("maxQuantity", maxQty);
        sizing.put("quantity", qty);
        return new PreparedOrder(signal, proposal, decision, sizing, notes);
    }

    /** The strategy-managed position an order belongs to (entry, stop or exit order), if any. */
    public Optional<StrategyPosition> positionForOrder(UUID orderId) {
        return store.findByOrder(orderId);
    }

    public Optional<StrategyPosition> position(UUID positionId) {
        return store.findPosition(positionId);
    }

    /** The user's confirmation: submits the prepared order (through validation, risk and the gate) and marks the signal EXECUTED. */
    public HejjeOrder execute(UUID signalId, String idempotencyKey, HejjePrincipal principal) {
        if (isOptions(signalId)) {
            throw new SignalException.NotActionable("an options signal executes as a basket of option legs (executeOptions)");
        }
        Signal signal = actionable(signalId);
        PreparedOrder prepared = prepare(signalId, principal);
        if (prepared.sizing().get("quantity") instanceof Integer q && q <= 0) {
            throw new SignalException.NotActionable("Signal cannot be sized: " + String.join("; ", prepared.notes()));
        }
        OrderIntentCommand c = prepared.proposal();
        OrderIntentCommand command = new OrderIntentCommand(c.clientId(), idempotencyKey, c.source(), c.actorId(), c.strategyId(), c.signalId(), c.instrumentId(),
                c.side(), c.quantity(), c.orderType(), c.product(), c.limitPrice(), c.triggerPrice(), c.stopPrice(), c.targetPrice(), c.maxRisk(), c.reason());
        HejjeOrder order = submitSignal(signal, prepared.signal(), command);
        audit.record(AuditEvent.of(AuditEventType.USER_APPROVED, ActorType.USER).withActorId(command.actorId()).withStrategyId(signal.strategyId())
                .withSignalId(signal.id()).withOrderIntentId(order.intentId()).withOrderId(order.id())
                .withPayload(Map.of("quantity", command.quantity().value(), "stop", signal.stop().toPlainString())));
        return order;
    }

    /**
     * AUTO (plan M5.2): the policy allowed this signal. Sizes it like the confirmation path and submits it as actor
     * STRATEGY through the same pipeline (validation, risk, kill switch, gate); the idempotency key is the signal's, so a
     * redelivered signal can never place a second entry. Audits {@code AUTO_EXECUTED} with the policy decision.
     */
    public HejjeOrder executeAuto(UUID signalId, String actorId, Map<String, Object> decision) {
        Signal signal = actionable(signalId);
        PreparedOrder prepared = dryRun(signal, null);
        if (!(prepared.sizing().get("quantity") instanceof Integer q) || q <= 0) {
            throw new SignalException.NotActionable("Signal cannot be sized: " + String.join("; ", prepared.notes()));
        }
        OrderIntentCommand c = prepared.proposal();
        OrderIntentCommand command = new OrderIntentCommand(AUTO_CLIENT, "auto:" + signal.id(), ActorType.STRATEGY, actorId, c.strategyId(), c.signalId(),
                c.instrumentId(), c.side(), c.quantity(), c.orderType(), c.product(), c.limitPrice(), c.triggerPrice(), c.stopPrice(), c.targetPrice(), c.maxRisk(),
                c.reason());
        HejjeOrder order = submitSignal(signal, signal, command);
        Map<String, Object> payload = new LinkedHashMap<>(decision);
        payload.put("quantity", command.quantity().value());
        payload.put("stop", signal.stop().toPlainString());
        audit.record(AuditEvent.of(AuditEventType.AUTO_EXECUTED, ActorType.STRATEGY).withActorId(actorId).withStrategyId(signal.strategyId())
                .withSignalId(signal.id()).withOrderIntentId(order.intentId()).withOrderId(order.id()).withPayload(payload));
        return order;
    }

    private HejjeOrder submitSignal(Signal signal, Signal consumed, OrderIntentCommand command) {
        // the pending position exists before the order does, so a fast fill always finds it
        StrategyPosition pending = engine.signalExecuting(consumed);
        HejjeOrder order;
        try {
            order = execution.submit(command);
        } catch (RuntimeException e) {
            engine.entryRefused(pending, e.getMessage());
            store.update(store.find(signal.id()).orElse(signal).with(SignalStatus.ACTIVE, "execution refused: " + e.getMessage(), null, null, clock.now()));
            throw e;
        }
        Signal executed = store.find(signal.id()).orElse(signal).with(SignalStatus.EXECUTED, null, order.intentId(), order.id(), clock.now());
        store.update(executed);
        engine.entrySubmitted(pending, order.id());
        return order;
    }

    /** True when the signal's strategy trades option legs (plan M5.4): it executes through {@link #executeOptions}. */
    public boolean isOptions(UUID signalId) {
        return store.find(signalId).flatMap(sig -> strategies.versionById(sig.versionId())).map(v -> !v.definition().legs().isEmpty()).orElse(false);
    }

    /** The user's confirmation of an options signal: its legs are placed as a hedge-first basket and managed as one options position. */
    public money.hejje.options.OptionsPosition executeOptions(UUID signalId, String idempotencyKey, HejjePrincipal principal) {
        Signal signal = actionable(signalId);
        money.hejje.options.OptionsPosition p = openOptions(signal, principal.id(), idempotencyKey, ActorType.USER, principal.name());
        audit.record(AuditEvent.of(AuditEventType.USER_APPROVED, ActorType.USER).withActorId(principal.name()).withStrategyId(signal.strategyId())
                .withSignalId(signal.id()).withPayload(Map.of("optionsPositionId", p.id().toString(), "basketId", p.basketId().toString(),
                        "legs", p.legs().stream().map(l -> l.side() + " " + l.quantity() + " " + l.symbol()).toList())));
        return p;
    }

    /** AUTO (M5.2) for an options signal: the same basket path as a confirmation, as actor STRATEGY with the signal's key. */
    public money.hejje.options.OptionsPosition executeOptionsAuto(UUID signalId, String actorId, Map<String, Object> decision) {
        Signal signal = actionable(signalId);
        money.hejje.options.OptionsPosition p = openOptions(signal, AUTO_CLIENT, "auto:" + signal.id(), ActorType.STRATEGY, actorId);
        Map<String, Object> payload = new LinkedHashMap<>(decision);
        payload.put("optionsPositionId", p.id().toString());
        payload.put("basketId", p.basketId().toString());
        audit.record(AuditEvent.of(AuditEventType.AUTO_EXECUTED, ActorType.STRATEGY).withActorId(actorId).withStrategyId(signal.strategyId())
                .withSignalId(signal.id()).withPayload(payload));
        return p;
    }

    private money.hejje.options.OptionsPosition openOptions(Signal signal, UUID clientId, String key, ActorType source, String actorId) {
        StrategyVersion version = strategies.versionById(signal.versionId()).orElseThrow(() -> new SignalException.NotFound("Version missing"));
        money.hejje.options.OptionsPosition p;
        try {
            p = options.open(new money.hejje.options.OptionsExecutor.OpenRequest(clientId, key, source, actorId, signal.strategyId(), signal.versionId(),
                    signal.deploymentId(), signal.id(), signal.instrumentId(), signal.side(), signal.stop(), version.definition()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new SignalException.NotActionable("Options legs cannot be placed: " + e.getMessage());
        }
        Signal executed = store.find(signal.id()).orElse(signal).with(SignalStatus.EXECUTED, "options position " + p.id() + " (basket " + p.basketId() + ")",
                null, null, clock.now());
        store.update(executed);
        engine.signalGone(executed); // the runner does not manage option legs; the options monitor does
        return p;
    }

    /** The policy denied an AUTO signal (plan M5.2): the signal is BLOCKED with the reason and leaves the runner. */
    public Signal block(UUID signalId, String reason) {
        Signal signal = store.find(signalId).orElseThrow(() -> new SignalException.NotFound("Signal " + signalId + " not found"));
        if (!signal.status().isActionable()) {
            return signal;
        }
        Signal blocked = signal.with(SignalStatus.BLOCKED, reason, null, null, clock.now());
        store.update(blocked);
        audit.record(AuditEvent.of(AuditEventType.SIGNAL_SKIPPED, ActorType.SYSTEM).withActorId("auto").withStrategyId(signal.strategyId())
                .withSignalId(signal.id()).withPayload(Map.of("reason", reason, "status", "BLOCKED")));
        engine.signalGone(blocked);
        return blocked;
    }

    /** Closed paper trades of a version (AUTO qualification). */
    public int closedPaperTrades(UUID versionId) {
        return store.closedPaperTrades(versionId);
    }

    /** A deployment's day so far: entries on any instrument and gross realized P&L (AUTO daily budgets). */
    public record DeploymentDay(int entries, Money realized) {}

    public DeploymentDay deploymentDay(UUID deploymentId, Instant since) {
        return new DeploymentDay(store.entriesSince(deploymentId, since), Money.of(store.realizedSince(deploymentId, since).setScale(2, java.math.RoundingMode.HALF_UP)));
    }

    /** Entries of a deployment on one instrument since {@code since} (the runner's per-day counter). */
    public int entriesSince(UUID deploymentId, UUID instrumentId, Instant since) {
        return store.openedSince(deploymentId, instrumentId, since);
    }

    public Signal skip(UUID signalId, String reason, HejjePrincipal principal) {
        Signal signal = actionable(signalId);
        Signal skipped = signal.with(SignalStatus.SKIPPED, reason == null || reason.isBlank() ? "skipped" : reason, null, null, clock.now());
        store.update(skipped);
        audit.record(AuditEvent.of(AuditEventType.SIGNAL_SKIPPED, ActorType.USER).withActorId(principal == null ? "system" : principal.name())
                .withStrategyId(signal.strategyId()).withSignalId(signal.id()).withPayload(Map.of("reason", skipped.note())));
        engine.signalGone(skipped);
        return skipped;
    }

    private Signal actionable(UUID signalId) {
        Signal signal = store.find(signalId).orElseThrow(() -> new SignalException.NotFound("Signal " + signalId + " not found"));
        if (signal.mode() != properties.mode()) {
            throw new SignalException.NotActionable("Signal belongs to mode " + signal.mode());
        }
        if (signal.isExpiredAt(clock.now())) {
            expire(signal, "validity window passed");
            throw new SignalException.NotActionable("Signal expired at " + signal.validUntil());
        }
        if (!signal.status().isActionable()) {
            throw new SignalException.NotActionable("Signal is " + signal.status());
        }
        return signal;
    }

    private OrderIntent toIntent(OrderIntentCommand c) {
        return new OrderIntent(Ids.newId(), c.idempotencyKey(), c.clientId(), c.source(), c.actorId(), c.strategyId(), c.signalId(), c.instrumentId(), c.side(),
                c.quantity(), c.orderType(), c.product(), c.limitPrice(), c.triggerPrice(), c.stopPrice(), c.targetPrice(), c.maxRisk(), c.reason(),
                properties.mode(), IntentStatus.CREATED, List.of(), clock.now());
    }

    public ExecutionMode mode() {
        return properties.mode();
    }

    public List<StrategyPosition> positions(boolean liveOnly) {
        return engine.positions(liveOnly, 200);
    }

    public Optional<StrategyDeployment> deploymentOf(Signal signal) {
        return signal.deploymentId() == null ? Optional.empty() : strategies.deployment(signal.deploymentId());
    }
}
