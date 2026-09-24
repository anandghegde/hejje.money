package ui

import (
	"fmt"
	"sort"
	"strings"

	"hejje.money/tui/internal/api"
)

// Daily context screens (plan M8.7). Every rate is printed next to its count.

func optInt(v *int) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%d", *v)
}

func optPct(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%+.1f%%", *v)
}

// RenderRatingsList renders one of the lists: setups (the combined ordering), buyzone, nearpivot, leaders, movers, groups.
func RenderRatingsList(l api.RatingsList) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("%s  %s\n\n", strings.ToUpper(l.Name), dash(l.Date)))
	if l.Name == "groups" {
		b.WriteString(fmt.Sprintf("%-4s %-40s %9s %8s\n", "RANK", "GROUP", "STRENGTH", "MEMBERS"))
		for _, g := range l.Groups {
			b.WriteString(fmt.Sprintf("%-4d %-40s %+8.1f%% %8d\n", g.Rank, g.Name, g.Strength*100, g.Members))
		}
		return b.String()
	}
	if len(l.Rows) == 0 {
		b.WriteString("(nothing on this list)\n")
		return b.String()
	}
	b.WriteString(fmt.Sprintf("%-16s %9s %7s %4s %3s %4s %7s %7s  %s\n", "SYMBOL", "CLOSE", "CHG", "RS", "A/D", "COMP", "OFF HI", "VOL", "SETUP"))
	for _, row := range l.Rows {
		symbol, r := "", row.Rating
		if row.Base != nil {
			symbol = row.Base.Symbol
		}
		if r != nil {
			symbol = r.Symbol
			b.WriteString(fmt.Sprintf("%-16s %9.2f %7s %4s %3s %4s %7s %7s  %s\n", symbol, float64(r.Close), optPct(r.ChangePct), optInt(r.RsRating),
				dash(r.AdGrade), optInt(r.TechComposite), optPct(negate(r.OffHighPct)), optPct(r.VolVsAvg50Pct), setup(row.Base)))
		} else {
			b.WriteString(fmt.Sprintf("%-16s %9s %7s %4s %3s %4s %7s %7s  %s\n", symbol, "—", "—", "—", "—", "—", "—", "—", setup(row.Base)))
		}
	}
	return b.String()
}

func negate(v *float64) *float64 {
	if v == nil {
		return nil
	}
	n := -*v
	return &n
}

func setup(base *api.Base) string {
	if base == nil {
		return ""
	}
	s := fmt.Sprintf("%s %s pivot %.2f", base.Type, base.Status, float64(base.Pivot))
	if base.VolumeConfirmed != nil && *base.VolumeConfirmed {
		s += " (volume)"
	}
	return s
}

// RenderScreen renders a screener result with a fixed set of columns.
func RenderScreen(r api.ScreenResult) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("SCREEN  %s  %d of %d stocks match\n\n", dash(r.Date), r.Matched, r.Universe))
	b.WriteString(fmt.Sprintf("%-16s %9s %7s %4s %3s %4s %-22s %-16s %s\n", "SYMBOL", "CLOSE", "CHG", "RS", "A/D", "COMP", "BASE", "ANALOG (5d)", "WIN RATE"))
	for _, row := range r.Rows {
		base := strings.TrimSpace(fmt.Sprintf("%s %s", text(row["baseType"]), text(row["baseStatus"])))
		win := "—"
		if rate, ok := row["analogWinRate5"].(float64); ok {
			win = fmt.Sprintf("%.0f%% of %s", rate*100, text(row["analogCount5"]))
		}
		b.WriteString(fmt.Sprintf("%-16s %9s %7s %4s %3s %4s %-22s %-16s %s\n", text(row["symbol"]), text(row["close"]), signedPct(row["changePct"]),
			text(row["rsRating"]), text(row["adGrade"]), text(row["techComposite"]), dash(base), dash(text(row["analogDirection"])), win))
	}
	return b.String()
}

func text(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case float64:
		if x == float64(int64(x)) {
			return fmt.Sprintf("%d", int64(x))
		}
		return fmt.Sprintf("%.2f", x)
	default:
		return fmt.Sprintf("%v", x)
	}
}

func signedPct(v any) string {
	if f, ok := v.(float64); ok {
		return fmt.Sprintf("%+.1f%%", f)
	}
	return "—"
}

// RenderStock renders the stock page: ratings, the open base with its plan, a braille D1 chart and the daily analogs.
func RenderStock(r api.DailyRating, bases []api.Base, closes []float64, analogs *api.AnalogSummary) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("%s  %.2f  %s   (session %s)\n\n", r.Symbol, float64(r.Close), optPct(r.ChangePct), r.SessionDate))
	b.WriteString(fmt.Sprintf("RS %s   A/D %s   Technical composite %s   Group %s (rank %s)\n", optInt(r.RsRating), dash(r.AdGrade), optInt(r.TechComposite),
		dash(r.GroupID), optInt(r.GroupRank)))
	b.WriteString(fmt.Sprintf("Off high %s   Off low %s   Volume vs 50d %s   Turnover %s cr\n\n", optPct(negate(r.OffHighPct)), optPct(r.OffLowPct),
		optPct(r.VolVsAvg50Pct), optFloat(r.AvgTurnoverCr)))
	if len(closes) > 1 {
		for _, line := range Braille(closes, 60, 8) {
			b.WriteString("  " + line + "\n")
		}
		b.WriteString(fmt.Sprintf("  D1 closes, %d sessions\n\n", len(closes)))
	}
	open := 0
	for _, base := range bases {
		if closedStatus(base.Status) {
			continue
		}
		open++
		b.WriteString(fmt.Sprintf("%s  %s since %s (detected %s, depth %.1f%%)\n", base.Type, base.Status, base.StatusDate, base.DetectedDate, base.DepthPct))
		b.WriteString(fmt.Sprintf("  pivot %.2f   buy zone to %.2f   stop %.2f   goal %.2f   (informational: Hejje does not trade it)\n\n",
			float64(base.Pivot), float64(base.BuyHigh), float64(base.Stop), float64(base.Goal)))
	}
	if open == 0 {
		b.WriteString("No open base.\n\n")
	}
	past := 0
	for _, base := range bases {
		if closedStatus(base.Status) && past < 5 {
			if past == 0 {
				b.WriteString("PAST SETUPS\n")
			}
			past++
			outcome := "not triggered"
			if base.OutcomeR != nil && base.OutcomePct != nil {
				outcome = fmt.Sprintf("%+.1f%% (%+.2f R)", *base.OutcomePct, *base.OutcomeR)
			}
			b.WriteString(fmt.Sprintf("  %-16s %-10s %s  %s\n", base.Type, base.Status, base.StatusDate, outcome))
		}
	}
	if past > 0 {
		b.WriteString("\n")
	}
	if analogs != nil {
		b.WriteString(RenderAnalogs(*analogs))
	}
	return b.String()
}

func optFloat(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%.1f", *v)
}

func closedStatus(s string) bool {
	return s == "HIT_GOAL" || s == "STOPPED" || s == "FAILED" || s == "EXPIRED"
}

// RenderAnalogs renders the outcome table (every rate with its count), the splits and the templated read.
func RenderAnalogs(s api.AnalogSummary) string {
	var b strings.Builder
	if s.Kind == "SESSION" {
		b.WriteString(fmt.Sprintf("SESSION ANALOGS  %s at %s (%s)  %d matches of %d sessions  quality %s (%.1f/5)\n\n", s.Symbol, s.Checkpoint, s.SessionDate,
			s.Matches, s.Candidates, s.QualityTag, s.MedianQuality))
	} else {
		b.WriteString(fmt.Sprintf("DAILY ANALOGS  %s  %d-session window (%s)  %d matches, %d compared of %d windows  quality %s (%.1f/5)\n\n", s.Symbol,
			s.Lookback, s.SessionDate, s.Matches, s.Compared, s.Candidates, s.QualityTag, s.MedianQuality))
	}
	b.WriteString(fmt.Sprintf("%-8s %5s %14s %8s %17s %8s %8s  %s\n", "FORWARD", "N", "HIGHER", "MEDIAN", "P25..P75", "MAE", "MFE", "TAGS"))
	for _, o := range s.Outcomes {
		higher := int(o.WinRate*float64(o.Count) + 0.5)
		tags := []string{o.Direction, "reliability " + o.Reliability, "risk " + o.Risk, o.Consistency}
		if o.Outlier {
			tags = append(tags, "OUTLIER")
		}
		b.WriteString(fmt.Sprintf("%-8s %5d %14s %+7.2f%% %+7.2f..%+7.2f%% %+7.2f%% %+7.2f%%  %s\n", o.Forward, o.Count,
			fmt.Sprintf("%d of %d (%.0f%%)", higher, o.Count, o.WinRate*100), o.Median, o.P25, o.P75, o.MaeMedian, o.MfeMedian, strings.Join(tags, ", ")))
	}
	if len(s.Outcomes) > 0 && len(s.Outcomes[len(s.Outcomes)-1].AvgPath) > 1 {
		last := s.Outcomes[len(s.Outcomes)-1]
		b.WriteString("\n")
		for _, line := range Braille(last.AvgPath, 40, 4) {
			b.WriteString("  " + line + "\n")
		}
		b.WriteString(fmt.Sprintf("  average path of %d matches over %s\n", last.Count, forwardLabel(last.Forward)))
	}
	splits := append([]api.AnalogSplit(nil), s.Splits...)
	sort.SliceStable(splits, func(i, j int) bool { return splits[i].Name < splits[j].Name })
	for i, x := range splits {
		if i == 0 {
			b.WriteString("\n")
		}
		if s.Kind != "SESSION" && x.Forward != "5" {
			continue
		}
		b.WriteString(fmt.Sprintf("%s (%s): %.0f%% higher of %d, median %+.2f%%  |  other: %.0f%% of %d, median %+.2f%%\n", x.Name, forwardLabel(x.Forward),
			x.WinRate*100, x.Count, x.Median, x.OtherWinRate*100, x.OtherCount, x.OtherMedian))
	}
	if s.Session != nil && s.Session.Count > 0 {
		b.WriteString(fmt.Sprintf("checkpoint high held in %d of %d, low held in %d of %d; median high at %s, low at %s; median move %+.2f ATR\n",
			s.Session.HighHeld, s.Session.Count, s.Session.LowHeld, s.Session.Count, s.Session.MedianHighTime, s.Session.MedianLowTime, s.Session.MedianReturnAtr))
	}
	b.WriteString("\n")
	for _, line := range s.Narrative {
		b.WriteString("  " + line + "\n")
	}
	return b.String()
}

func forwardLabel(f string) string {
	if f == "close" {
		return "the rest of the session"
	}
	return f + " sessions"
}

// RenderWatchlist renders the watchlist.
func RenderWatchlist(items []api.WatchlistItem) string {
	if len(items) == 0 {
		return "The watchlist is empty: hejje watch add NSE:INFY\n"
	}
	var b strings.Builder
	b.WriteString(fmt.Sprintf("%-18s %-12s %s\n", "SYMBOL", "ADDED", "NOTE"))
	for _, w := range items {
		b.WriteString(fmt.Sprintf("%-18s %-12s %s\n", w.Symbol, w.AddedAt.Format("2006-01-02"), w.Note))
	}
	return b.String()
}
