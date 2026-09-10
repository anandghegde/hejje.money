package main

import (
	"fmt"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/ui"
)

func experimentsCmd() *cobra.Command {
	var version string
	cmd := &cobra.Command{Use: "experiments [id]", Short: "Strategy experiments (read-only): list, or one experiment's ranked variants", Args: cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			if len(args) == 1 {
				e, err := client.Experiment(args[0])
				if err != nil {
					return err
				}
				emit(e, func() { fmt.Print(ui.RenderExperiment(e)) })
				return nil
			}
			list, err := client.Experiments(version)
			if err != nil {
				return err
			}
			emit(list, func() { fmt.Print(ui.RenderExperiments(list)) })
			return nil
		}}
	cmd.Flags().StringVar(&version, "version", "", "only experiments on this strategy version id")
	return cmd
}
