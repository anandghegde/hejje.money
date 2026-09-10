package main

import (
	"fmt"
	"time"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/ui"
)

func notifyCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "notify", Short: "Notifications: the inbox, and a test through every channel (M5.5)"}
	var limit int
	list := &cobra.Command{Use: "list", Short: "Recent notifications, newest first (• = unread)", RunE: func(_ *cobra.Command, _ []string) error {
		n, err := client.Notifications(limit)
		if err != nil {
			return err
		}
		emit(n, func() { fmt.Print(ui.RenderNotifications(n, time.Now())) })
		return nil
	}}
	list.Flags().IntVar(&limit, "limit", 20, "how many notifications")
	test := &cobra.Command{Use: "test", Short: "Send a TEST notification through every enabled channel and show each delivery (admin)",
		RunE: func(_ *cobra.Command, _ []string) error {
			t, err := client.NotifyTest()
			if err != nil {
				return err
			}
			emit(t, func() { fmt.Print(ui.RenderNotificationTest(t)) })
			return nil
		}}
	cmd.AddCommand(list, test)
	return cmd
}
