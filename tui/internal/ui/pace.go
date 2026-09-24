package ui

import (
	"fmt"
	"strings"

	"hejje.money/tui/internal/api"
)

// RenderPace prints the pace report's three tables with the count beside every rate (plan M9.7).
func RenderPace(p api.PaceReport) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Pace %s %s..%s: %d trades\n", p.Mode, p.From, p.To, p.Trades)
	for _, t := range []struct {
		title string
		rows  []api.PaceRow
	}{{"Trades that day", p.ByTradesThatDay}, {"Sequence in the day", p.BySequence}, {"Entry hour", p.ByHour}} {
		fmt.Fprintf(&b, "\n%s\n%-8s %6s %8s %13s %12s %10s\n", t.title, "BUCKET", "TRADES", "WIN %", "EXPECT R (N)", "EXPECT ₹", "NET ₹")
		for _, r := range t.rows {
			win, er, ep := "—", "—", "—"
			if r.WinRate != nil {
				win = fmt.Sprintf("%.0f", *r.WinRate*100)
			}
			if r.ExpectancyR != nil {
				er = fmt.Sprintf("%+.2f (%d)", *r.ExpectancyR, r.WithR)
			}
			if r.ExpectancyRupees != nil {
				ep = fmt.Sprintf("%.2f", *r.ExpectancyRupees)
			}
			fmt.Fprintf(&b, "%-8s %6d %8s %13s %12s %10.2f\n", r.Bucket, r.Trades, win, er, ep, r.NetPnl)
		}
	}
	return b.String()
}
