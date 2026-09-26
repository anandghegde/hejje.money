package ui

import (
	"fmt"
	"strings"
	"text/tabwriter"

	"hejje.money/tui/internal/api"
)

// RenderSwingBook renders `hejje swing`: the open swing positions with their GTTs, the overnight risk and today's
// watched setups (plan M11.6). The numbers are the API's, unchanged.
func RenderSwingBook(positions []api.SwingPosition, risk api.SwingRisk, setups []api.SwingSetup) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Swing book, %s (swing is PAPER only)  %d/%d open  overnight risk %s of %s  deployed %s of %s  gap %.1f%%\n", risk.Mode, risk.OpenPositions,
		risk.MaxOpenPositions, Rupees(risk.OvernightRisk.Paise), Rupees(risk.Budget.Paise), Rupees(risk.Deployed.Paise), Rupees(risk.Capital.Paise),
		float64(risk.GapAllowancePct))
	b.WriteString("\n")
	if len(positions) == 0 {
		b.WriteString("No open swing positions.\n")
	} else {
		w := tabwriter.NewWriter(&b, 0, 2, 2, ' ', 0)
		fmt.Fprintln(w, "SYMBOL\tENTRY DATE\tDAYS\tQTY\tENTRY\tLAST\tSTOP\tGOAL\tR\tUNREAL ₹\tGTT")
		for _, p := range positions {
			gtt := p.Gtt
			if p.GttID != "" {
				gtt += " " + p.GttID
			}
			if p.Gtt == "MISSING" {
				gtt = "MISSING !"
			}
			fmt.Fprintf(w, "%s\t%s\t%d\t%d\t%.2f\t%.2f\t%s\t%s\t%s\t%s\t%s\n", p.Symbol, p.EntryDate, p.DaysHeld, p.Quantity, float64(p.EntryPrice),
				float64(p.LastPrice), optPrice(p.Stop), optPrice(p.Goal), optR(p.R), Rupees(p.UnrealizedPnl.Paise), gtt)
		}
		w.Flush()
	}
	b.WriteString("\nSetups watched today\n")
	if len(setups) == 0 {
		b.WriteString("None.\n")
		return b.String()
	}
	w := tabwriter.NewWriter(&b, 0, 2, 2, ' ', 0)
	fmt.Fprintln(w, "SYMBOL\tTYPE\tPIVOT\tBUY TO\tSTOP\tGOAL\tLAST\tPACE\tSTATE")
	for _, s := range setups {
		pace := "—"
		if s.Pace != nil {
			pace = fmt.Sprintf("%.2fx", float64(*s.Pace))
		}
		fmt.Fprintf(w, "%s\t%s\t%.2f\t%.2f\t%.2f\t%.2f\t%s\t%s\t%s\n", s.Symbol, s.Type, float64(s.Pivot), float64(s.BuyHigh), float64(s.Stop), float64(s.Goal),
			optPrice(s.LastClose), pace, s.State)
	}
	w.Flush()
	return b.String()
}

// OvernightRiskLine is the swing line of `hejje risk` (plan M11.6); empty without a swing module.
func OvernightRiskLine(r api.RiskDashboard) string {
	if r.OvernightRisk == nil || r.OvernightRiskBudget == nil {
		return ""
	}
	positions := 0
	if r.SwingPositions != nil {
		positions = *r.SwingPositions
	}
	pct := 0.0
	if r.OvernightRiskBudget.Paise > 0 {
		pct = 100 * float64(r.OvernightRisk.Paise) / float64(r.OvernightRiskBudget.Paise)
	}
	return fmt.Sprintf("Swing overnight risk %s of %s (%.0f%%, gap-adjusted)  %d swing position(s)", Rupees(r.OvernightRisk.Paise),
		Rupees(r.OvernightRiskBudget.Paise), pct, positions)
}

func optPrice(v *api.Flex) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%.2f", float64(*v))
}

func optR(v *api.Flex) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%+.2f", float64(*v))
}
