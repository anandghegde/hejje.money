package money.hejje.risk.policy;

import static money.hejje.risk.policy.PolicyAction.ORDER_CANCEL;
import static money.hejje.risk.policy.PolicyAction.ORDER_MODIFY;
import static money.hejje.risk.policy.PolicyAction.ORDER_NEW;
import static money.hejje.risk.policy.PolicyAction.POSITION_CLOSE;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.risk.RiskDashboard;
import org.junit.jupiter.api.Test;

/** The PRD 49 policy table as seeded in V25, evaluated without a database. */
class PolicyEngineTest {

    static PolicyRule rule(String name, int priority, PolicyCondition condition, Set<PolicyAction> actions, PolicyDecision decision, Map<String, Object> params) {
        return new PolicyRule(UUID.randomUUID(), name, priority, condition, actions, decision, params, true, name.replace('_', ' '), Instant.EPOCH, "seed");
    }

    static List<PolicyRule> seed() {
        return List.of(
                rule("daily_loss_block", 10, PolicyCondition.DAILY_LOSS_EXCEEDED, Set.of(ORDER_NEW), PolicyDecision.DENY, Map.of("lossLimitPct", 100)),
                rule("deployment_budget", 15, PolicyCondition.DEPLOYMENT_BUDGET_EXCEEDED, Set.of(ORDER_NEW), PolicyDecision.DENY, Map.of()),
                rule("agent_needs_prepare_level", 30, PolicyCondition.AUTONOMY_BELOW_PREPARE, Set.of(ORDER_NEW), PolicyDecision.DENY, Map.of("minLevel", 2)),
                rule("event_risk_high", 40, PolicyCondition.EVENT_RISK_HIGH, Set.of(ORDER_NEW), PolicyDecision.REQUIRE_APPROVAL, Map.of()),
                rule("new_strategy_version", 50, PolicyCondition.NEW_STRATEGY_VERSION, Set.of(ORDER_NEW), PolicyDecision.REQUIRE_APPROVAL, Map.of()),
                rule("agent_actions", 60, PolicyCondition.ACTOR_AGENT, Set.of(), PolicyDecision.REQUIRE_APPROVAL, Map.of()),
                rule("manual_orders", 70, PolicyCondition.ACTOR_USER, Set.of(), PolicyDecision.REQUIRE_APPROVAL, Map.of()),
                rule("score_below_80", 80, PolicyCondition.SCORE_BELOW, Set.of(ORDER_NEW), PolicyDecision.REQUIRE_APPROVAL, Map.of("threshold", 80)),
                rule("auto_strategy", 85, PolicyCondition.AUTO_ELIGIBLE, Set.of(ORDER_NEW), PolicyDecision.ALLOW, Map.of("minLevel", 4)),
                rule("strategy_signals", 90, PolicyCondition.ACTOR_STRATEGY, Set.of(ORDER_NEW), PolicyDecision.REQUIRE_APPROVAL, Map.of()));
    }

    static Supplier<RiskDashboard> net(long paise) {
        return () -> new RiskDashboard(ExecutionMode.PAPER, Money.ZERO, Money.ZERO, Money.ofPaise(paise), Money.ofPaise(500_000), Money.ZERO, Money.ZERO, 0, 5, 0,
                20, 0, BigDecimal.ZERO, false);
    }

    static PolicyRequest agent(PolicyAction action, Integer autonomy, String eventRisk) {
        return new PolicyRequest(action, ActorType.AGENT, ExecutionMode.PAPER, autonomy, eventRisk, null, false, null, null);
    }

    static PolicyResult decide(PolicyRequest r) {
        return PolicyEngine.evaluate(seed(), r, net(0));
    }

    @Test
    void agentProposalsNeedApprovalAndLowOrFutureAutonomyIsDenied() {
        assertThat(decide(agent(ORDER_NEW, 3, "LOW"))).extracting(PolicyResult::decision, PolicyResult::rule)
                .containsExactly(PolicyDecision.REQUIRE_APPROVAL, "agent_actions");
        assertThat(decide(agent(ORDER_NEW, 2, "LOW")).decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        PolicyResult level1 = decide(agent(ORDER_NEW, 1, "LOW"));
        assertThat(level1.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(level1.rule()).isEqualTo("agent_needs_prepare_level");
        assertThat(level1.reason()).contains("autonomy level 1 < 2");
        assertThat(decide(agent(ORDER_NEW, 0, null)).decision()).isEqualTo(PolicyDecision.DENY);
        // levels 4-5 no longer deny outright (M5.2), but an agent never executes automatically
        assertThat(decide(agent(ORDER_NEW, 4, "LOW"))).extracting(PolicyResult::decision, PolicyResult::rule)
                .containsExactly(PolicyDecision.REQUIRE_APPROVAL, "agent_actions");
        // the prepare-level rule is about new exposure: a level-1 strategy's position can still be closed with approval
        assertThat(decide(agent(POSITION_CLOSE, 1, "LOW"))).extracting(PolicyResult::decision, PolicyResult::rule)
                .containsExactly(PolicyDecision.REQUIRE_APPROVAL, "agent_actions");
    }

    @Test
    void eventRiskAndUnprovenVersionsRequireApprovalAheadOfTheAgentRule() {
        assertThat(decide(agent(ORDER_NEW, 3, "HIGH")).rule()).isEqualTo("event_risk_high");
        PolicyResult fresh = decide(new PolicyRequest(ORDER_NEW, ActorType.AGENT, ExecutionMode.PAPER, 3, "LOW", 90, true, null, null));
        assertThat(fresh).extracting(PolicyResult::decision, PolicyResult::rule).containsExactly(PolicyDecision.REQUIRE_APPROVAL, "new_strategy_version");
    }

    @Test
    void dailyLossBlocksNewExposureButNotClosesAndIsOnlyFetchedWhenNeeded() {
        assertThat(PolicyEngine.evaluate(seed(), agent(ORDER_NEW, 3, "LOW"), net(-500_000)))
                .extracting(PolicyResult::decision, PolicyResult::rule).containsExactly(PolicyDecision.DENY, "daily_loss_block");
        assertThat(PolicyEngine.evaluate(seed(), agent(ORDER_NEW, 3, "LOW"), net(-499_999)).decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        AtomicInteger fetched = new AtomicInteger();
        Supplier<RiskDashboard> counting = () -> {
            fetched.incrementAndGet();
            return net(-900_000).get();
        };
        assertThat(PolicyEngine.evaluate(seed(), agent(POSITION_CLOSE, 3, "LOW"), counting).decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        assertThat(PolicyEngine.evaluate(seed(), agent(ORDER_CANCEL, 3, "LOW"), counting).decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        assertThat(fetched).hasValue(0);
    }

    @Test
    void strategyAndManualActorsFollowThePrdTable() {
        assertThat(decide(new PolicyRequest(ORDER_NEW, ActorType.STRATEGY, ExecutionMode.PAPER, 3, "LOW", 70, false, null, null)).rule())
                .isEqualTo("score_below_80");
        assertThat(decide(new PolicyRequest(ORDER_NEW, ActorType.STRATEGY, ExecutionMode.PAPER, 3, "LOW", 85, false, null, null)).rule())
                .isEqualTo("strategy_signals");
        assertThat(decide(new PolicyRequest(ORDER_NEW, ActorType.USER, ExecutionMode.PAPER, null, "LOW", null, false, null, null)).rule())
                .isEqualTo("manual_orders");
    }

    @Test
    void disabledRulesAreSkippedAllowIsCappedForAgentsAndNoMatchNeedsApproval() {
        List<PolicyRule> allowAll = List.of(rule("allow_everything", 1, PolicyCondition.ALWAYS, Set.of(), PolicyDecision.ALLOW, Map.of()));
        PolicyResult capped = PolicyEngine.evaluate(allowAll, agent(ORDER_NEW, 3, "LOW"), net(0));
        assertThat(capped.decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        assertThat(capped.reason()).contains("Automation Level 3");
        assertThat(PolicyEngine.evaluate(allowAll, new PolicyRequest(ORDER_NEW, ActorType.USER, ExecutionMode.PAPER, null, null, null, false, null, null), net(0))
                .decision()).isEqualTo(PolicyDecision.ALLOW);

        List<PolicyRule> rules = new ArrayList<>();
        for (PolicyRule r : seed()) {
            rules.add(r.name().equals("agent_actions") ? new PolicyRule(r.id(), r.name(), r.priority(), r.condition(), r.actions(), r.decision(), r.params(), false,
                    r.description(), r.updatedAt(), r.updatedBy()) : r);
        }
        PolicyResult none = PolicyEngine.evaluate(rules, agent(ORDER_MODIFY, 3, "LOW"), net(0));
        assertThat(none.decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        assertThat(none.rule()).isNull();
        assertThat(none.trace()).contains("agent_actions: disabled");
    }

    static PolicyRequest strategy(int autonomy, boolean qualified, Integer score, String eventRisk, String budgetBreach) {
        return new PolicyRequest(ORDER_NEW, ActorType.STRATEGY, ExecutionMode.PAPER, autonomy, eventRisk, score, false, null, null, qualified, budgetBreach);
    }

    @Test
    void qualifiedAutoStrategiesAreAllowedAndEveryRowAheadOfThemStillHolds() {
        assertThat(decide(strategy(4, true, 85, "LOW", null))).extracting(PolicyResult::decision, PolicyResult::rule)
                .containsExactly(PolicyDecision.ALLOW, "auto_strategy");
        assertThat(decide(strategy(5, true, 85, "LOW", null)).decision()).isEqualTo(PolicyDecision.ALLOW);
        // not qualified, unscored or below level 4: human confirmation
        assertThat(decide(strategy(4, false, 85, "LOW", null)).rule()).isEqualTo("strategy_signals");
        assertThat(decide(strategy(4, true, null, "LOW", null)).rule()).isEqualTo("strategy_signals");
        assertThat(decide(strategy(3, true, 85, "LOW", null)).rule()).isEqualTo("strategy_signals");
        // the PRD 49 rows ahead of auto_strategy keep holding at level 5
        assertThat(decide(strategy(5, true, 70, "LOW", null)).rule()).isEqualTo("score_below_80");
        assertThat(decide(strategy(5, true, 90, "HIGH", null)).rule()).isEqualTo("event_risk_high");
        assertThat(decide(new PolicyRequest(ORDER_NEW, ActorType.STRATEGY, ExecutionMode.AUTO, 5, "LOW", 90, true, null, null, true, null)).rule())
                .isEqualTo("new_strategy_version");
        PolicyResult budget = decide(strategy(5, true, 90, "LOW", "the deployment has made 3 of its 3 entries today"));
        assertThat(budget).extracting(PolicyResult::decision, PolicyResult::rule).containsExactly(PolicyDecision.DENY, "deployment_budget");
        assertThat(budget.reason()).contains("3 of its 3 entries");
        assertThat(PolicyEngine.evaluate(seed(), strategy(5, true, 90, "LOW", null), net(-500_000)).rule()).isEqualTo("daily_loss_block");
    }

    @Test
    void agentsNeverExecuteAutomaticallyEvenWhenQualified() {
        PolicyRequest agentAtFive = new PolicyRequest(ORDER_NEW, ActorType.AGENT, ExecutionMode.PAPER, 5, "LOW", 95, false, null, null, true, null);
        assertThat(decide(agentAtFive).rule()).isEqualTo("agent_actions");
        List<PolicyRule> allowAll = List.of(rule("allow_everything", 1, PolicyCondition.ALWAYS, Set.of(), PolicyDecision.ALLOW, Map.of()));
        assertThat(PolicyEngine.evaluate(allowAll, agentAtFive, net(0)).decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        assertThat(PolicyEngine.evaluate(allowAll, strategy(4, true, 85, "LOW", null), net(0)).decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(PolicyEngine.evaluate(allowAll, strategy(4, false, 85, "LOW", null), net(0)).decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
        // a hand-lowered minLevel cannot automate below level 4
        List<PolicyRule> lowered = List.of(rule("auto_strategy", 85, PolicyCondition.AUTO_ELIGIBLE, Set.of(ORDER_NEW), PolicyDecision.ALLOW, Map.of("minLevel", 2)));
        assertThat(PolicyEngine.evaluate(lowered, strategy(3, true, 85, "LOW", null), net(0)).decision()).isEqualTo(PolicyDecision.REQUIRE_APPROVAL);
    }
}
