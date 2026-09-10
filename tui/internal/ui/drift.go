package ui

import (
	"fmt"
	"strings"

	"hejje.money/tui/internal/api"
)

// RenderDrift prints the live-vs-backtest comparison per deployment (PRD 25): status, both sides, criteria, override and last actions.
func RenderDrift(d api.StrategyDrift) string {
	var b strings.Builder
	b.WriteString("DRIFT (live vs backtest)\n")
	if !d.Enabled {
		b.WriteString("  drift monitor disabled\n")
	}
	if len(d.Deployments) == 0 {
		b.WriteString("  no deployments\n")
	}
	for _, x := range d.Deployments {
		r := x.Report
		state := "enabled"
		if !r.Enabled {
			state = "paused"
		}
		size := ""
		if r.SizeMultiplier > 0 && r.SizeMultiplier < 1 {
			size = fmt.Sprintf("  size x%.2f", r.SizeMultiplier)
		}
		b.WriteString(fmt.Sprintf("  %s v%d %s %s  %s%s\n", r.DeploymentID, r.Version, r.Mode, state, r.Status, size))
		bt := DriftSide(r.Backtest)
		label := "BACKTEST"
		if r.Backtest != nil && r.Backtest.Split == "OUT_OF_SAMPLE" {
			label = "BACKTEST (OOS)"
		}
		b.WriteString(fmt.Sprintf("    %-14s %16s %16s\n", "", label, fmt.Sprintf("TRAILING %d", r.Window.MaxTrades)))
		live := DriftSide(&r.Live)
		for i, name := range []string{"Trades", "Win rate", "Expectancy", "Profit factor", "Max drawdown"} {
			b.WriteString(fmt.Sprintf("    %-14s %16s %16s\n", name, bt[i], live[i]))
		}
		for _, t := range r.Triggered {
			b.WriteString("    ! " + t + "\n")
		}
		if x.State != nil && x.State.OverrideStatus != "" {
			b.WriteString(fmt.Sprintf("    override: %s by %s — %s\n", x.State.OverrideStatus, x.State.OverrideBy, x.State.OverrideReason))
		}
		if len(x.History) > 0 && len(x.History[0].Actions) > 0 {
			b.WriteString("    last actions: " + strings.Join(x.History[0].Actions, ", ") + "\n")
		}
	}
	return b.String()
}

// DriftSide formats trades, win rate, expectancy, profit factor and max drawdown; dashes when the side is missing.
func DriftSide(s *api.DriftSide) []string {
	if s == nil {
		return []string{"—", "—", "—", "—", "—"}
	}
	pf := "—"
	if s.ProfitFactor != nil {
		pf = fmt.Sprintf("%.2f", *s.ProfitFactor)
	}
	return []string{fmt.Sprint(s.Trades), fmt.Sprintf("%.0f%%", s.WinRate*100), fmt.Sprintf("%+.2fR", s.ExpectancyR), pf, fmt.Sprintf("%.1fR", s.MaxDrawdownR)}
}
