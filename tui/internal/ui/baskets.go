package ui

import (
	"fmt"
	"sort"
	"strings"

	"hejje.money/tui/internal/api"
)

// RenderBaskets lists baskets with their outcome.
func RenderBaskets(list []api.Basket) string {
	if len(list) == 0 {
		return "No baskets.\n"
	}
	var b strings.Builder
	b.WriteString(fmt.Sprintf("%-38s %-16s %-12s %-6s %s\n", "ID", "POLICY", "STATUS", "LEGS", "DETAIL"))
	for _, x := range list {
		b.WriteString(fmt.Sprintf("%-38s %-16s %-12s %-6s %s\n", x.ID, x.Policy, x.Status, legCount(x), x.Detail))
	}
	return b.String()
}

// RenderBasket prints one basket's legs in placement order (hedges first).
func RenderBasket(x api.Basket) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("BASKET %s  %s  %s (rollback %s)\n", x.ID, x.Status, x.Policy, x.Rollback))
	if x.Detail != "" {
		b.WriteString("  " + x.Detail + "\n")
	}
	legs := append([]api.BasketLeg(nil), x.Legs...)
	sort.SliceStable(legs, func(i, j int) bool { return order(legs[i]) < order(legs[j]) })
	b.WriteString(fmt.Sprintf("\n%-3s %-4s %6s %-7s %-6s %-12s %s\n", "#", "SIDE", "QTY", "TYPE", "HEDGE", "STATUS", "DETAIL"))
	for _, l := range legs {
		hedge := ""
		if l.HedgeFirst {
			hedge = "first"
		}
		b.WriteString(fmt.Sprintf("%-3d %-4s %6d %-7s %-6s %-12s %s\n", l.Sequence, l.Side, l.Quantity, l.OrderType, hedge, l.Status, l.Detail))
	}
	return b.String()
}

// RenderSplits prints split progress.
func RenderSplits(list []api.SplitOrder) string {
	if len(list) == 0 {
		return "No split orders.\n"
	}
	var b strings.Builder
	b.WriteString(fmt.Sprintf("%-38s %-4s %-12s %-10s %s\n", "ID", "SIDE", "FILLED", "STATUS", "DETAIL"))
	for _, s := range list {
		b.WriteString(fmt.Sprintf("%-38s %-4s %-12s %-10s %s\n", s.ID, s.Side, fmt.Sprintf("%d/%d (%d)", s.Filled, s.Quantity, s.Children), s.Status, s.Detail))
	}
	return b.String()
}

func legCount(x api.Basket) string {
	filled := 0
	for _, l := range x.Legs {
		if l.Status == "FILLED" {
			filled++
		}
	}
	return fmt.Sprintf("%d/%d", filled, len(x.Legs))
}

func order(l api.BasketLeg) int {
	if l.ExecutionOrder != nil {
		return *l.ExecutionOrder
	}
	if l.HedgeFirst {
		return 100 + l.Sequence
	}
	return 1000 + l.Sequence
}
