package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderBestAndNoTrade(t *testing.T) {
	score := 87
	entry, stop, target, risk, reward := 1507.5, 1495.0, 1531.0, 2000.0, 4000.0
	best := api.Recommendation{Instrument: "NSE:INFY", Strategy: "orb", Version: 3, Score: &score, Decision: "TRADE", Direction: "BUY", SignalID: "s1",
		SignalStatus: "ACTIVE", Entry: &entry, Stop: &stop, Target: &target, RiskRupees: &risk, ExpectedRewardRupees: &reward,
		Backtest: map[string]any{"expectancyR": 0.42, "winRatePct": 61}, ScoreBreakdown: map[string]any{"adjustments": []any{map[string]any{"name": "Technical compatibility", "delta": 8}}}}
	out := RenderBest(api.TodayView{Best: &best})
	for _, want := range []string{"NSE:INFY — orb v3", "HEJJE SCORE            87 / 100", "LONG", "Entry                  1507.50", "Stop                   1495.00",
		"Target                 1531.00", "Risk                   ₹2000", "Expected reward        ₹4000", "Technical compatibility +8", "0.42 R", "61%"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	noTrade := RenderBest(api.TodayView{NoTrade: "No strategy currently meets your minimum quality threshold (70)."})
	if !strings.Contains(noTrade, "minimum quality threshold") {
		t.Fatalf("no-trade line missing: %s", noTrade)
	}
	if Why(api.Recommendation{HardBlocks: []string{"killSwitch: stopped"}, Risks: []string{"a", "b"}}) != "killSwitch: stopped; a" {
		t.Fatalf("why should list two reasons")
	}
	if DirectionText("SELL") != "SHORT" || ScoreText(nil) != "—" {
		t.Fatalf("formatting helpers")
	}
}
