package ui

import (
	"fmt"
	"strings"

	"hejje.money/tui/internal/api"
)

// RenderPulse renders the PRD 16 Technical Pulse line, the Market Pulse table and the evidence.
func RenderPulse(p api.PulseSnapshot) string {
	var b strings.Builder
	t := p.Technical
	m := p.Market
	b.WriteString(fmt.Sprintf("TECHNICAL PULSE  %s — %s  (score %+d, coverage %.0f%%, %s)\n\n", t.Direction, t.Strength, t.Score, t.Coverage*100, p.AsOf))
	b.WriteString(fmt.Sprintf("%-16s %s\n", "Market regime", m.Regime))
	b.WriteString(fmt.Sprintf("%-16s %s\n", "Volatility", m.Volatility))
	b.WriteString(fmt.Sprintf("%-16s %s\n", "Breadth", m.Breadth))
	for _, s := range m.Sectors {
		b.WriteString(fmt.Sprintf("%-16s %-8s %s\n", s.Name, s.Label, pct(s.ChangePct)))
	}
	b.WriteString(fmt.Sprintf("%-16s %s\n\n", "Global context", m.GlobalContext))
	b.WriteString("WHY\n")
	for _, e := range t.Evidence {
		b.WriteString("  " + e + "\n")
	}
	return b.String()
}

func pct(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%+.2f%%", *v)
}
