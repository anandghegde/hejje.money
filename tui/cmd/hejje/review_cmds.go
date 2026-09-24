package main

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/spf13/cobra"
)

// reviewsCmd lists recent post-trade reviews with their cause and entry timing (plan M9.6).
func reviewsCmd() *cobra.Command {
	var limit int
	cmd := &cobra.Command{
		Use:   "reviews",
		Short: "Recent post-trade reviews with the trade's cause and entry timing",
		RunE: func(_ *cobra.Command, _ []string) error {
			rs, err := client.Reviews(limit)
			if err != nil {
				return err
			}
			emit(rs, func() {
				w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
				fmt.Fprintln(w, "CLOSED\tSIDE\tQTY\tNET ₹\tR\tREASON\tCAUSE\tTIMING\tMFE/MAE\tJEV")
				for _, r := range rs {
					cause, timing, excursion, jev := "—", "—", "—", "—"
					if r.Cause != nil {
						cause = r.Cause.Cause
						if !r.Cause.Complete {
							cause += "*"
						}
						timing = deref(r.Cause.EntryTiming)
						if r.Cause.MfeR != nil && r.Cause.MaeR != nil {
							excursion = fmt.Sprintf("%+.2f/%+.2f", *r.Cause.MfeR, *r.Cause.MaeR)
						}
						if r.Cause.JevCause != nil {
							jev = *r.Cause.JevCause + " " + deref(r.Cause.JevTiming)
						}
					}
					fmt.Fprintf(w, "%s\t%s\t%d\t%.2f\t%s\t%s\t%s\t%s\t%s\t%s\n", shortTime(r.ClosedAt), r.Side, r.Quantity, float64(r.NetPnl.Paise)/100,
						fmtR(r.OutcomeR), deref(r.CloseReason), cause, timing, excursion, jev)
				}
				_ = w.Flush()
				fmt.Println("* provisional: completed 35 minutes after the close (docs/analytics.md)")
			})
			return nil
		},
	}
	cmd.Flags().IntVar(&limit, "limit", 20, "number of reviews")
	return cmd
}

func shortTime(ts string) string {
	if len(ts) >= 16 {
		return ts[:16]
	}
	return ts
}
