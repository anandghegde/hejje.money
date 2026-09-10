package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderDriftShowsStatusSidesAndCriteria(t *testing.T) {
	pf := 1.8
	d := api.StrategyDrift{Enabled: true, Deployments: []api.DeploymentDrift{{
		Report: api.DriftReport{DeploymentID: "dep1", Version: 2, Mode: "PAPER", Enabled: true, SizeMultiplier: 0.5, Status: "DEGRADING",
			Live:      api.DriftSide{Trades: 30, WinRate: 0.47, ExpectancyR: -0.07, MaxDrawdownR: 5.2},
			Backtest:  &api.DriftSide{Trades: 60, WinRate: 0.61, ExpectancyR: 0.28, ProfitFactor: &pf, MaxDrawdownR: 4, Split: "OUT_OF_SAMPLE"},
			Triggered: []string{"win rate 47% vs 61% backtest is unlikely by chance (p = 0.030 < 0.05)"}},
		State: &api.DriftState{Status: "DEGRADING", OverrideStatus: "DEGRADING", OverrideBy: "admin", OverrideReason: "reviewed"},
	}}}
	d.Deployments[0].Report.Window.MaxTrades = 30
	out := RenderDrift(d)
	for _, want := range []string{"dep1 v2 PAPER enabled  DEGRADING  size x0.50", "BACKTEST (OOS)", "TRAILING 30", "61%", "47%", "+0.28R", "-0.07R", "1.80",
		"5.2R", "! win rate 47%", "override: DEGRADING by admin — reviewed"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	if !strings.Contains(RenderDrift(api.StrategyDrift{}), "drift monitor disabled") {
		t.Fatal("disabled render")
	}
}
