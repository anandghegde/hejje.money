package ui

import (
	"fmt"
	"strings"

	"hejje.money/tui/internal/api"
)

// ScoreText renders a nullable score.
func ScoreText(score *int) string {
	if score == nil {
		return "—"
	}
	return fmt.Sprintf("%d", *score)
}

// DirectionText maps BUY/SELL to LONG/SHORT.
func DirectionText(direction string) string {
	switch direction {
	case "BUY":
		return "LONG"
	case "SELL":
		return "SHORT"
	}
	return "—"
}

// Why is the short reason column of the ranked table: hard blocks first, then risks.
func Why(r api.Recommendation) string {
	all := append(append([]string{}, r.HardBlocks...), r.Risks...)
	if len(all) > 2 {
		all = all[:2]
	}
	return strings.Join(all, "; ")
}

func num(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%.2f", *v)
}

func rupees(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("₹%.0f", *v)
}

// RenderBest renders the PRD 8.2 Best Hejje card (or the No-Trade line).
func RenderBest(t api.TodayView) string {
	var b strings.Builder
	if t.Best == nil {
		b.WriteString("BEST HEJJE\n  " + t.NoTrade + "\n")
		return b.String()
	}
	r := t.Best
	b.WriteString(fmt.Sprintf("BEST HEJJE  %s — %s v%d\n", r.Instrument, r.Strategy, r.Version))
	b.WriteString(fmt.Sprintf("  HEJJE SCORE            %s / 100\n", ScoreText(r.Score)))
	b.WriteString(fmt.Sprintf("  Direction              %s\n", DirectionText(r.Direction)))
	b.WriteString(fmt.Sprintf("  Signal                 %s (until %s)\n", r.SignalStatus, r.SignalValidUntil))
	if v, ok := r.Backtest["expectancyR"]; ok {
		b.WriteString(fmt.Sprintf("  Backtested expectancy  %+v R\n", v))
	}
	if v, ok := r.Backtest["winRatePct"]; ok {
		b.WriteString(fmt.Sprintf("  Historical win rate    %v%%\n", v))
	}
	if v, ok := r.Backtest["profitFactor"]; ok && v != nil {
		b.WriteString(fmt.Sprintf("  Profit factor          %v\n", v))
	}
	if v, ok := r.Backtest["maxDrawdownR"]; ok {
		b.WriteString(fmt.Sprintf("  Max drawdown           -%v R\n", v))
	}
	if adj, ok := r.ScoreBreakdown["adjustments"].([]any); ok {
		for _, a := range adj {
			if m, ok := a.(map[string]any); ok {
				b.WriteString(fmt.Sprintf("  %-22s %s\n", m["name"], signed(m["delta"])))
			}
		}
	}
	b.WriteString(fmt.Sprintf("  Entry                  %s\n", num(r.Entry)))
	b.WriteString(fmt.Sprintf("  Stop                   %s\n", num(r.Stop)))
	b.WriteString(fmt.Sprintf("  Target                 %s\n", num(r.Target)))
	b.WriteString(fmt.Sprintf("  Risk                   %s\n", rupees(r.RiskRupees)))
	b.WriteString(fmt.Sprintf("  Expected reward        %s\n", rupees(r.ExpectedRewardRupees)))
	b.WriteString(fmt.Sprintf("  Signal id              %s\n", r.SignalID))
	return b.String()
}

// RenderDetails renders the PRD 20 evidence and risks of a recommendation.
func RenderDetails(r api.Recommendation) string {
	var b strings.Builder
	b.WriteString("  Supporting evidence\n")
	for _, e := range r.SupportingEvidence {
		b.WriteString("    " + e + "\n")
	}
	b.WriteString("  Risks\n")
	for _, e := range r.Risks {
		b.WriteString("    " + e + "\n")
	}
	for _, e := range r.HardBlocks {
		b.WriteString("    ✗ " + e + "\n")
	}
	return b.String()
}

// RenderPrepared renders a prepared order and its risk checks.
func RenderPrepared(p api.PreparedOrder) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("PREPARED ORDER for signal %s\n", p.Signal.ID))
	b.WriteString(fmt.Sprintf("  %s %d %s/%s stop %s target %s max risk %s\n", p.Proposal.Side, p.Proposal.Quantity, p.Proposal.OrderType, p.Proposal.Product,
		p.Proposal.StopPrice, orDash(p.Proposal.TargetPrice), Rupees(p.Proposal.MaxRisk.Paise)))
	b.WriteString(fmt.Sprintf("  Risk: %s\n", p.Risk.Outcome))
	for _, c := range p.Risk.Checks {
		mark := "✓"
		if !c.Passed {
			mark = "✗"
		}
		b.WriteString(fmt.Sprintf("    %s %-22s %s\n", mark, c.Name, c.Message))
	}
	for _, n := range p.Notes {
		b.WriteString("    ! " + n + "\n")
	}
	return b.String()
}

// RenderScore renders the PRD 14 breakdown table.
func RenderScore(s api.ScoreView) string {
	var b strings.Builder
	if s.Breakdown == nil {
		b.WriteString("HEJJE SCORE  — (not scored yet)\n")
		return b.String()
	}
	b.WriteString(fmt.Sprintf("HEJJE SCORE (v%d)\n", s.Version))
	for _, c := range s.Breakdown.Components {
		b.WriteString(fmt.Sprintf("  %-32s %3.0f%% %6.1f -> %5.1f\n", c.Name, c.Weight*100, c.Score, c.Contribution))
	}
	cap := ""
	if s.Breakdown.Cap != "" {
		cap = " (" + s.Breakdown.Cap + ")"
	}
	b.WriteString(fmt.Sprintf("  %-32s %19.1f%s\n", "Base backtest score", s.Breakdown.Base, cap))
	for _, a := range s.Breakdown.Adjustments {
		b.WriteString(fmt.Sprintf("  %-32s %+19d\n", a.Name, a.Delta))
	}
	b.WriteString(fmt.Sprintf("  %-32s %19d\n", "FINAL HEJJE SCORE", s.Breakdown.FinalScore))
	return b.String()
}

// signed renders a JSON number (float64 or int) with an explicit sign.
func signed(v any) string {
	switch n := v.(type) {
	case float64:
		return fmt.Sprintf("%+.0f", n)
	case int:
		return fmt.Sprintf("%+d", n)
	default:
		return fmt.Sprintf("%v", v)
	}
}

func orDash(s string) string {
	if s == "" {
		return "—"
	}
	return s
}
