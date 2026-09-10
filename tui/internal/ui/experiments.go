package ui

import (
	"fmt"
	"strings"

	"hejje.money/tui/internal/api"
)

// RenderExperiments lists experiments, newest first.
func RenderExperiments(list []api.Experiment) string {
	if len(list) == 0 {
		return "No experiments.\n"
	}
	var b strings.Builder
	b.WriteString(fmt.Sprintf("%-38s %-8s %-8s %s\n", "ID", "STATUS", "VARIANTS", "GOAL"))
	for _, e := range list {
		b.WriteString(fmt.Sprintf("%-38s %-8s %-8d %s\n", e.ID, e.Status, len(e.Variants), e.Goal))
	}
	return b.String()
}

// RenderExperiment prints the ranked variants with their verdicts and warnings.
func RenderExperiment(e api.Experiment) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("EXPERIMENT %s  %s  %s\n", e.ID, e.Status, e.Goal))
	for _, n := range e.Notes {
		b.WriteString("  ⚠ " + n + "\n")
	}
	b.WriteString(fmt.Sprintf("\n%-4s %-20s %-22s %6s %7s %6s %6s %6s  %s\n", "#", "VARIANT", "VERDICT", "SCORE", "EXP R", "PF", "DD R", "TRADES", "WARNINGS"))
	for _, v := range e.Variants {
		rank, score, exp, pf, dd, trades := "—", "—", "—", "—", "—", "—"
		if v.Rank != nil {
			rank = fmt.Sprint(*v.Rank)
		}
		if v.Score != nil {
			score = fmt.Sprintf("%.1f", *v.Score)
		}
		if v.Metrics != nil {
			s := v.Metrics.Overall
			if v.Metrics.OutOfSample != nil {
				s = *v.Metrics.OutOfSample
			} else if v.Metrics.Validation != nil {
				s = *v.Metrics.Validation
			}
			exp = fmt.Sprintf("%.2f", s.ExpectancyR)
			if s.ProfitFactor != nil {
				pf = fmt.Sprintf("%.2f", *s.ProfitFactor)
			}
			dd = fmt.Sprintf("%.1f", v.Metrics.Overall.MaxDrawdownR)
			trades = fmt.Sprint(v.Metrics.Overall.Trades)
		}
		verdict := v.Verdict
		if verdict == "" {
			verdict = v.Status
		}
		var codes []string
		for _, w := range v.Warnings {
			codes = append(codes, strings.SplitN(w, ":", 2)[0])
		}
		note := strings.Join(codes, ",")
		if v.Error != "" {
			note = v.Error
		}
		b.WriteString(fmt.Sprintf("%-4s %-20s %-22s %6s %7s %6s %6s %6s  %s\n", rank, v.Name, verdict, score, exp, pf, dd, trades, note))
	}
	return b.String()
}
