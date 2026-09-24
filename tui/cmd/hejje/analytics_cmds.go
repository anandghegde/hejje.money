package main

import (
	"fmt"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/ui"
)

// analyticsCmd groups analytics reports; `pace` is the plan M9.7 pace report.
func analyticsCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "analytics", Short: "Analytics reports"}
	var from, to, mode, strategy string
	pace := &cobra.Command{
		Use:   "pace",
		Short: "Expectancy by trades that day, by sequence in the day and by entry hour",
		RunE: func(_ *cobra.Command, _ []string) error {
			p, err := client.Pace(from, to, mode, strategy)
			if err != nil {
				return err
			}
			emit(p, func() { fmt.Print(ui.RenderPace(p)) })
			return nil
		},
	}
	pace.Flags().StringVar(&from, "from", "", "first date (YYYY-MM-DD; default the start of the month)")
	pace.Flags().StringVar(&to, "to", "", "last date (YYYY-MM-DD; default today)")
	pace.Flags().StringVar(&mode, "mode", "", "PAPER, CONFIRM, AUTO or SIM (default the server's mode)")
	pace.Flags().StringVar(&strategy, "strategy", "", "one strategy (slug or id)")
	cmd.AddCommand(pace)
	return cmd
}
