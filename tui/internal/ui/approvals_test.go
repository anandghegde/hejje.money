package ui

import (
	"strings"
	"testing"
	"time"

	"hejje.money/tui/internal/api"
)

func TestRenderApprovalShowsPolicyRiskAndResult(t *testing.T) {
	now := time.Date(2026, 9, 10, 4, 30, 0, 0, time.UTC)
	a := api.Approval{ID: "a1", Kind: "ORDER_NEW", Status: "PENDING", RequestedBy: "research-bot", Summary: "BUY 100 NSE:INFY MARKET MIS · stop 1490.00",
		Rationale: "ORB breakout", ExpiresAt: "2026-09-10T04:34:12Z", Policy: &api.ApprovalPolicy{Decision: "REQUIRE_APPROVAL", Rule: "agent_actions", Reason: "Agent-prepared actions need human confirmation"},
		Risk: &api.ApprovalRisk{Outcome: "APPROVED", Checks: []api.ApprovalCheck{{Name: "dailyLoss", Passed: true}, {Name: "rewardRisk", Passed: false, Message: "1.2 < 1.5"}}}}
	out := RenderApproval(a, now)
	for _, want := range []string{"APPROVAL a1  ORDER_NEW  PENDING", "BUY 100 NSE:INFY", "requested by research-bot · expires in 4m12s", "why: ORB breakout",
		"policy REQUIRE_APPROVAL (agent_actions)", "risk dry run APPROVED", "✓ dailyLoss", "✗ rewardRisk — 1.2 < 1.5"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	a.Status, a.DecidedBy, a.Result = "APPROVED", "admin", map[string]any{"orderId": "o1", "state": "FILLED"}
	if out := RenderApproval(a, now); !strings.Contains(out, "decided by admin") || !strings.Contains(out, "order o1 FILLED") {
		t.Fatalf("decision missing\n%s", out)
	}
	table := RenderApprovalsTable([]api.Approval{a}, now)
	if !strings.Contains(table, "APPROVED  —") || Expiry("2026-09-10T04:00:00Z", now) != "expired" {
		t.Fatalf("unexpected table/expiry\n%s", table)
	}
}
