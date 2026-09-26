package main

import (
	"fmt"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/ui"
)

// swingCmd shows the swing book (plan M11.6): positions with their GTTs, the overnight risk and today's setups.
func swingCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "swing",
		Short: "Swing book: delivery positions with their GTT stops, overnight risk, today's setups (PAPER only)",
		RunE: func(_ *cobra.Command, _ []string) error {
			positions, err := client.SwingPositions()
			if err != nil {
				return err
			}
			risk, err := client.SwingRisk()
			if err != nil {
				return err
			}
			setups, err := client.SwingSetups()
			if err != nil {
				return err
			}
			emit(map[string]any{"positions": positions, "risk": risk, "setups": setups}, func() {
				fmt.Print(ui.RenderSwingBook(positions, risk, setups))
			})
			return nil
		},
	}
	cmd.AddCommand(&cobra.Command{
		Use:   "close <symbol>",
		Short: "Exit one swing position at market (its GTT is cancelled in the same operation)",
		Args:  cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			if !confirm(fmt.Sprintf("Sell the whole swing position in %s at market? [y/N]: ", args[0]), "y") {
				fmt.Println("aborted")
				return nil
			}
			res, err := client.SwingClose(args[0])
			if err != nil {
				return err
			}
			emit(res, func() { fmt.Printf("exit order %v\n", res["orderId"]) })
			return nil
		},
	})
	return cmd
}
