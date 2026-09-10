package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderAiTurnMarksUnverifiedNumbersAndListsTools(t *testing.T) {
	turn := api.AiTurn{Answer: "Score 97 [get_market_regime], risk 1,500 and 970 bars.", Steps: 2, Profile: "reasoning", Flow: "why_ranked_first",
		Grounding: api.Grounding{VerifiedNumbers: []string{"1,500", "970"}, UnverifiedNumbers: []string{"97"}},
		Trace: []api.AiTraceStep{{Tool: "get_market_regime", RequiredScope: "market:read", Status: "OK", LatencyMs: 12},
			{Tool: "get_account_risk", RequiredScope: "risk:read", Status: "FORBIDDEN", Error: "Tool get_account_risk requires scope risk:read"}}}
	out := RenderAiTurn(turn)
	for _, want := range []string{"Score 97[unverified] [get_market_regime], risk 1,500 and 970 bars.", "⚠ unverified (not in this turn's tool results): 97",
		"get_market_regime          market:read      OK", "get_account_risk           risk:read        FORBIDDEN", "requires scope risk:read",
		"profile reasoning · 2 step(s) · flow why_ranked_first"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	clean := RenderAiTurn(api.AiTurn{Answer: "ok", Grounding: api.Grounding{VerifiedNumbers: []string{"87"}}})
	if !strings.Contains(clean, "✓ 1 number(s) traced") || !strings.Contains(clean, "(none)") {
		t.Fatalf("unexpected clean render\n%s", clean)
	}
}
