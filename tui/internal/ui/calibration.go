package ui

import (
	"fmt"
	"strings"

	"hejje.money/tui/internal/api"
)

// RenderCalibration prints a calibration report as a bucket table (plan M9.2).
func RenderCalibration(r api.CalibrationReport) string {
	var b strings.Builder
	version := "—"
	if r.Version != nil {
		version = *r.Version
	}
	fmt.Fprintf(&b, "%s v%s: %d labelled, %d none, %d pending, %d sessions\n", r.Purpose, version, r.N, r.None, r.Pending, r.Sessions)
	fmt.Fprintf(&b, "%-11s %6s %6s %7s %7s %15s\n", "BUCKET", "N", "HITS", "MEAN P", "RATE", "WILSON 95%")
	for _, k := range r.Buckets {
		mean, rate, wilson := "—", "—", "—"
		if k.MeanProbability != nil {
			mean = fmt.Sprintf("%.2f", *k.MeanProbability)
		}
		if k.Rate != nil {
			rate = fmt.Sprintf("%.2f", *k.Rate)
		}
		if k.WilsonLo != nil && k.WilsonHi != nil {
			wilson = fmt.Sprintf("%.2f–%.2f", *k.WilsonLo, *k.WilsonHi)
		}
		fmt.Fprintf(&b, "%.1f–%.1f   %6d %6d %7s %7s %15s\n", k.Lo, k.Hi, k.N, k.Hits, mean, rate, wilson)
	}
	fmt.Fprintf(&b, "Brier %s  ECE %s\n", optF(r.Brier), optF(r.Ece))
	if r.Passes {
		b.WriteString("PASSES the pre-registered bar (docs/calibration.md)\n")
	} else {
		b.WriteString("Does not pass:\n")
		for _, why := range r.Reasons {
			fmt.Fprintf(&b, "  - %s\n", why)
		}
	}
	return b.String()
}

func optF(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%.4f", *v)
}
