package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderExperimentShowsRanksVerdictsAndWarnings(t *testing.T) {
	one, two := 1, 2
	s1, s2 := 71.5, 40.0
	pf := 1.6
	e := api.Experiment{ID: "e1", Status: "DONE", Goal: "fewer false breakouts", Notes: []string{"MULTIPLE_COMPARISONS: 3 variants were tested"},
		Variants: []api.ExperimentVariant{
			{Name: "vwap_filter", Rank: &one, Score: &s1, Verdict: "RECOMMENDED", Metrics: &api.VariantMetrics{Overall: api.SplitSummary{Trades: 140, MaxDrawdownR: 4},
				OutOfSample: &api.SplitSummary{Trades: 55, ExpectancyR: 0.35, ProfitFactor: &pf}}},
			{Name: "baseline", Rank: &two, Score: &s2, Verdict: "BASELINE", Warnings: []string{"LOW_TRADES: 60 trades"}, Metrics: &api.VariantMetrics{Overall: api.SplitSummary{Trades: 60, ExpectancyR: 0.2}}},
			{Name: "broken", Status: "INVALID", Error: "stop.value: required"}}}
	out := RenderExperiment(e)
	for _, want := range []string{"EXPERIMENT e1  DONE  fewer false breakouts", "⚠ MULTIPLE_COMPARISONS", "vwap_filter", "RECOMMENDED", "71.5", "0.35", "1.60",
		"LOW_TRADES", "INVALID", "stop.value: required"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	if !strings.Contains(RenderExperiments([]api.Experiment{e}), "e1") || RenderExperiments(nil) != "No experiments.\n" {
		t.Fatal("list render")
	}
}
