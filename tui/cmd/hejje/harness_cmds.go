package main

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/api"
	"hejje.money/tui/internal/ui"
)

// harnessCmd opens the bot harness screen (plan M7.4): a SIM session with its replay controls, or the live PAPER/LIVE
// view of a bot without them. Its subcommands list session reports and show the leaderboard (plan M7.5).
func harnessCmd() *cobra.Command {
	var bot string
	cmd := &cobra.Command{
		Use:   "harness [session-id]",
		Short: "Watch a bot trade: replay controls in SIM, the same screen in PAPER and LIVE",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			session := ""
			if len(args) == 1 {
				session = args[0]
			}
			return ui.RunHarness(client, bot, session)
		},
	}
	cmd.Flags().StringVar(&bot, "bot", "", "bot id (default: the session's first bot, else the only enabled bot)")
	cmd.AddCommand(harnessSessionsCmd(), leaderboardCmd())
	return cmd
}

func harnessSessionsCmd() *cobra.Command {
	var bot string
	var limit int
	cmd := &cobra.Command{
		Use:   "sessions [report-id]",
		Short: "List SIM session reports, or open one read-only",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			if len(args) == 1 {
				r, err := client.SimReport(args[0])
				if err != nil {
					return err
				}
				if jsonOut {
					emit(r, func() {})
					return nil
				}
				return ui.RunReport(r.Snapshot)
			}
			reports, err := client.SimReports(bot, limit)
			if err != nil {
				return err
			}
			emit(reports, func() { printReports(reports) })
			return nil
		},
	}
	cmd.Flags().StringVar(&bot, "bot", "", "only this bot (name)")
	cmd.Flags().IntVar(&limit, "limit", 30, "how many reports")
	return cmd
}

func printReports(reports []api.SimReport) {
	w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
	fmt.Fprintln(w, "DATES\tBOT\tTRADES\tEXPECTANCY\tPF\tNET ₹\tFRICTION ₹\tMAX DD ₹\tREPORT")
	for _, r := range reports {
		dates := ""
		if len(r.SessionDates) > 0 {
			dates = r.SessionDates[0]
			if len(r.SessionDates) > 1 {
				dates += fmt.Sprintf(" +%d", len(r.SessionDates)-1)
			}
		}
		fmt.Fprintf(w, "%s\t%s v%s\t%d\t%s\t%s\t%.2f\t%.2f\t%.2f\t%s\n", dates, r.BotName, r.BotVersion, r.Trades, fmtR(r.ExpectancyR), fmtF(r.ProfitFactor),
			float64(r.NetPnlPaise)/100, float64(r.FrictionPaise)/100, float64(r.MaxDrawdownPaise)/100, r.ID)
	}
	_ = w.Flush()
}

func leaderboardCmd() *cobra.Command {
	var from, to string
	var common bool
	cmd := &cobra.Command{
		Use:   "leaderboard",
		Short: "Bots ranked by expectancy net of costs over their SIM sessions",
		RunE: func(_ *cobra.Command, _ []string) error {
			l, err := client.Leaderboard(from, to, common)
			if err != nil {
				return err
			}
			emit(l, func() {
				w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
				fmt.Fprintln(w, "#\tBOT\tKIND\tSESSIONS\tTRADES\tWIN %\tEXPECTANCY\tBRIER (N)\tPF\tMAX DD ₹\tNET ₹\tFRICTION ₹")
				for _, r := range l.Rows {
					win := "—"
					if r.WinRate != nil {
						win = fmt.Sprintf("%.0f", *r.WinRate*100)
					}
					brier := "—"
					if r.Brier != nil {
						brier = fmt.Sprintf("%.3f (%d)", *r.Brier, r.BrierN)
					}
					fmt.Fprintf(w, "%d\t%s v%s\t%s\t%d\t%d\t%s\t%s\t%s\t%s\t%.2f\t%.2f\t%.2f\n", r.Rank, r.Bot, r.Version, r.Kind, r.Sessions, r.Trades, win,
						fmtR(r.ExpectancyR), brier, fmtF(r.ProfitFactor), float64(r.MaxDrawdownPaise)/100, float64(r.NetPnlPaise)/100, float64(r.FrictionPaise)/100)
				}
				_ = w.Flush()
				fmt.Printf("PAPER needs %d SIM sessions with positive expectancy.\n", l.MinSimSessions)
			})
			return nil
		},
	}
	cmd.Flags().StringVar(&from, "from", "", "first session date (YYYY-MM-DD)")
	cmd.Flags().StringVar(&to, "to", "", "last session date (YYYY-MM-DD)")
	cmd.Flags().BoolVar(&common, "common", true, "compare only the sessions every ranked bot played")
	return cmd
}

func fmtR(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%+.3fR", *v)
}

func fmtF(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%.2f", *v)
}
