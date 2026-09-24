package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"time"

	"github.com/spf13/cobra"
	"hejje.money/tui/internal/api"
	"hejje.money/tui/internal/ui"
)

// trackCmd streams one instrument and prints its price (and any open position's P&L) until Ctrl-C.
func trackCmd() *cobra.Command {
	var every time.Duration
	cmd := &cobra.Command{Use: "track <symbol>", Short: "Stream one instrument's live price and position P&L until Ctrl-C", Args: cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			symbol := strings.ToUpper(args[0])
			inst, err := client.ResolveInstrument(symbol)
			if err != nil {
				return err
			}
			if err := client.Subscribe(inst.ID); err != nil {
				return err
			}
			ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
			defer stop()
			tick := time.NewTicker(every)
			defer tick.Stop()
			var start float64
			for {
				quotes, err := client.Quotes(inst.ID)
				if err != nil {
					fmt.Fprintf(os.Stderr, "\n%v\n", err)
				} else if q, ok := quotes[inst.ID]; ok {
					if start == 0 {
						start = q.LastPrice
					}
					fmt.Printf("\r\033[K%s %s", time.Now().Format("15:04:05"), ui.RenderTrackLine(inst.HejjeSymbol, q, start, position(inst.ID)))
				}
				select {
				case <-ctx.Done():
					fmt.Println()
					return nil
				case <-tick.C:
				}
			}
		}}
	cmd.Flags().DurationVar(&every, "every", time.Second, "refresh interval")
	return cmd
}

// position is the open position in the instrument, if any (errors only hide the P&L).
func position(instrumentID string) *api.Position {
	ps, err := client.Positions()
	if err != nil {
		return nil
	}
	for i := range ps {
		if ps[i].InstrumentID == instrumentID && ps[i].NetQuantity != 0 {
			return &ps[i]
		}
	}
	return nil
}
