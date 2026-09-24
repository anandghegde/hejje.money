package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderJevLineShowsUsageLatencyAndCap(t *testing.T) {
	p50, p90 := int64(180), int64(420)
	capPaise := 5000.0
	j := api.JevStatus{Enabled: true, KeyPresent: true, Model: "jev-1.13.0", Circuit: "CLOSED", DailyCostCapPaise: &capPaise,
		Today: api.JevUsage{Calls: 812, Ok: 790, Timeouts: 18, Failed: 4, CostPaise: 867.6, P50Ms: &p50, P90Ms: &p90}}
	out := RenderJevLine(j)
	for _, want := range []string{"Jev jev-1.13.0", "circuit CLOSED", "812 calls (790 ok, 0 cached, 18 timeouts, 4 failed)", "p50 180 ms p90 420 ms",
		"cost ₹8.68 of ₹50.00"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in %s", want, out)
		}
	}
	bare := RenderJevLine(api.JevStatus{Enabled: true, Fixture: true, Circuit: "OPEN", BudgetExceeded: true})
	for _, want := range []string{"Jev fixture", "circuit OPEN", "(cap reached)", "no API key"} {
		if !strings.Contains(bare, want) {
			t.Fatalf("missing %q in %s", want, bare)
		}
	}
	if strings.Contains(bare, "p50") {
		t.Fatalf("latency without data in %s", bare)
	}
}
