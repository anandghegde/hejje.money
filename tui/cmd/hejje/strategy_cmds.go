package main

import (
	"fmt"
	"strings"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/api"
	"hejje.money/tui/internal/ui"
)

func bestCmd() *cobra.Command {
	return &cobra.Command{Use: "best", Short: "Best Hejje and the ranked opportunities (Today)", RunE: func(_ *cobra.Command, _ []string) error {
		t, err := client.Today()
		if err != nil {
			return err
		}
		emit(t, func() {
			fmt.Print(ui.RenderBest(t))
			fmt.Println()
			fmt.Printf("%-3s %-28s %-24s %5s %-6s %-6s %s\n", "#", "INSTRUMENT", "STRATEGY", "SCORE", "DIR", "DECIS", "WHY")
			for i, r := range t.Ranked {
				fmt.Printf("%-3d %-28s %-24s %5s %-6s %-6s %s\n", i+1, r.Instrument, fmt.Sprintf("%s v%d", r.Strategy, r.Version), ui.ScoreText(r.Score),
					ui.DirectionText(r.Direction), r.Decision, ui.Why(r))
			}
		})
		return nil
	}}
}

func strategiesCmd() *cobra.Command {
	return &cobra.Command{Use: "strategies", Short: "Strategies with status and deployments", RunE: func(_ *cobra.Command, _ []string) error {
		s, err := client.Strategies()
		if err != nil {
			return err
		}
		deployments, _ := client.Deployments()
		emit(s, func() {
			fmt.Printf("%-38s %-28s %-15s %-8s %-10s %s\n", "ID", "STRATEGY", "FAMILY", "LATEST", "STATUS", "DEPLOYMENTS")
			for _, x := range s {
				var deps []string
				for _, d := range deployments {
					if d.StrategyID == x.ID {
						state := "enabled"
						if !d.Enabled {
							state = "paused"
						}
						deps = append(deps, fmt.Sprintf("%s(%s,%d instr)", d.Mode, state, len(d.InstrumentIDs)))
					}
				}
				fmt.Printf("%-38s %-28s %-15s v%-7d %-10s %s\n", x.ID, x.Slug, x.Family, x.LatestVersion, x.LatestStatus, strings.Join(deps, " "))
			}
		})
		return nil
	}}
}

func strategyCmd() *cobra.Command {
	return &cobra.Command{Use: "strategy <id>", Short: "Score breakdown, metrics and deployments of a strategy", Args: cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			s, err := client.Strategy(args[0])
			if err != nil {
				return err
			}
			versions, _ := client.StrategyVersions(s.ID)
			score, _ := client.Score(s.ID)
			deployments, _ := client.Deployments()
			var backtests []api.Backtest
			if len(versions) > 0 {
				backtests, _ = client.Backtests(versions[len(versions)-1].ID)
			}
			view := map[string]any{"strategy": s, "versions": versions, "score": score, "deployments": deployments, "backtests": backtests}
			emit(view, func() {
				fmt.Printf("%s (%s) latest v%d %s\n\n", s.Slug, s.Family, s.LatestVersion, s.LatestStatus)
				fmt.Println("VERSIONS")
				for _, v := range versions {
					fmt.Printf("  v%-3d %-10s %s\n", v.Version, v.Status, v.ChangeNote)
				}
				fmt.Println()
				fmt.Print(ui.RenderScore(score))
				fmt.Println("\nBACKTESTS (latest version)")
				for _, b := range backtests {
					if b.Metrics == nil {
						fmt.Printf("  %s %s\n", b.ID, b.Status)
						continue
					}
					pf := "—"
					if b.Metrics.ProfitFactor != nil {
						pf = fmt.Sprintf("%.2f", *b.Metrics.ProfitFactor)
					}
					fmt.Printf("  %s %s trades=%d win=%.0f%% exp=%+.2fR pf=%s maxDD=%.1fR net=%s warnings=%d\n", b.ID[:8], b.Status, b.Metrics.TotalTrades,
						b.Metrics.WinRate*100, b.Metrics.ExpectancyR, pf, b.Metrics.MaxDrawdownR, ui.Rupees(b.Metrics.NetPnl.Paise), len(b.Warnings))
				}
				fmt.Println("\nDEPLOYMENTS")
				for _, d := range deployments {
					if d.StrategyID == s.ID {
						state := "enabled"
						if !d.Enabled {
							state = "paused (" + d.PauseReason + ")"
						}
						fmt.Printf("  %s %s %d instrument(s) %s\n", d.ID, d.Mode, len(d.InstrumentIDs), state)
					}
				}
			})
			return nil
		}}
}

func signalsCmd() *cobra.Command {
	var status string
	cmd := &cobra.Command{Use: "signals", Short: "Signals (default: active)", RunE: func(_ *cobra.Command, _ []string) error {
		var (
			s   []api.Signal
			err error
		)
		if status == "" {
			s, err = client.ActiveSignals()
		} else {
			s, err = client.Signals(status)
		}
		if err != nil {
			return err
		}
		emit(s, func() {
			fmt.Printf("%-38s %-4s %10s %10s %10s %-10s %s\n", "ID", "SIDE", "REF", "STOP", "TARGET", "STATUS", "VALID UNTIL")
			for _, x := range s {
				fmt.Printf("%-38s %-4s %10.2f %10.2f %10.2f %-10s %s\n", x.ID, x.Side, x.ReferencePrice, x.Stop, x.Target, x.Status, x.ValidUntil)
			}
		})
		return nil
	}}
	cmd.Flags().StringVar(&status, "status", "", "filter by status (ACTIVE, PREPARED, EXECUTED, EXPIRED, SKIPPED)")
	return cmd
}

func executeCmd() *cobra.Command {
	var yes bool
	cmd := &cobra.Command{Use: "execute <signal-id>", Short: "Prepare a signal's order, show the risk checks and confirm", Args: cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			p, err := client.PrepareSignal(args[0])
			if err != nil {
				return err
			}
			fmt.Print(ui.RenderPrepared(p))
			if p.Risk.Outcome != "APPROVED" {
				return fmt.Errorf("risk %s; not executing", p.Risk.Outcome)
			}
			if !yes && !confirm("Execute? [y/N] ", "y") {
				fmt.Println("aborted")
				return nil
			}
			o, err := client.ExecuteSignal(args[0])
			if err != nil {
				return err
			}
			emit(o, func() { fmt.Printf("Order %s %s %d %s state=%s\n", o.ID, o.Side, o.Quantity, o.OrderType, o.State) })
			return nil
		}}
	cmd.Flags().BoolVarP(&yes, "yes", "y", false, "skip the confirmation prompt")
	return cmd
}

func skipCmd() *cobra.Command {
	var reason string
	cmd := &cobra.Command{Use: "skip <signal-id>", Short: "Skip a signal", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		s, err := client.SkipSignal(args[0], reason)
		if err != nil {
			return err
		}
		emit(s, func() { fmt.Printf("Signal %s %s (%s)\n", s.ID, s.Status, s.Note) })
		return nil
	}}
	cmd.Flags().StringVar(&reason, "reason", "skipped from TUI", "why")
	return cmd
}

func pulseCmd() *cobra.Command {
	return &cobra.Command{Use: "pulse", Short: "Technical and Market Pulse (what kind of market is today?)", RunE: func(_ *cobra.Command, _ []string) error {
		p, err := client.Pulse()
		if err != nil {
			return err
		}
		emit(p, func() { fmt.Print(ui.RenderPulse(p)) })
		return nil
	}}
}
