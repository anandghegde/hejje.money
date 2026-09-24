package ui

import (
	"strings"
	"testing"
	"time"

	"github.com/charmbracelet/x/exp/teatest"

	"hejje.money/tui/internal/api"
)

func ip(v int) *int         { return &v }
func fp(v float64) *float64 { return &v }
func bp(v bool) *bool       { return &v }

func sampleRating() api.DailyRating {
	return api.DailyRating{SessionDate: "2026-09-18", Symbol: "NSE:INFY", RsRating: ip(87), AdGrade: "B+", TechComposite: ip(91), OffHighPct: fp(3.2),
		OffLowPct: fp(41.0), VolVsAvg50Pct: fp(38.5), AvgTurnoverCr: fp(812.4), Close: 1495, ChangePct: fp(1.2), GroupID: "information-technology", GroupRank: ip(4)}
}

func sampleAnalogs() api.AnalogSummary {
	return api.AnalogSummary{SessionDate: "2026-09-18", Symbol: "NSE:INFY", Kind: "DAILY", Lookback: 15, Candidates: 1210000, Compared: 244780, Matches: 40,
		MedianQuality: 4.2, QualityTag: "STRONG",
		Outcomes: []api.AnalogOutcome{
			{Forward: "5", Count: 40, WinRate: 0.675, Median: 1.24, P25: -0.8, P75: 2.1, MaeMedian: -1.1, MfeMedian: 1.9, Direction: "BULLISH_STRONG",
				Consistency: "NORMAL", Reliability: "HIGH", Risk: "MODERATE", AvgPath: []float64{0.2, 0.5, 0.7, 1.0, 1.2}},
			{Forward: "10", Count: 40, WinRate: 0.55, Median: 0.9, P25: -2.2, P75: 3.4, MaeMedian: -1.9, MfeMedian: 2.8, Direction: "BULLISH", Consistency: "WIDE",
				Reliability: "HIGH", Risk: "MODERATE", Outlier: true, AvgPath: []float64{0.2, 0.5, 0.7, 1.0, 1.2, 1.1, 0.9, 1.0, 0.8, 0.9}}},
		Splits: []api.AnalogSplit{{Name: "sameMonth", Forward: "5", Count: 6, WinRate: 0.5, Median: 0.2, OtherCount: 34, OtherWinRate: 0.71, OtherMedian: 1.4}},
		Narrative: []string{"In 40 similar past 15-session windows, price ended higher over the next 5 sessions 27 times (68 %); the median move was +1.24 %.",
			"Takeaway: similar setups have historically leaned clearly higher over the next 5 sessions. This is historical context, not a forecast."}}
}

func TestStockPageGolden(t *testing.T) {
	bases := []api.Base{
		{Symbol: "NSE:INFY", Type: "FLAT_BASE", Status: "IN_BUY_ZONE", StatusDate: "2026-09-17", DetectedDate: "2026-09-02", DepthPct: 8.9, Pivot: 1480, BuyHigh: 1554,
			Stop: 1376.4, Goal: 1776, VolumeConfirmed: bp(true)},
		{Symbol: "NSE:INFY", Type: "CUP_WITH_HANDLE", Status: "STOPPED", StatusDate: "2026-03-11", OutcomePct: fp(-7.0), OutcomeR: fp(-1.0)}}
	closes := []float64{1400, 1410, 1405, 1420, 1435, 1430, 1450, 1462, 1458, 1470, 1481, 1477, 1490, 1495}
	analogs := sampleAnalogs()
	teatest.RequireEqualOutput(t, []byte(RenderStock(sampleRating(), bases, closes, &analogs)))
}

func TestRatingsListGolden(t *testing.T) {
	r := sampleRating()
	l := api.RatingsList{Name: "setups", Date: "2026-09-18", Rows: []api.SetupRow{
		{Rating: &r, Base: &api.Base{Symbol: "NSE:INFY", Type: "FLAT_BASE", Status: "IN_BUY_ZONE", Pivot: 1480, VolumeConfirmed: bp(true)}},
		{Base: &api.Base{Symbol: "NSE:NEWCO", Type: "CUP", Status: "NEAR_PIVOT", Pivot: 212.5}}}}
	teatest.RequireEqualOutput(t, []byte(RenderRatingsList(l)))
}

func TestSessionAnalogsGolden(t *testing.T) {
	s := api.AnalogSummary{SessionDate: "2026-09-18", Symbol: "NSE:SBIN", Kind: "SESSION", Lookback: 12, Checkpoint: "10:15", Candidates: 52110, Matches: 50,
		MedianQuality: 3.4, QualityTag: "MODERATE",
		Outcomes: []api.AnalogOutcome{{Forward: "close", Count: 50, WinRate: 0.44, Median: -0.12, P25: -0.6, P75: 0.4, MaeMedian: -0.5, MfeMedian: 0.45,
			Direction: "MIXED", Consistency: "NORMAL", Reliability: "HIGH", Risk: "LOW", AvgPath: []float64{0, -0.05, -0.1, -0.08, -0.12}}},
		Splits: []api.AnalogSplit{{Name: "sameWeekday", Forward: "close", Count: 9, WinRate: 0.33, Median: -0.3, OtherCount: 41, OtherWinRate: 0.46, OtherMedian: -0.1},
			{Name: "expiryDay", Forward: "close", Count: 4, WinRate: 0.5, Median: 0.1, OtherCount: 46, OtherWinRate: 0.43, OtherMedian: -0.14}},
		Session:   &api.AnalogSession{Count: 50, HighHeld: 21, LowHeld: 17, MedianHighTime: "11:40", MedianLowTime: "13:05", MedianReturnAtr: -0.06},
		Narrative: []string{"In 50 similar past sessions at 10:15, price ended higher over the rest of the session (to 15:10) 22 times (44 %)."}}
	teatest.RequireEqualOutput(t, []byte(RenderAnalogs(s)))
}

func TestScreenAndWatchlistRenderCountsNextToRates(t *testing.T) {
	out := RenderScreen(api.ScreenResult{Date: "2026-09-18", Universe: 497, Matched: 1, Rows: []map[string]any{{"symbol": "NSE:INFY", "close": "1495.00",
		"changePct": 1.2, "rsRating": 87.0, "adGrade": "B+", "techComposite": 91.0, "baseType": "FLAT_BASE", "baseStatus": "IN_BUY_ZONE",
		"analogDirection": "BULLISH", "analogWinRate5": 0.62, "analogCount5": 50.0}}})
	for _, want := range []string{"1 of 497 stocks match", "NSE:INFY", "FLAT_BASE IN_BUY_ZONE", "BULLISH", "62% of 50"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	w := RenderWatchlist([]api.WatchlistItem{{Symbol: "NSE:INFY", Note: "results next week", AddedAt: time.Date(2026, 9, 18, 4, 0, 0, 0, time.UTC)}})
	if !strings.Contains(w, "NSE:INFY") || !strings.Contains(w, "2026-09-18") || !strings.Contains(w, "results next week") {
		t.Fatalf("watchlist render:\n%s", w)
	}
	if !strings.Contains(RenderWatchlist(nil), "hejje watch add") {
		t.Fatal("an empty watchlist should say how to add")
	}
}
