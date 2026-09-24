package ui

import (
	"fmt"

	"hejje.money/tui/internal/api"
)

// RenderTrackLine is one refresh of `hejje track`: last price, change since tracking started, bid/ask when streamed in
// FULL mode, volume, and the open position with its unrealized P&L at the last price.
func RenderTrackLine(symbol string, q api.Quote, start float64, pos *api.Position) string {
	line := fmt.Sprintf("%s  %.2f", symbol, q.LastPrice)
	if start > 0 {
		d := q.LastPrice - start
		line += fmt.Sprintf("  %+.2f (%+.2f%%)", d, d/start*100)
	}
	if q.Bid != nil && q.Ask != nil {
		line += fmt.Sprintf("  bid %.2f ask %.2f", *q.Bid, *q.Ask)
	}
	line += fmt.Sprintf("  vol %d", q.Volume)
	if q.Stale {
		line += "  STALE"
	}
	if pos != nil && pos.NetQuantity != 0 {
		unrealized := (q.LastPrice - pos.AveragePrice) * float64(pos.NetQuantity)
		line += fmt.Sprintf("  | pos %+d @ %.2f  P&L %+.2f", pos.NetQuantity, pos.AveragePrice, unrealized)
	}
	return line
}
