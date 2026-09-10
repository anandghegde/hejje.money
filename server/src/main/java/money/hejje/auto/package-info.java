/**
 * AUTO execution (PRD sections 26, 27, 49; plan M5.2): each new signal of a deployment at autonomy 4-5 is decided by the
 * policy engine with its deterministic context (qualification, daily budget, event risk, score, re-entry). ALLOW submits
 * it through the normal execution pipeline as actor STRATEGY; REQUIRE_APPROVAL hands it to the approvals inbox;
 * DENY blocks it. No LLM is involved. Live AUTO needs {@code hejje.mode=AUTO}, the prod profile and
 * {@code hejje.auto.acknowledged=true}; PAPER deployments at autonomy 4-5 rehearse the same path with simulated fills.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.auto;
