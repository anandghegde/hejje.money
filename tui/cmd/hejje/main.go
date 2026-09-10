package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/api"
	"hejje.money/tui/internal/config"
	"hejje.money/tui/internal/ui"
)

var (
	jsonOut bool
	client  *api.Client
)

func main() {
	if err := rootCmd().Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func rootCmd() *cobra.Command {
	root := &cobra.Command{
		Use:   "hejje",
		Short: "Hejje terminal client",
		PersistentPreRunE: func(_ *cobra.Command, _ []string) error {
			cfg, err := config.Load()
			if err != nil {
				return err
			}
			client = api.New(cfg.ServerURL, cfg.APIKey)
			return nil
		},
		RunE: func(_ *cobra.Command, _ []string) error {
			return ui.RunDashboard(client)
		},
	}
	root.PersistentFlags().BoolVar(&jsonOut, "json", false, "output JSON for scripting")
	root.AddCommand(statusCmd(), positionsCmd(), ordersCmd(), orderCmd(), cancelCmd(), closeCmd(), closeAllCmd(),
		riskCmd(), brokerCmd(), serverCmd(), logsCmd(), killCmd(), orderPlaceCmd(),
		bestCmd(), strategiesCmd(), strategyCmd(), signalsCmd(), executeCmd(), skipCmd(), pulseCmd(), aiCmd())
	return root
}

func emit(v any, text func()) {
	if jsonOut {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(v)
		return
	}
	text()
}

func statusCmd() *cobra.Command {
	return &cobra.Command{Use: "status", Short: "Server and broker status", RunE: func(_ *cobra.Command, _ []string) error {
		h, err := client.Health()
		if err != nil {
			return err
		}
		emit(h, func() {
			fmt.Printf("Mode: %s  Execution: %v\n", h.Mode, h.ExecutionEnabled)
			fmt.Printf("Server %s | Broker %s | Market %s | DB %s | Clock %s | Risk %s\n",
				h.Status, h.Broker.Status, h.MarketData.Status, h.Database.Status, h.ClockSync.Status, h.RiskEngine.Status)
			if len(h.Reasons) > 0 {
				fmt.Println("Blocked:", strings.Join(h.Reasons, "; "))
			}
		})
		return nil
	}}
}

func serverCmd() *cobra.Command {
	return &cobra.Command{Use: "server", Short: "Server health", RunE: statusCmd().RunE}
}

func brokerCmd() *cobra.Command {
	return &cobra.Command{Use: "broker", Short: "Broker session status", RunE: func(_ *cobra.Command, _ []string) error {
		b, err := client.BrokerStatus()
		if err != nil {
			return err
		}
		emit(b, func() { fmt.Printf("%s %s (user %s, live=%v) — %s\n", b.Broker, b.State, b.BrokerUserID, b.LiveTradingEnabled, b.Detail) })
		return nil
	}}
}

func positionsCmd() *cobra.Command {
	return &cobra.Command{Use: "positions", Short: "Open positions", RunE: func(_ *cobra.Command, _ []string) error {
		p, err := client.Positions()
		if err != nil {
			return err
		}
		emit(p, func() {
			fmt.Printf("%-38s %-5s %6s %10s %12s\n", "INSTRUMENT", "PROD", "NET", "AVG", "REALIZED")
			for _, x := range p {
				fmt.Printf("%-38s %-5s %6d %10.2f %12s\n", x.InstrumentID, x.Product, x.NetQuantity, x.AveragePrice, ui.Rupees(x.RealizedPnl.Paise))
			}
		})
		return nil
	}}
}

func ordersCmd() *cobra.Command {
	return &cobra.Command{Use: "orders", Short: "Orders", RunE: func(_ *cobra.Command, _ []string) error {
		o, err := client.Orders()
		if err != nil {
			return err
		}
		emit(o, func() {
			fmt.Printf("%-38s %-4s %5s %5s %-8s %-16s\n", "ID", "SIDE", "QTY", "FILL", "TYPE", "STATE")
			for _, x := range o {
				fmt.Printf("%-38s %-4s %5d %5d %-8s %-16s\n", x.ID, x.Side, x.Quantity, x.Filled, x.OrderType, x.State)
			}
		})
		return nil
	}}
}

func orderCmd() *cobra.Command {
	return &cobra.Command{Use: "order <id>", Short: "Order detail", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		o, err := client.Order(args[0])
		if err != nil {
			return err
		}
		emit(o, func() { fmt.Printf("%s %s %d @ %s state=%s broker=%s\n", o.Side, o.OrderType, o.Quantity, o.OrderType, o.State, o.BrokerOrderID) })
		return nil
	}}
}

func cancelCmd() *cobra.Command {
	return &cobra.Command{Use: "cancel <id>", Short: "Cancel an order", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		if err := client.CancelOrder(args[0]); err != nil {
			return err
		}
		fmt.Println("cancel requested")
		return nil
	}}
}

func closeCmd() *cobra.Command {
	product := "MIS"
	cmd := &cobra.Command{Use: "close <instrument>", Short: "Close a position", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		inst, err := client.ResolveInstrument(args[0])
		if err != nil {
			return err
		}
		res, err := client.ClosePosition(inst.ID, product)
		if err != nil {
			return err
		}
		emit(res, func() { fmt.Println("close requested:", res) })
		return nil
	}}
	cmd.Flags().StringVar(&product, "product", "MIS", "product (MIS/CNC/NRML)")
	return cmd
}

func closeAllCmd() *cobra.Command {
	return &cobra.Command{Use: "close-all", Short: "Close all positions", RunE: func(_ *cobra.Command, _ []string) error {
		if !confirm("Close ALL positions? type CLOSE ALL: ", "CLOSE ALL") {
			fmt.Println("aborted")
			return nil
		}
		res, err := client.KillSwitch("CLOSE_ALL_POSITIONS", "CLOSE ALL")
		if err != nil {
			return err
		}
		emit(res, func() { fmt.Println("close-all requested") })
		return nil
	}}
}

func riskCmd() *cobra.Command {
	return &cobra.Command{Use: "risk", Short: "Risk dashboard", RunE: func(_ *cobra.Command, _ []string) error {
		r, err := client.Risk()
		if err != nil {
			return err
		}
		emit(r, func() {
			fmt.Printf("Realized %s  Unrealized %s  Net %s\n", ui.Rupees(r.RealizedPnl.Paise), ui.Rupees(r.UnrealizedPnl.Paise), ui.Rupees(r.NetPnl.Paise))
			fmt.Printf("Daily loss limit %s  Open %d/%d  Trades %d/%d  Margin %.1f%%  Kill=%v\n",
				ui.Rupees(r.DailyLossLimit.Paise), r.OpenPositions, r.MaxOpenPositions, r.TradesToday, r.MaxTradesPerDay, r.MarginUsedPct, r.KillSwitchStopNewOrders)
		})
		return nil
	}}
}

func killCmd() *cobra.Command {
	var cancelAll, closeAll bool
	cmd := &cobra.Command{Use: "kill", Short: "Kill switch", RunE: func(_ *cobra.Command, _ []string) error {
		action := "STOP_NEW_ORDERS"
		confirmation := ""
		if closeAll {
			action = "CLOSE_ALL_POSITIONS"
			if !confirm("Close ALL positions and stop new orders? type CLOSE ALL: ", "CLOSE ALL") {
				fmt.Println("aborted")
				return nil
			}
			confirmation = "CLOSE ALL"
		} else if cancelAll {
			action = "CANCEL_ALL_OPEN"
			if !confirm("Cancel all open orders and stop new orders? [y/N]: ", "y") {
				fmt.Println("aborted")
				return nil
			}
		} else if !confirm("Stop new orders? [y/N]: ", "y") {
			fmt.Println("aborted")
			return nil
		}
		res, err := client.KillSwitch(action, confirmation)
		if err != nil {
			return err
		}
		emit(res, func() { fmt.Printf("kill switch: %s stopNewOrders=%v\n", action, res.StopNewOrders) })
		return nil
	}}
	cmd.Flags().BoolVar(&cancelAll, "cancel-all", false, "also cancel all open orders")
	cmd.Flags().BoolVar(&closeAll, "close-all", false, "also close all positions")
	return cmd
}

func orderPlaceCmd() *cobra.Command {
	var instrument, side, orderType, price, stop, target string
	var qty int
	var risk float64
	var yes bool
	cmd := &cobra.Command{Use: "order-place", Aliases: []string{"place"}, Short: "Place an order", RunE: func(_ *cobra.Command, _ []string) error {
		inst, err := client.ResolveInstrument(instrument)
		if err != nil {
			return err
		}
		quantity := qty
		if quantity == 0 && risk > 0 && price != "" && stop != "" {
			p, _ := strconv.ParseFloat(price, 64)
			s, _ := strconv.ParseFloat(stop, 64)
			if p != s {
				units := int(risk / abs(p-s))
				quantity = (units / inst.LotSize) * inst.LotSize
			}
		}
		if quantity <= 0 {
			return fmt.Errorf("quantity resolves to 0; pass --qty or a valid --risk/--price/--stop")
		}
		if !yes && !confirm(fmt.Sprintf("Place %s %d %s %s? [y/N]: ", side, quantity, inst.HejjeSymbol, orderType), "y") {
			fmt.Println("aborted")
			return nil
		}
		req := api.PlaceOrderRequest{InstrumentID: inst.ID, Side: strings.ToUpper(side), Quantity: quantity,
			OrderType: strings.ToUpper(orderType), Product: "MIS", Reason: "MANUAL"}
		if price != "" {
			req.LimitPrice = &price
		}
		if stop != "" {
			req.StopPrice = &stop
		}
		if target != "" {
			req.TargetPrice = &target
		}
		o, err := client.PlaceOrder(req)
		if err != nil {
			return err
		}
		emit(o, func() { fmt.Printf("order %s state=%s\n", o.ID, o.State) })
		return nil
	}}
	cmd.Flags().StringVar(&instrument, "instrument", "", "Hejje symbol")
	cmd.Flags().StringVar(&side, "side", "BUY", "BUY or SELL")
	cmd.Flags().IntVar(&qty, "qty", 0, "quantity")
	cmd.Flags().Float64Var(&risk, "risk", 0, "risk in rupees (with --price and --stop)")
	cmd.Flags().StringVar(&orderType, "type", "MARKET", "MARKET/LIMIT/SL/SL_M")
	cmd.Flags().StringVar(&price, "price", "", "limit price")
	cmd.Flags().StringVar(&stop, "stop", "", "stop price")
	cmd.Flags().StringVar(&target, "target", "", "target price")
	cmd.Flags().BoolVar(&yes, "yes", false, "skip confirmation")
	_ = cmd.MarkFlagRequired("instrument")
	return cmd
}

func logsCmd() *cobra.Command {
	return &cobra.Command{Use: "logs", Short: "Stream events (/ws/events)", RunE: func(cmd *cobra.Command, _ []string) error {
		return api.StreamEvents(cmd.Context(), client, func(line string) { fmt.Println(line) })
	}}
}

func confirm(prompt, expected string) bool {
	fmt.Print(prompt)
	reader := bufio.NewReader(os.Stdin)
	line, _ := reader.ReadString('\n')
	line = strings.TrimSpace(line)
	if expected == "y" {
		return strings.EqualFold(line, "y") || strings.EqualFold(line, "yes")
	}
	return line == expected
}

func abs(f float64) float64 {
	if f < 0 {
		return -f
	}
	return f
}
