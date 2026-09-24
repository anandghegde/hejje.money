package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderCalibrationShowsCountsAndHidesSmallRates(t *testing.T) {
	v, mean, rate, lo, hi, brier := "1", 0.55, 0.51, 0.42, 0.60, 0.2411
	r := api.CalibrationReport{Purpose: "bot:momo", Version: &v, N: 312, None: 40, Pending: 3, Sessions: 18, Brier: &brier,
		Buckets: []api.CalibrationBucket{{Lo: 0.5, Hi: 0.6, N: 120, Hits: 61, MeanProbability: &mean, Rate: &rate, WilsonLo: &lo, WilsonHi: &hi},
			{Lo: 0.9, Hi: 1.0, N: 7, Hits: 6}},
		Reasons: []string{"expected calibration error n/a, the bar is 0.07"}}
	out := RenderCalibration(r)
	for _, want := range []string{"bot:momo v1: 312 labelled, 40 none, 3 pending, 18 sessions", "0.5–0.6", "0.42–0.60", "Brier 0.2411", "Does not pass:"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	last := strings.Split(strings.TrimSpace(out), "\n")[3]
	if !strings.Contains(last, "0.9–1.0") || strings.Contains(last, "0.86") {
		t.Fatalf("a small bucket shows a rate: %s", last)
	}
}
