package ui

import (
	"fmt"

	"hejje.money/tui/internal/api"
)

// RenderJevLine is the Jev line under `hejje status` (plan M9.1): model, circuit, today's calls, latency and cost.
func RenderJevLine(j api.JevStatus) string {
	model := j.Model
	if j.Fixture {
		model = "fixture"
	}
	line := fmt.Sprintf("Jev %s | circuit %s | today %d calls (%d ok, %d cached, %d timeouts, %d failed)",
		model, j.Circuit, j.Today.Calls, j.Today.Ok, j.Today.Cached, j.Today.Timeouts, j.Today.Failed)
	if j.Today.P50Ms != nil && j.Today.P90Ms != nil {
		line += fmt.Sprintf(" | p50 %d ms p90 %d ms", *j.Today.P50Ms, *j.Today.P90Ms)
	}
	line += fmt.Sprintf(" | cost ₹%.2f", j.Today.CostPaise/100)
	if j.DailyCostCapPaise != nil {
		line += fmt.Sprintf(" of ₹%.2f", *j.DailyCostCapPaise/100)
	}
	if j.BudgetExceeded {
		line += " (cap reached)"
	}
	if !j.KeyPresent {
		line += " | no API key"
	}
	return line
}
