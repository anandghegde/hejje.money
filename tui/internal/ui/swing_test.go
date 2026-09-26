package ui

import (
	"testing"

	"github.com/charmbracelet/x/exp/teatest"

	"hejje.money/tui/internal/api"
)

func flex(v float64) *api.Flex {
	f := api.Flex(v)
	return &f
}

func TestSwingBookGolden(t *testing.T) {
	positions := []api.SwingPosition{
		{Symbol: "NSE:INFY", EntryDate: "2026-12-01", DaysHeld: 3, Quantity: 233, EntryPrice: 100.7, LastPrice: 107, Stop: flex(93), Goal: flex(120),
			R: flex(0.82), UnrealizedPnl: api.Paise{Paise: 146790}, Gtt: "ACTIVE", GttID: "112127"},
		{Symbol: "NSE:TCS", EntryDate: "2026-11-20", DaysHeld: 11, Quantity: 40, EntryPrice: 3450, LastPrice: 3390, Stop: flex(3208.5), Goal: flex(4140),
			R: flex(-0.25), UnrealizedPnl: api.Paise{Paise: -240000}, Gtt: "MISSING"},
	}
	risk := api.SwingRisk{Mode: "PAPER", OvernightRisk: api.Paise{Paise: 1661690}, Budget: api.Paise{Paise: 1000000}, GapAllowancePct: 3, OpenPositions: 2,
		MaxOpenPositions: 6, Deployed: api.Paise{Paise: 16146310}, Capital: api.Paise{Paise: 50000000}}
	setups := []api.SwingSetup{
		{Symbol: "NSE:HDFCBANK", Type: "FLAT_BASE", Pivot: 1650, BuyHigh: 1732.5, Stop: 1534.5, Goal: 1980, State: "WAIT", LastClose: flex(1622.3), Pace: flex(0.8)},
		{Symbol: "NSE:SBIN", Type: "MA_REVERSAL", Pivot: 812, BuyHigh: 852.6, Stop: 798.1, Goal: 876.95, State: "WATCHING"},
	}
	teatest.RequireEqualOutput(t, []byte(RenderSwingBook(positions, risk, setups)))
}

func TestRiskShowsTheSwingOvernightRisk(t *testing.T) {
	n := 2
	r := api.RiskDashboard{OvernightRisk: &api.Paise{Paise: 36180}, OvernightRiskBudget: &api.Paise{Paise: 1000000}, SwingPositions: &n}
	if got := OvernightRiskLine(r); got != "Swing overnight risk 361.80 of 10000.00 (4%, gap-adjusted)  2 swing position(s)" {
		t.Fatalf("line: %q", got)
	}
	if OvernightRiskLine(api.RiskDashboard{}) != "" {
		t.Fatal("no line without a swing module")
	}
}
