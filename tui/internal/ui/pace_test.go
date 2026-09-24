package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderPaceShowsCountsBesideRates(t *testing.T) {
	wr, er, ep := 0.33, -0.2, -16.67
	p := api.PaceReport{Mode: "PAPER", From: "2026-09-01", To: "2026-09-30", Trades: 6,
		ByTradesThatDay: []api.PaceRow{{Bucket: "5-8", Trades: 6, Wins: 2, WinRate: &wr, ExpectancyR: &er, WithR: 5, ExpectancyRupees: &ep, NetPnl: -100}, {Bucket: "17+"}}}
	out := RenderPace(p)
	for _, want := range []string{"Pace PAPER 2026-09-01..2026-09-30: 6 trades", "Trades that day", "-0.20 (5)", "-16.67", "Entry hour"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
}
