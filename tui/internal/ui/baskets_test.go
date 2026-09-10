package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderBasketShowsLegsInPlacementOrderAndSplitsProgress(t *testing.T) {
	zero, one := 0, 1
	b := api.Basket{ID: "b1", Status: "ROLLED_BACK", Policy: "ALL_OR_NOTHING", Rollback: "CLOSE_FILLED_LEGS", Detail: "a leg failed; closed 1 of 1 filled leg(s)",
		Legs: []api.BasketLeg{
			{Sequence: 1, Side: "BUY", Quantity: 10, OrderType: "MARKET", Status: "ROLLED_BACK", ExecutionOrder: &one},
			{Sequence: 2, Side: "SELL", Quantity: 10, OrderType: "MARKET", HedgeFirst: true, Status: "FILLED", ExecutionOrder: &zero},
			{Sequence: 3, Side: "BUY", Quantity: 5, OrderType: "LIMIT", Status: "SKIPPED", Detail: "not placed: an earlier leg failed"}}}
	out := RenderBasket(b)
	for _, want := range []string{"BASKET b1  ROLLED_BACK  ALL_OR_NOTHING (rollback CLOSE_FILLED_LEGS)", "closed 1 of 1", "first", "not placed"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
	if strings.Index(out, "SELL") > strings.Index(out, "ROLLED_BACK   ") && strings.Index(out, "2   SELL") > strings.Index(out, "1   BUY") {
		t.Fatalf("hedge leg should come first\n%s", out)
	}
	if !strings.Contains(RenderBaskets([]api.Basket{b}), "1/3") {
		t.Fatal("list render")
	}
	s := []api.SplitOrder{{ID: "s1", Side: "BUY", Quantity: 300, Filled: 100, Children: 1, Status: "EXPIRED", Detail: "deadline passed"}}
	if !strings.Contains(RenderSplits(s), "100/300 (1)") || RenderSplits(nil) != "No split orders.\n" {
		t.Fatal("splits render")
	}
}
