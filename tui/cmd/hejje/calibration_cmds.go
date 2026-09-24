package main

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/ui"
)

// calibrationCmd lists calibrated purposes, or prints one purpose's bucket table (plan M9.2).
func calibrationCmd() *cobra.Command {
	var version, bot, from, to string
	cmd := &cobra.Command{
		Use:   "calibration [purpose]",
		Short: "Hit rate per probability bucket for a Jev purpose or a bot's confidence (no argument: list purposes)",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			if len(args) == 0 && bot == "" {
				ps, err := client.CalibrationPurposes()
				if err != nil {
					return err
				}
				emit(ps, func() {
					w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
					fmt.Fprintln(w, "PURPOSE\tVERSION\tPREDICTIONS\tLABELLED\tFIRST\tLAST")
					for _, p := range ps {
						fmt.Fprintf(w, "%s\t%s\t%d\t%d\t%s\t%s\n", p.Purpose, p.Version, p.Predictions, p.Labelled, deref(p.First), deref(p.Last))
					}
					_ = w.Flush()
				})
				return nil
			}
			purpose := ""
			if len(args) == 1 {
				purpose = args[0]
			}
			r, err := client.Calibration(purpose, version, bot, from, to)
			if err != nil {
				return err
			}
			emit(r, func() { fmt.Print(ui.RenderCalibration(r)) })
			return nil
		},
	}
	cmd.Flags().StringVar(&version, "version", "", "question set or bot version (default: the newest)")
	cmd.Flags().StringVar(&bot, "bot", "", "a bot's entry confidence (purpose bot:<name>)")
	cmd.Flags().StringVar(&from, "from", "", "first session date (YYYY-MM-DD)")
	cmd.Flags().StringVar(&to, "to", "", "last session date (YYYY-MM-DD)")
	return cmd
}

func deref(s *string) string {
	if s == nil {
		return "—"
	}
	return *s
}
