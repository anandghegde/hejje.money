package money.hejje.auto;

import java.time.Instant;
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
import money.hejje.common.Money;
import money.hejje.common.config.AutoProperties;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.events.EventRisk;
import money.hejje.events.EventService;
import money.hejje.orders.HejjeOrder;
import money.hejje.risk.KillSwitchState;
import money.hejje.risk.RiskService;
import money.hejje.risk.policy.PolicyAction;
import money.hejje.risk.policy.PolicyDecision;
import money.hejje.risk.policy.PolicyEngine;
import money.hejje.risk.policy.PolicyRequest;
import money.hejje.risk.policy.PolicyResult;
import money.hejje.scoring.ScoreBreakdown;
import money.hejje.scoring.ScoringService;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Decides and executes the signals of autonomy 4-5 deployments (plan M5.2). Deterministic: the same signal, deployment,
 * policy table and context always give the same decision; nothing here calls an LLM.
 */
@Service
public class AutoExecutor {

    private static final Logger log = LoggerFactory.getLogger(AutoExecutor.class);

    private final SignalService signals;
    private final StrategyService strategies;
    private final PolicyEngine policies;
    private final RiskService risk;
    private final EventService events;
    private final ScoringService scoring;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final AutoProperties properties;
    private final HejjeClock clock;

    AutoExecutor(SignalService signals, StrategyService strategies, PolicyEngine policies, RiskService risk, EventService events, ScoringService scoring,
            AuditService audit, ApplicationEventPublisher publisher, AutoProperties properties, HejjeClock clock) {
        this.signals = signals;
        this.strategies = strategies;
        this.policies = policies;
        this.risk = risk;
        this.events = events;
        this.scoring = scoring;
        this.audit = audit;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    /** Re-offers every actionable signal of an autonomy 4-5 deployment (startup and periodic sweep; executed signals are skipped). */
    public List<AutoDecision> sweep() {
        return signals.active().stream().filter(s -> signals.deploymentOf(s).map(d -> d.autonomyLevel() >= 4).orElse(false)).map(s -> onSignal(s.id()))
                .toList();
    }

    public synchronized AutoDecision onSignal(UUID signalId) {
        Optional<Signal> found = signals.find(signalId);
        if (found.isEmpty()) {
            return skipped(signalId, "unknown signal");
        }
        Signal s = found.get();
        Optional<StrategyDeployment> deployment = signals.deploymentOf(s);
        if (deployment.isEmpty() || deployment.get().autonomyLevel() < 4) {
            return skipped(signalId, "autonomy below 4: the confirmation flow applies");
        }
        StrategyDeployment d = deployment.get();
        ExecutionMode mode = signals.mode();
        if (!s.status().isActionable() || s.isExpiredAt(clock.now())) {
            return skipped(signalId, "signal is " + s.status() + (s.status().isActionable() ? " but expired" : ""));
        }
        if (s.mode() != mode || d.mode() != mode) {
            return skipped(signalId, "the deployment runs in " + d.mode() + "; the server runs in " + mode);
        }
        if (!d.enabled()) {
            return skipped(signalId, "the deployment is paused");
        }
        String actor = strategies.find(s.strategyId()).map(Strategy::slug).orElse("strategy");
        KillSwitchState killSwitch = risk.killSwitch(mode);
        if (killSwitch.stopNewOrders()) {
            String reason = "kill switch STOP_NEW_ORDERS is active" + (killSwitch.reason() == null ? "" : " (" + killSwitch.reason() + ")");
            record(s, actor, AutoDecision.Outcome.DENIED, reason, null, Map.of());
            return new AutoDecision(signalId, AutoDecision.Outcome.DENIED, reason, null, null);
        }

        StrategyVersion version = strategies.versionById(s.versionId()).orElseThrow();
        // "promoted for the mode": paper rehearsal needs a PAPER or LIVE version, live AUTO a LIVE one (and paper history)
        boolean newVersion = mode.simulated() ? version.status() != VersionStatus.PAPER && version.status() != VersionStatus.LIVE
                : version.status() != VersionStatus.LIVE;
        Integer paperTrades = mode.simulated() ? null : signals.closedPaperTrades(version.id());
        boolean qualified = !newVersion && (paperTrades == null || paperTrades >= properties.minPaperTrades());
        Instant dayStart = clock.today().atStartOfDay(clock.zone()).toInstant();
        SignalService.DeploymentDay day = signals.deploymentDay(d.id(), dayStart);
        String budgetBreach = budgetBreach(d, day);
        EventRisk eventRisk = events.risk(s.instrumentId());
        String eventLevel = eventRisk.available() && eventRisk.level() != null ? eventRisk.level().name() : null;
        Integer score = scoring.latest(version.id(), s.instrumentId()).map(ScoreBreakdown::finalScore).orElse(null);
        // an options strategy cannot be backtested, so it never has a score; its PAPER rehearsal may still run AUTO (plan M6.4)
        boolean unscoredPaper = mode.simulated() && !version.definition().legs().isEmpty();
        PolicyResult policy = policies.decide(new PolicyRequest(PolicyAction.ORDER_NEW, ActorType.STRATEGY, mode, d.autonomyLevel(), eventLevel, score,
                newVersion, s.strategyId(), s.instrumentId(), qualified, budgetBreach, unscoredPaper));
        int entriesOnInstrument = signals.entriesSince(d.id(), s.instrumentId(), dayStart);
        if (policy.decision() == PolicyDecision.ALLOW && d.autonomyLevel() == 4 && entriesOnInstrument > 0) {
            policy = new PolicyResult(PolicyDecision.REQUIRE_APPROVAL, policy.rule(), "autonomy 4 automates the first entry per instrument and day; this is entry "
                    + (entriesOnInstrument + 1) + " today, so it needs a human (autonomy 5 re-enters within the daily budget)", policy.trace());
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("deploymentId", d.id().toString());
        context.put("autonomyLevel", d.autonomyLevel());
        context.put("mode", mode.name());
        context.put("versionStatus", version.status().name());
        context.put("qualified", qualified);
        if (paperTrades != null) {
            context.put("paperTrades", paperTrades);
            context.put("minPaperTrades", properties.minPaperTrades());
        }
        context.put("entriesToday", day.entries());
        context.put("realizedToday", day.realized().toRupeesString());
        context.put("eventRisk", eventLevel == null ? "UNKNOWN" : eventLevel);
        if (score != null) {
            context.put("score", score);
        }
        context.put("decision", policy.decision().name());
        context.put("rule", policy.rule() == null ? "default" : policy.rule());
        context.put("reason", policy.reason());
        context.put("trace", policy.trace());

        switch (policy.decision()) {
            case ALLOW -> {
                try {
                    if (signals.isOptions(s.id())) {
                        signals.executeOptionsAuto(s.id(), actor, context); // options legs as a basket (M5.4)
                        return new AutoDecision(signalId, AutoDecision.Outcome.EXECUTED, policy.reason(), policy, null);
                    }
                    HejjeOrder order = signals.executeAuto(s.id(), actor, context);
                    return new AutoDecision(signalId, AutoDecision.Outcome.EXECUTED, policy.reason(), policy, order.id());
                } catch (RuntimeException e) {
                    log.warn("AUTO execution of signal {} refused: {}", s.id(), e.getMessage());
                    Map<String, Object> failed = new LinkedHashMap<>(context);
                    failed.put("error", String.valueOf(e.getMessage()));
                    record(s, actor, AutoDecision.Outcome.FAILED, "the pipeline refused the order: " + e.getMessage(), policy, failed);
                    return new AutoDecision(signalId, AutoDecision.Outcome.FAILED, e.getMessage(), policy, null);
                }
            }
            case REQUIRE_APPROVAL -> {
                record(s, actor, AutoDecision.Outcome.HELD, policy.reason(), policy, context);
                publisher.publishEvent(new AutoExecutionHeld(EventMeta.create(clock), s.id(), d.id(), s.strategyId(), actor, policy.reason(), policy));
                return new AutoDecision(signalId, AutoDecision.Outcome.HELD, policy.reason(), policy, null);
            }
            default -> {
                String reason = "policy " + (policy.rule() == null ? "default" : policy.rule()) + ": " + policy.reason();
                signals.block(s.id(), reason);
                record(s, actor, AutoDecision.Outcome.DENIED, reason, policy, context);
                return new AutoDecision(signalId, AutoDecision.Outcome.DENIED, reason, policy, null);
            }
        }
    }

    /** Null while the deployment is within its daily budget (deployment params override the {@code hejje.auto} defaults). */
    String budgetBreach(StrategyDeployment d, SignalService.DeploymentDay day) {
        int maxTrades = number(d.params().get("daily_max_trades")).map(Number::intValue).orElse(properties.defaultMaxTradesPerDay());
        Money maxLoss = number(d.params().get("daily_max_loss_rupees")).map(n -> Money.of(new java.math.BigDecimal(n.toString()).setScale(2, java.math.RoundingMode.HALF_UP)))
                .orElse(Money.ofRupees(properties.defaultMaxLossRupees()));
        if (day.entries() >= maxTrades) {
            return "the deployment has made " + day.entries() + " of its " + maxTrades + " entries today";
        }
        if (maxLoss.paise() > 0 && day.realized().paise() <= -maxLoss.paise()) {
            return "the deployment lost " + day.realized().negate().toRupeesString() + " today (budget " + maxLoss.toRupeesString() + ")";
        }
        return null;
    }

    private static Optional<Number> number(Object v) {
        return v instanceof Number n && n.doubleValue() > 0 ? Optional.of(n) : Optional.empty();
    }

    private void record(Signal s, String actor, AutoDecision.Outcome outcome, String reason, PolicyResult policy, Map<String, Object> context) {
        Map<String, Object> payload = new LinkedHashMap<>(context);
        payload.put("outcome", outcome.name());
        payload.put("reason", reason);
        audit.record(AuditEvent.of(AuditEventType.AUTO_HELD, ActorType.STRATEGY).withActorId(actor).withStrategyId(s.strategyId()).withSignalId(s.id())
                .withPayload(payload));
    }

    private static AutoDecision skipped(UUID signalId, String reason) {
        return new AutoDecision(signalId, AutoDecision.Outcome.SKIPPED, reason, null, null);
    }
}
