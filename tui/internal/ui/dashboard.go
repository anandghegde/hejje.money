package ui

import (
	"fmt"
	"strings"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"hejje.money/tui/internal/api"
)

type refreshMsg struct{}

type model struct {
	client    *api.Client
	health    api.Health
	positions []api.Position
	orders    []api.Order
	risk      api.RiskDashboard
	today     api.TodayView
	approvals []api.Approval
	prepared  *api.PreparedOrder
	details   bool
	err       string
	status    string
	quitting  bool
	selPos    int
	selOrder  int
}

// RunDashboard starts the Bubble Tea dashboard (PRD 5.4).
func RunDashboard(client *api.Client) error {
	p := tea.NewProgram(model{client: client}, tea.WithAltScreen())
	_, err := p.Run()
	return err
}

func (m model) Init() tea.Cmd {
	return tea.Batch(m.refresh(), tick())
}

func tick() tea.Cmd {
	return tea.Tick(2*time.Second, func(time.Time) tea.Msg { return refreshMsg{} })
}

func (m model) refresh() tea.Cmd {
	return func() tea.Msg {
		return loaded{
			health:    fetchHealth(m.client),
			positions: fetchPositions(m.client),
			orders:    fetchOrders(m.client),
			risk:      fetchRisk(m.client),
			today:     fetchToday(m.client),
			approvals: fetchApprovals(m.client),
		}
	}
}

type loaded struct {
	health    api.Health
	positions []api.Position
	orders    []api.Order
	risk      api.RiskDashboard
	today     api.TodayView
	approvals []api.Approval
}

func fetchHealth(c *api.Client) api.Health { h, _ := c.Health(); return h }
func fetchPositions(c *api.Client) []api.Position { p, _ := c.Positions(); return p }
func fetchOrders(c *api.Client) []api.Order { o, _ := c.Orders(); return o }
func fetchRisk(c *api.Client) api.RiskDashboard { r, _ := c.Risk(); return r }
func fetchToday(c *api.Client) api.TodayView     { t, _ := c.Today(); return t }

// fetchApprovals lists pending approvals; keys without orders:execute simply see none.
func fetchApprovals(c *api.Client) []api.Approval { a, _ := c.Approvals("PENDING"); return a }

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case refreshMsg:
		return m, tea.Batch(m.refresh(), tick())
	case loaded:
		m.health = msg.health
		m.positions = msg.positions
		m.orders = msg.orders
		m.risk = msg.risk
		m.today = msg.today
		m.approvals = msg.approvals
		return m, nil
	case tea.KeyMsg:
		// a prepared order waits for an explicit y/N
		if m.prepared != nil {
			switch msg.String() {
			case "y", "Y":
				if m.prepared.Risk.Outcome != "APPROVED" {
					m.status = "risk " + m.prepared.Risk.Outcome + "; not executed"
				} else if o, err := m.client.ExecuteSignal(m.prepared.Signal.ID); err != nil {
					m.status = "execute failed: " + err.Error()
				} else {
					m.status = "order " + o.ID + " " + o.State
				}
				m.prepared = nil
				return m, m.refresh()
			case "q", "ctrl+c":
				m.quitting = true
				return m, tea.Quit
			default:
				m.prepared = nil
				m.status = "execution cancelled"
				return m, nil
			}
		}
		switch msg.String() {
		case "q", "ctrl+c":
			m.quitting = true
			return m, tea.Quit
		case "E":
			if m.today.Best != nil && m.today.Best.SignalID != "" {
				p, err := m.client.PrepareSignal(m.today.Best.SignalID)
				if err != nil {
					m.status = "prepare failed: " + err.Error()
				} else {
					m.prepared = &p
				}
			}
			return m, nil
		case "D":
			m.details = !m.details
			return m, nil
		case "S":
			if m.today.Best != nil && m.today.Best.SignalID != "" {
				_, err := m.client.SkipSignal(m.today.Best.SignalID, "skipped from dashboard")
				if err != nil {
					m.status = "skip failed: " + err.Error()
				} else {
					m.status = "signal skipped"
				}
			}
			return m, m.refresh()
		case "r":
			return m, m.refresh()
		case "c":
			if o := m.selectedOpenOrder(); o != nil {
				_ = m.client.CancelOrder(o.ID)
				m.status = "cancel requested " + o.ID
			}
			return m, m.refresh()
		case "x":
			if p := m.selectedPosition(); p != nil {
				_, _ = m.client.ClosePosition(p.InstrumentID, p.Product)
				m.status = "close requested " + p.InstrumentID
			}
			return m, m.refresh()
		case "K":
			_, _ = m.client.KillSwitch("STOP_NEW_ORDERS", "")
			m.status = "kill switch: new orders stopped"
			return m, m.refresh()
		case "down":
			m.selPos++
		case "up":
			if m.selPos > 0 {
				m.selPos--
			}
		}
	}
	return m, nil
}

func (m model) selectedPosition() *api.Position {
	open := openPositions(m.positions)
	if m.selPos >= 0 && m.selPos < len(open) {
		return &open[m.selPos]
	}
	return nil
}

func (m model) selectedOpenOrder() *api.Order {
	open := openOrders(m.orders)
	if m.selOrder >= 0 && m.selOrder < len(open) {
		return &open[m.selOrder]
	}
	return nil
}

func openPositions(ps []api.Position) []api.Position {
	var out []api.Position
	for _, p := range ps {
		if p.NetQuantity != 0 {
			out = append(out, p)
		}
	}
	return out
}

func openOrders(os []api.Order) []api.Order {
	var out []api.Order
	for _, o := range os {
		switch o.State {
		case "OPEN", "PARTIALLY_FILLED", "BROKER_ACCEPTED", "TRIGGER_PENDING":
			out = append(out, o)
		}
	}
	return out
}

var (
	liveStyle  = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("196"))
	paperStyle = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("39"))
	dim        = lipgloss.NewStyle().Foreground(lipgloss.Color("244"))
)

func (m model) View() string {
	if m.quitting {
		return ""
	}
	var b strings.Builder
	mode := m.health.Mode
	if mode == "" {
		mode = "…"
	}
	banner := paperStyle.Render("● PAPER " + mode)
	if mode == "CONFIRM" || mode == "AUTO" {
		banner = liveStyle.Render("● LIVE " + mode)
	}
	b.WriteString(banner + "\n")
	b.WriteString(dim.Render("HEJJE") + "\n\n")

	if m.today.Best == nil && m.today.NoTrade == "" {
		b.WriteString("BEST HEJJE\n" + dim.Render("  No strategies deployed") + "\n\n")
	} else {
		b.WriteString(RenderBest(m.today))
		if m.details && m.today.Best != nil {
			b.WriteString(RenderDetails(*m.today.Best))
		}
		if m.prepared != nil {
			b.WriteString(RenderPrepared(*m.prepared))
			b.WriteString(liveStyle.Render("  Execute this order? [y/N]") + "\n")
		}
		b.WriteString("\n")
	}

	if n := len(m.approvals); n > 0 {
		b.WriteString(liveStyle.Render(fmt.Sprintf("⚑ %d approval(s) waiting — %s — run: hejje approvals", n, m.approvals[0].Summary)) + "\n\n")
	}

	b.WriteString("POSITIONS\n")
	for i, p := range openPositions(m.positions) {
		marker := "  "
		if i == m.selPos {
			marker = "> "
		}
		b.WriteString(fmt.Sprintf("%s%-38s %-4s net %5d avg %8.2f realized %s\n", marker, p.InstrumentID, p.Product, p.NetQuantity, p.AveragePrice, Rupees(p.RealizedPnl.Paise)))
	}
	if len(openPositions(m.positions)) == 0 {
		b.WriteString(dim.Render("  (none)") + "\n")
	}

	b.WriteString("\nOPEN ORDERS\n")
	for _, o := range openOrders(m.orders) {
		b.WriteString(fmt.Sprintf("  %-38s %-4s %5d %-8s %s\n", o.ID, o.Side, o.Quantity, o.OrderType, o.State))
	}
	if len(openOrders(m.orders)) == 0 {
		b.WriteString(dim.Render("  (none)") + "\n")
	}

	b.WriteString("\n")
	b.WriteString(fmt.Sprintf("Daily P&L %s / limit %s\n", Rupees(m.risk.NetPnl.Paise), Rupees(m.risk.DailyLossLimit.Paise)))
	b.WriteString(fmt.Sprintf("Server %s  Broker %s  Market %s\n", dotStatus(m.health.Status == "UP"), dotStatus(m.health.Broker.Status == "HEALTHY"), dotStatus(m.health.MarketData.Status != "DOWN")))
	if m.status != "" {
		b.WriteString(dim.Render(m.status) + "\n")
	}
	b.WriteString(dim.Render("[E] execute  [D] details  [S] skip  [c] cancel  [x] close  [K] kill  [r] refresh  [q] quit") + "\n")
	return b.String()
}

func dotStatus(ok bool) string {
	if ok {
		return "●"
	}
	return "○"
}
