package main

import (
	"github.com/spf13/cobra"

	"hejje.money/tui/internal/ui"
)

// harnessCmd opens the bot harness screen (plan M7.4): a SIM session with its replay controls, or the live PAPER/LIVE
// view of a bot without them.
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
	return cmd
}
