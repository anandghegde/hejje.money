package money.hejje.risk.internal;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.risk.policy.PolicyDecision;
import money.hejje.risk.policy.PolicyEngine;
import money.hejje.risk.policy.PolicyRule;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inspectable approval policy (PRD 49): read with {@code risk:read}, edit with {@code risk:write}. */
@RestController
@RequestMapping("/api/v1/risk/policies")
class PolicyController {

    record PolicyView(List<PolicyRule> rules, PolicyDecision defaultDecision, List<String> notes) {}

    record UpdateRequest(Boolean enabled, PolicyDecision decision, Integer priority, Map<String, Object> params) {}

    static final List<String> NOTES = List.of(
            "Rules are evaluated by priority; the first enabled rule whose actions and condition match decides.",
            "No match means REQUIRE_APPROVAL.",
            "Agents are capped at REQUIRE_APPROVAL (Automation Level 3); only AUTO_ELIGIBLE rules may ALLOW, and only strategy signals of a qualified deployment at autonomy 4-5 reach it.",
            "Autonomy levels are set per deployment (0-5; 4-5 on PAPER and AUTO deployments only); agent proposals without a strategy use hejje.agent.approvals.account-autonomy-level.",
            "Qualified: the version is promoted for the mode (LIVE for live trading) and, for live modes, has hejje.auto.min-paper-trades closed paper trades.",
            "Autonomy 4 automates the first entry per instrument and day; autonomy 5 also re-enters within the deployment's daily budget and pauses itself on drift.");

    private final PolicyEngine policies;

    PolicyController(PolicyEngine policies) {
        this.policies = policies;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_risk:read')")
    PolicyView list() {
        return new PolicyView(policies.rules(), PolicyEngine.DEFAULT, NOTES);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_risk:write')")
    ResponseEntity<PolicyRule> update(@PathVariable UUID id, @RequestBody UpdateRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        try {
            return ResponseEntity.ok(policies.update(id, body.enabled(), body.decision(), body.priority(), body.params(), principal.name()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
