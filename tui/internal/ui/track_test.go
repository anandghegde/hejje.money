package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderTrackLineShowsChangeAndPosition(t *testing.T) {
	bid, ask := 1501.9, 1502.1
	q := api.Quote{LastPrice: 1502.0, Bid: &bid, Ask: &ask, Volume: 120345}
	out := RenderTrackLine("NSE:INFY", q, 1500.0, &api.Position{NetQuantity: -10, AveragePrice: 1505.0})
	for _, want := range []string{"NSE:INFY  1502.00", "+2.00 (+0.13%)", "bid 1501.90 ask 1502.10", "vol 120345", "pos -10 @ 1505.00  P&L +30.00"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in %s", want, out)
		}
	}
	bare := RenderTrackLine("NSE:INFY", api.Quote{LastPrice: 1490.0, Stale: true}, 1500.0, &api.Position{})
	for _, bad := range []string{"bid", "pos"} {
		if strings.Contains(bare, bad) {
			t.Fatalf("unexpected %q in %s", bad, bare)
		}
	}
	if !strings.Contains(bare, "-10.00 (-0.67%)") || !strings.Contains(bare, "STALE") {
		t.Fatalf("bad line %s", bare)
	}
}
