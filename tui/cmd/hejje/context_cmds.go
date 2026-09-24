package main

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/api"
	"hejje.money/tui/internal/ui"
)

// Daily context commands (plan M8.7): screen, stock, analogs, watch.

var listNames = map[string]string{"setups": "setups", "leaders": "leaders", "buyzone": "buyzone", "nearpivot": "nearpivot", "movers": "movers", "groups": "groups"}

// parseFilter reads field:op:value, for example rsRating:gte:80 or baseStatus:in:IN_BUY_ZONE,NEAR_PIVOT.
func parseFilter(text string) (api.ScreenFilter, error) {
	parts := strings.SplitN(text, ":", 3)
	if len(parts) != 3 {
		return api.ScreenFilter{}, fmt.Errorf("filter %q is not field:op:value", text)
	}
	var value any = parts[2]
	if parts[1] == "in" {
		value = strings.Split(parts[2], ",")
	} else if n, err := strconv.ParseFloat(parts[2], 64); err == nil {
		value = n
	}
	return api.ScreenFilter{Field: parts[0], Op: parts[1], Value: value}, nil
}

func screenCmd() *cobra.Command {
	var list, sortBy string
	var filters []string
	var limit int
	cmd := &cobra.Command{Use: "screen", Short: "Daily lists (setups, leaders, buyzone, nearpivot, movers, groups) or a screen with --filter field:op:value",
		RunE: func(_ *cobra.Command, _ []string) error {
			if len(filters) > 0 {
				parsed := make([]api.ScreenFilter, 0, len(filters))
				for _, f := range filters {
					p, err := parseFilter(f)
					if err != nil {
						return err
					}
					parsed = append(parsed, p)
				}
				r, err := client.Screen(parsed, sortBy, limit)
				if err != nil {
					return err
				}
				emit(r, func() { fmt.Print(ui.RenderScreen(r)) })
				return nil
			}
			name, ok := listNames[list]
			if !ok {
				return fmt.Errorf("--list must be one of setups, leaders, buyzone, nearpivot, movers, groups")
			}
			l, err := client.RatingsList(name)
			if err != nil {
				return err
			}
			emit(l, func() { fmt.Print(ui.RenderRatingsList(l)) })
			return nil
		}}
	cmd.Flags().StringVar(&list, "list", "setups", "setups|leaders|buyzone|nearpivot|movers|groups")
	cmd.Flags().StringArrayVar(&filters, "filter", nil, "field:op:value (op: gte lte gt lt eq ne in); repeatable, all must hold")
	cmd.Flags().StringVar(&sortBy, "sort", "", "sort field, - prefix for descending (default -techComposite)")
	cmd.Flags().IntVar(&limit, "limit", 30, "rows")
	return cmd
}

func stockCmd() *cobra.Command {
	return &cobra.Command{Use: "stock <symbol>", Short: "Ratings, base and plan, D1 chart and daily analogs of a stock", Args: cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			symbol := strings.ToUpper(args[0])
			rating, err := client.Rating(symbol)
			if err != nil {
				return err
			}
			bases, err := client.BasesOf(symbol)
			if err != nil {
				return err
			}
			var closes []float64
			if instrument, err := client.ResolveInstrument(symbol); err == nil {
				if candles, err := client.DailyCandles(instrument.ID, 380); err == nil {
					for _, c := range candles {
						closes = append(closes, float64(c.Close))
					}
				}
			}
			var analogs *api.AnalogSummary
			if a, err := client.DailyAnalogs(symbol, 15); err == nil { // analogs may be off or not computed: the page still renders
				analogs = &a
			}
			emit(map[string]any{"rating": rating, "bases": bases, "analogs": analogs}, func() { fmt.Print(ui.RenderStock(rating, bases, closes, analogs)) })
			return nil
		}}
}

func analogsCmd() *cobra.Command {
	var lookback int
	var session bool
	var checkpoint string
	cmd := &cobra.Command{Use: "analogs <symbol>", Short: "What followed past windows that looked like now (daily), or like today's session so far (--session)",
		Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
			symbol := strings.ToUpper(args[0])
			var s api.AnalogSummary
			var err error
			if session {
				s, err = client.SessionAnalogs(symbol, checkpoint)
			} else {
				s, err = client.DailyAnalogs(symbol, lookback)
			}
			if err != nil {
				return err
			}
			emit(s, func() { fmt.Print(ui.RenderAnalogs(s)) })
			return nil
		}}
	cmd.Flags().IntVar(&lookback, "lookback", 15, "window in sessions: 5 10 15 20 25 30 40 50")
	cmd.Flags().BoolVar(&session, "session", false, "session analogs: today so far against past sessions")
	cmd.Flags().StringVar(&checkpoint, "checkpoint", "", "09:45|10:15|11:15|13:00 (default: the latest that has passed)")
	return cmd
}

func watchCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "watch", Short: "The watchlist: setup alerts and session analogs for these symbols"}
	ls := &cobra.Command{Use: "ls", Short: "List the watchlist", RunE: func(_ *cobra.Command, _ []string) error {
		w, err := client.Watchlist()
		if err != nil {
			return err
		}
		emit(w, func() { fmt.Print(ui.RenderWatchlist(w)) })
		return nil
	}}
	var note string
	add := &cobra.Command{Use: "add <symbol>", Short: "Add a symbol (or replace its note)", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		w, err := client.Watch(strings.ToUpper(args[0]), note)
		if err != nil {
			return err
		}
		emit(w, func() { fmt.Printf("watching %s\n", w.Symbol) })
		return nil
	}}
	add.Flags().StringVar(&note, "note", "", "a note shown next to the symbol")
	rm := &cobra.Command{Use: "rm <symbol>", Short: "Remove a symbol", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		if err := client.Unwatch(strings.ToUpper(args[0])); err != nil {
			return err
		}
		emit(map[string]string{"removed": strings.ToUpper(args[0])}, func() { fmt.Printf("removed %s\n", strings.ToUpper(args[0])) })
		return nil
	}}
	cmd.AddCommand(ls, add, rm)
	return cmd
}
