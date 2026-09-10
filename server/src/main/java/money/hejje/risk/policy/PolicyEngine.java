package money.hejje.risk.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.Money;
import money.hejje.common.time.HejjeClock;
import money.hejje.risk.RiskDashboard;
import money.hejje.risk.RiskService;
import money.hejje.risk.internal.PolicyRuleStore;
import org.springframework.stereotype.Service;

/**
 * {@code Policy.decide(action, actorType, autonomyLevel, mode, context) → ALLOW | REQUIRE_APPROVAL | DENY} (plan M4.4).
 * The first enabled rule (by priority) whose actions and condition match decides; no match means REQUIRE_APPROVAL.
 * ALLOW reaches only the local user and, from M5.2, strategy signals of a qualified deployment at autonomy 4-5; every
 * other actor (agents always) is capped at REQUIRE_APPROVAL.
 */
@Service
public class PolicyEngine {

    public static final PolicyDecision DEFAULT = PolicyDecision.REQUIRE_APPROVAL;

    private final PolicyRuleStore store;
    private final RiskService risk;
    private final AuditService audit;
    private final HejjeClock clock;

    PolicyEngine(PolicyRuleStore store, RiskService risk, AuditService audit, HejjeClock clock) {
        this.store = store;
        this.risk = risk;
        this.audit = audit;
        this.clock = clock;
    }

    public PolicyResult decide(PolicyRequest request) {
        return evaluate(store.findAll(), request, () -> risk.dashboard(request.mode()));
    }

    public List<PolicyRule> rules() {
        return store.findAll();
    }

    /** Edits a rule ({@code risk:write}). ALLOW is accepted only on AUTO_ELIGIBLE rules (automatic execution of qualified strategy signals). */
    public PolicyRule update(UUID id, Boolean enabled, PolicyDecision decision, Integer priority, Map<String, Object> params, String by) {
        PolicyRule current = store.find(id).orElseThrow(() -> new NoSuchElementException("Unknown policy rule " + id));
        if (decision == PolicyDecision.ALLOW && current.condition() != PolicyCondition.AUTO_ELIGIBLE) {
            throw new IllegalArgumentException("Only an AUTO_ELIGIBLE rule may ALLOW (automatic execution of qualified strategy signals); "
                    + "this rule may require approval or deny");
        }
        PolicyRule next = new PolicyRule(current.id(), current.name(), priority != null ? priority : current.priority(), current.condition(), current.actions(),
                decision != null ? decision : current.decision(), params != null ? params : current.params(), enabled != null ? enabled : current.enabled(),
                current.description(), clock.now(), by);
        store.update(next);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rule", next.name());
        payload.put("enabled", next.enabled());
        payload.put("decision", next.decision().name());
        payload.put("priority", next.priority());
        payload.put("params", next.params());
        audit.record(AuditEvent.of(AuditEventType.POLICY_UPDATED, ActorType.USER).withActorId(by).withPayload(payload));
        return next;
    }

    /** Pure evaluation (the dashboard is fetched only when a daily-loss rule needs it). */
    public static PolicyResult evaluate(List<PolicyRule> rules, PolicyRequest r, Supplier<RiskDashboard> dashboard) {
        List<String> trace = new ArrayList<>();
        RiskDashboard[] cached = new RiskDashboard[1];
        Supplier<RiskDashboard> lazy = () -> {
            if (cached[0] == null) {
                cached[0] = dashboard.get();
            }
            return cached[0];
        };
        List<PolicyRule> ordered = rules.stream().sorted(Comparator.comparingInt(PolicyRule::priority).thenComparing(PolicyRule::name)).toList();
        for (PolicyRule rule : ordered) {
            if (!rule.actions().isEmpty() && !rule.actions().contains(r.action())) {
                continue;
            }
            if (!rule.enabled()) {
                trace.add(rule.name() + ": disabled");
                continue;
            }
            String why = matches(rule, r, lazy);
            if (why == null) {
                trace.add(rule.name() + ": no match");
                continue;
            }
            PolicyDecision decision = rule.decision();
            String reason = rule.description() + " (" + why + ")";
            if (decision == PolicyDecision.ALLOW && r.actorType() != ActorType.USER && !autoAllowed(r)) {
                decision = PolicyDecision.REQUIRE_APPROVAL;
                reason += "; capped at human confirmation (Automation Level 3: only a qualified strategy deployment at autonomy 4-5 executes automatically)";
            }
            trace.add(rule.name() + ": " + decision);
            return new PolicyResult(decision, rule.name(), reason, trace);
        }
        return new PolicyResult(DEFAULT, null, "No rule matched; human confirmation is the default", trace);
    }

    /** ALLOW may reach this request: a strategy signal of a qualified deployment at autonomy 4-5 (plan M5.2). */
    static boolean autoAllowed(PolicyRequest r) {
        return r.actorType() == ActorType.STRATEGY && r.autoQualified() && r.autonomyLevel() != null && r.autonomyLevel() >= 4;
    }

    /** Null when the condition does not hold, else a short explanation. */
    static String matches(PolicyRule rule, PolicyRequest r, Supplier<RiskDashboard> dashboard) {
        Map<String, Object> p = rule.params();
        return switch (rule.condition()) {
            case ALWAYS -> "always";
            case ACTOR_AGENT -> r.actorType() == ActorType.AGENT ? "actor AGENT" : null;
            case ACTOR_USER -> r.actorType() == ActorType.USER ? "actor USER" : null;
            case ACTOR_STRATEGY -> r.actorType() == ActorType.STRATEGY ? "actor STRATEGY" : null;
            case EVENT_RISK_HIGH -> "HIGH".equals(r.eventRisk()) ? "event risk HIGH" : null;
            case NEW_STRATEGY_VERSION -> r.newStrategyVersion() ? "the strategy version is not LIVE" : null;
            case SCORE_BELOW -> {
                int threshold = intParam(p, "threshold", 80);
                yield r.score() != null && r.score() < threshold ? "score " + r.score() + " < " + threshold : null;
            }
            case AUTONOMY_BELOW_PREPARE -> {
                int min = intParam(p, "minLevel", 2);
                yield r.actorType() == ActorType.AGENT && r.autonomyLevel() != null && r.autonomyLevel() < min
                        ? "autonomy level " + r.autonomyLevel() + " < " + min : null;
            }
            case AUTONOMY_ABOVE_PHASE -> {
                int max = intParam(p, "maxLevel", 3);
                yield r.autonomyLevel() != null && r.autonomyLevel() > max ? "autonomy level " + r.autonomyLevel() + " > " + max : null;
            }
            case AUTO_ELIGIBLE -> {
                int min = Math.max(4, intParam(p, "minLevel", 4));
                yield r.actorType() == ActorType.STRATEGY && r.autonomyLevel() != null && r.autonomyLevel() >= min && r.autoQualified() && r.score() != null
                        ? "strategy deployment at autonomy " + r.autonomyLevel() + ", qualified for automation, score " + r.score() : null;
            }
            case DEPLOYMENT_BUDGET_EXCEEDED -> r.budgetBreach();
            case DAILY_LOSS_EXCEEDED -> {
                RiskDashboard d = dashboard.get();
                long threshold = d.dailyLossLimit().paise() * intParam(p, "lossLimitPct", 100) / 100;
                long net = d.netPnl().paise();
                yield threshold > 0 && net <= -threshold
                        ? "net P&L today " + d.netPnl().toRupeesString() + " breaches the daily loss threshold " + Money.ofPaise(threshold).toRupeesString() : null;
            }
        };
    }

    private static int intParam(Map<String, Object> params, String name, int fallback) {
        Object v = params.get(name);
        return v instanceof Number n ? n.intValue() : fallback;
    }
}
