package main

import (
	"fmt"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/api"
	"hejje.money/tui/internal/ui"
)

func basketsCmd() *cobra.Command {
	return &cobra.Command{Use: "baskets [id]", Short: "Baskets (read-only): list, or one basket's legs", Args: cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			if len(args) == 1 {
				b, err := client.Basket(args[0])
				if err != nil {
					return err
				}
				emit(b, func() { fmt.Print(ui.RenderBasket(b)) })
				return nil
			}
			list, err := client.Baskets()
			if err != nil {
				return err
			}
			emit(list, func() { fmt.Print(ui.RenderBaskets(list)) })
			return nil
		}}
}

func splitsCmd() *cobra.Command {
	return &cobra.Command{Use: "splits", Short: "Split orders and their progress", RunE: func(_ *cobra.Command, _ []string) error {
		list, err := client.Splits()
		if err != nil {
			return err
		}
		emit(list, func() { fmt.Print(ui.RenderSplits(list)) })
		return nil
	}}
}

var _ = api.Basket{}
