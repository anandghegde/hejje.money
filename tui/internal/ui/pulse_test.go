package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderPulse(t *testing.T) {
	change := 1.8
	p := api.PulseSnapshot{AsOf: "2026-09-08T04:31:00Z",
		Technical: api.TechnicalPulse{Direction: "BULLISH", Strength: "STRONG", Score: 72, Coverage: 0.9, Evidence: []string{"+15.0 Index 24930.00 is +0.35% from its session average 24843.00"}},
		Market: api.MarketPulse{Regime: "Trending ↑", Volatility: "Moderate", Breadth: "Positive", GlobalContext: "NEUTRAL",
			Sectors: []api.SectorStrength{{Name: "Banking", Label: "STRONG", ChangePct: &change}, {Name: "IT", Label: "UNKNOWN"}}}}
	out := RenderPulse(p)
	for _, want := range []string{"BULLISH — STRONG", "score +72", "coverage 90%", "Market regime    Trending ↑", "Banking          STRONG   +1.80%", "IT               UNKNOWN  —",
		"Global context   NEUTRAL", "+15.0 Index 24930.00"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
}
