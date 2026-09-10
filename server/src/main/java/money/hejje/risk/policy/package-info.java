/**
 * Policy engine (PRD 49, plan M4.4): explicit, inspectable rules ({@code policy_rule}, {@code GET /api/v1/risk/policies})
 * deciding whether an action may run (ALLOW), needs a human (REQUIRE_APPROVAL) or is refused (DENY). Rules are evaluated
 * by priority and the first enabled matching rule decides; agents are capped at REQUIRE_APPROVAL (Automation Level 3).
 * From M5.2 an AUTO_ELIGIBLE rule may ALLOW strategy signals of a qualified deployment at autonomy 4-5.
 */
@org.springframework.modulith.NamedInterface("policy")
package money.hejje.risk.policy;
