package main

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/ui"
)

func executorCmd() *cobra.Command {
	return &cobra.Command{Use: "executor", Short: "Is this server the active executor or a standby? (M5.6)", RunE: func(_ *cobra.Command, _ []string) error {
		s, err := client.Executor()
		if err != nil {
			return err
		}
		emit(s, func() { fmt.Print(ui.RenderExecutor(s)) })
		return nil
	}}
}

func failoverCmd() *cobra.Command {
	var yes bool
	cmd := &cobra.Command{Use: "failover", Short: "Controlled failover: release the executor lease on this (active) server so the standby takes over (admin)",
		RunE: func(_ *cobra.Command, _ []string) error {
			s, err := client.Executor()
			if err != nil {
				return err
			}
			if !jsonOut {
				fmt.Print(ui.RenderExecutor(s))
			}
			if s.Role != "ACTIVE" {
				return fmt.Errorf("this server is %s; run the failover against the active server", s.Role)
			}
			if !yes && !askYesNo(os.Stdin, os.Stdout, "Release the lease? This server stops sending orders and the standby takes over. [y/N] ") {
				fmt.Println("not failed over")
				return nil
			}
			r, err := client.Failover()
			if err != nil {
				return err
			}
			emit(r, func() { fmt.Println(r.Message) })
			return nil
		}}
	cmd.Flags().BoolVarP(&yes, "yes", "y", false, "skip the confirmation prompt")
	return cmd
}
