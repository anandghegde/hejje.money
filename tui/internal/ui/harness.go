package ui

import (
	"context"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"hejje.money/tui/internal/api"
)

// HarnessAPI is what the harness screen calls (the API client; a fake in tests).
type HarnessAPI interface {
	SimControl(sessionID, action, speed string, capitalRupees *int64) error
	SetBotEnabled(botID string, enabled bool) error
	KillSwitch(action, confirmation string) (api.KillSwitch, error)
}

// WideLayout is the terminal width from which the panels sit side by side; narrower terminals stack them.
const WideLayout = 160

// frameInterval caps redraws from the stream at ten a second, whatever the replay speed.
const frameInterval = 100 * time.Millisecond

var speeds = []string{"1", "10", "60", "300", "MAX"}

var panelNames = []string{"Equity", "Positions", "Candidates", "Trades", "Log", "Decisions"}

// SnapshotMsg carries a snapshot from the stream (or the initial GET).
type SnapshotMsg struct{ Snapshot api.HarnessSnapshot }

// StreamStatusMsg reports the stream's connection state.
type StreamStatusMsg struct{ Err error }

type frameMsg struct{}

type actionMsg struct {
	status string
	err    error
}

// Harness is the Bubble Tea model of `hejje harness` (plan M7.4).
type Harness struct {
	api            HarnessAPI
	snap           *api.HarnessSnapshot
	pending        *api.HarnessSnapshot
	width, height  int
	focus          int
	confirmKill    bool
	editingCapital bool
	capital        string
	status         string
	streamErr      string
	frames         int
	// readOnly shows a stored session report (plan M7.5): no stream, no controls
	readOnly bool
}

// NewHarness builds the model; the stream feeds it SnapshotMsg.
func NewHarness(a HarnessAPI) Harness {
	return Harness{api: a, width: 200, height: 50}
}

// NewHarnessReport shows a stored session report read-only.
func NewHarnessReport(s api.HarnessSnapshot) Harness {
	return Harness{snap: &s, readOnly: true, width: 200, height: 50}
}

func (m Harness) Init() tea.Cmd {
	return frame()
}

func frame() tea.Cmd {
	return tea.Tick(frameInterval, func(time.Time) tea.Msg { return frameMsg{} })
}

func (m Harness) sim() bool {
	return m.snap != nil && m.snap.Mode == "SIM" && m.snap.Session != nil
}

func (m Harness) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height
	case SnapshotMsg:
		s := msg.Snapshot
		m.pending = &s
		if m.snap == nil { // the first snapshot shows at once
			m.snap, m.pending = m.pending, nil
		}
	case frameMsg:
		if m.pending != nil {
			m.snap, m.pending = m.pending, nil
			m.frames++
		}
		return m, frame()
	case StreamStatusMsg:
		if msg.Err != nil {
			m.streamErr = "stream: " + msg.Err.Error() + " (reconnecting)"
		} else {
			m.streamErr = ""
		}
	case actionMsg:
		if msg.err != nil {
			m.status = "error: " + msg.err.Error()
		} else {
			m.status = msg.status
		}
	case tea.KeyMsg:
		return m.key(msg)
	}
	return m, nil
}

func (m Harness) key(k tea.KeyMsg) (tea.Model, tea.Cmd) {
	key := k.String()
	if m.confirmKill {
		m.confirmKill = false
		if key == "y" {
			return m, m.do("kill switch: new orders stopped", func() error {
				_, err := m.api.KillSwitch("STOP_NEW_ORDERS", "")
				return err
			})
		}
		m.status = "kill cancelled"
		return m, nil
	}
	if m.editingCapital {
		switch key {
		case "enter":
			m.editingCapital = false
			v, err := strconv.ParseInt(m.capital, 10, 64)
			if err != nil || v <= 0 {
				m.status = "capital must be a positive whole number of rupees"
				return m, nil
			}
			session := m.snap.Session.ID
			return m, m.do(fmt.Sprintf("capital set to ₹%s", hRupees(float64(v))), func() error { return m.api.SimControl(session, "", "", &v) })
		case "esc":
			m.editingCapital = false
			m.status = "capital unchanged"
		case "backspace":
			if len(m.capital) > 0 {
				m.capital = m.capital[:len(m.capital)-1]
			}
		default:
			if len(key) == 1 && key[0] >= '0' && key[0] <= '9' {
				m.capital += key
			}
		}
		return m, nil
	}
	switch key {
	case "q", "ctrl+c":
		return m, tea.Quit
	case "tab":
		m.focus = (m.focus + 1) % len(panelNames)
	}
	if m.readOnly {
		return m, nil
	}
	switch key {
	case "K":
		m.confirmKill = true
	case "p":
		if m.snap != nil && m.snap.Header.BotID != "" {
			enable := m.snap.Header.BotEnabled != nil && !*m.snap.Header.BotEnabled
			bot := m.snap.Header.BotID
			label := map[bool]string{true: "bot resumed", false: "bot paused"}[enable]
			return m, m.do(label, func() error { return m.api.SetBotEnabled(bot, enable) })
		}
	}
	if !m.sim() {
		return m, nil // replay controls exist only in SIM
	}
	session := m.snap.Session.ID
	switch key {
	case " ":
		action := "play"
		if m.snap.Session.State == "PLAYING" {
			action = "pause"
		}
		return m, m.do(action, func() error { return m.api.SimControl(session, action, "", nil) })
	case "s":
		return m, m.do("step", func() error { return m.api.SimControl(session, "step", "", nil) })
	case "1", "2", "3", "4", "5":
		speed := speeds[key[0]-'1']
		return m, m.do("speed "+speed, func() error { return m.api.SimControl(session, "", speed, nil) })
	case "c":
		m.editingCapital = true
		m.capital = ""
	}
	return m, nil
}

func (m Harness) do(label string, call func() error) tea.Cmd {
	return func() tea.Msg { return actionMsg{status: label, err: call()} }
}

// --- rendering ---

var (
	hTitle   = lipgloss.NewStyle().Bold(true)
	hDim     = lipgloss.NewStyle().Foreground(lipgloss.Color("244"))
	hWarn    = lipgloss.NewStyle().Foreground(lipgloss.Color("214")).Bold(true)
	hBadSim  = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("15")).Background(lipgloss.Color("25")).Padding(0, 1)
	hBadPap  = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("15")).Background(lipgloss.Color("28")).Padding(0, 1)
	hBadLive = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("15")).Background(lipgloss.Color("160")).Padding(0, 1)
	hBox     = lipgloss.NewStyle().Border(lipgloss.RoundedBorder()).BorderForeground(lipgloss.Color("240")).Padding(0, 1)
	hFocus   = hBox.BorderForeground(lipgloss.Color("39"))
)

func (m Harness) View() string {
	if m.snap == nil {
		return "hejje harness: waiting for the first snapshot… " + m.streamErr + "\n[q] quit"
	}
	s := m.snap
	w := max(m.width, 60)
	var rows []string
	rows = append(rows, m.header(w))
	if m.sim() {
		rows = append(rows, m.replayBar(w))
	}
	rows = append(rows, m.contextLine())
	rows = append(rows, m.tiles(w))
	used := lipgloss.Height(strings.Join(rows, "\n"))
	footer := m.footer()
	avail := max(m.height-used-lipgloss.Height(footer), 12)
	if w >= WideLayout {
		h := max(avail/3, 4)
		left, right := w*7/20, w-w*7/20
		rows = append(rows,
			lipgloss.JoinHorizontal(lipgloss.Top, m.panel(0, left, h, m.body(0, left-4, h-2)), m.panel(1, right, h, m.body(1, right-4, h-2))),
			lipgloss.JoinHorizontal(lipgloss.Top, m.panel(2, left, h, m.body(2, left-4, h-2)), m.panel(3, right, h, m.body(3, right-4, h-2))),
			lipgloss.JoinHorizontal(lipgloss.Top, m.panel(4, left, avail-2*h, m.body(4, left-4, avail-2*h-2)),
				m.panel(5, right, avail-2*h, m.body(5, right-4, avail-2*h-2))))
	} else {
		// stacked: every panel full width; the focused one ([tab]) in full, the others as a one-line summary
		const collapsed = 4 // border, title and one line
		for i := range panelNames {
			h := collapsed
			var body []string
			if i == m.focus {
				h = max(avail-collapsed*(len(panelNames)-1), 6)
				body = m.body(i, w-4, h-2)
			} else {
				body = []string{m.summary(i)}
			}
			rows = append(rows, m.panel(i, w, h, body))
		}
	}
	rows = append(rows, footer)
	_ = s
	return strings.Join(rows, "\n")
}

func (m Harness) body(i, width, height int) []string {
	switch i {
	case 0:
		return m.equity(width, height)
	case 1:
		return m.positions(width, height)
	case 2:
		return m.candidates(width, height)
	case 3:
		return m.trades(width, height)
	case 4:
		return m.logLines(width, height)
	default:
		return m.decisions(width, height)
	}
}

// summary is a collapsed panel's one line: the most recent or most relevant row.
func (m Harness) summary(i int) string {
	s := m.snap
	switch i {
	case 0:
		lines := m.equity(40, 3)
		return lines[len(lines)-1]
	case 1:
		if len(s.Positions) == 0 {
			return hDim.Render("flat")
		}
		p := s.Positions[0]
		more := ""
		if len(s.Positions) > 1 {
			more = fmt.Sprintf("  (+%d more)", len(s.Positions)-1)
		}
		return fmt.Sprintf("%s %s ×%d @ %s  LTP %s  open ₹%s  R %s%s", p.Symbol, p.Side, p.Quantity, hPrice(p.Entry), hPrice(p.Ltp), hRupees(p.OpenPnl),
			hNum(p.R, 2), more)
	case 2:
		for _, c := range s.Candidates {
			if c["sent"] == true {
				return fmt.Sprintf("sent ▶ %v %v %.2f  (%d candidates)", c["instrument"], c["side"], score(c), len(s.Candidates))
			}
		}
		return fmt.Sprintf("%d candidates", len(s.Candidates))
	case 3:
		if len(s.Trades) == 0 {
			return hDim.Render("no closed trades")
		}
		t := s.Trades[0]
		count := "1 trade"
		if len(s.Trades) != 1 {
			count = fmt.Sprintf("%d trades", len(s.Trades))
		}
		return fmt.Sprintf("%s %s %s ×%d  %s → %s  ₹%s  %s  (%s)", istClock(t.Time), t.Symbol, t.Side, t.Quantity, hPrice(t.Entry), hPrice(t.Exit),
			hRupees(t.Pnl), t.Why, count)
	case 4:
		lines := m.logLines(0, 0)
		return lines[0]
	default:
		if len(s.Decisions) == 0 {
			return hDim.Render("no decisions yet")
		}
		d := s.Decisions[0]
		return fmt.Sprintf("%s %s %s %s → %s", istClock(d.Time), d.Symbol, d.Action, hNum(d.Confidence, 2), d.Outcome)
	}
}

func (m Harness) panel(i, width, height int, body []string) string {
	style := hBox
	if i == m.focus {
		style = hFocus
	}
	inner := max(height-2, 1)
	if len(body) > inner {
		body = body[:inner]
	}
	for len(body) < inner {
		body = append(body, "")
	}
	for j, line := range body {
		body[j] = truncate(line, width-4)
	}
	title := hTitle.Render(panelNames[i])
	return style.Width(width - 2).Render(title + "\n" + strings.Join(body[:max(inner-1, 0)], "\n"))
}

func (m Harness) header(w int) string {
	s := m.snap
	badge := hBadPap.Render(s.Mode)
	switch s.Mode {
	case "SIM":
		badge = hBadSim.Render("SIM")
	case "CONFIRM", "AUTO":
		badge = hBadLive.Render("LIVE · " + s.Mode)
	}
	h := s.Header
	lat := "—"
	if h.LatencyP50Ms != nil && h.LatencyP90Ms != nil {
		lat = fmt.Sprintf("p50 %dms p90 %dms", *h.LatencyP50Ms, *h.LatencyP90Ms)
	}
	bot := h.Bot
	if h.BotEnabled != nil && !*h.BotEnabled {
		bot += " (paused)"
	}
	if h.BotID != "" && !h.Connected && h.BotKind != "STRATEGY" {
		bot += " (not connected)"
	}
	kill := ""
	if h.KillSwitch {
		kill = "  " + hWarn.Render("KILL SWITCH ON")
	}
	title := "HEJJE HARNESS"
	if m.readOnly {
		title = "HEJJE REPORT (read-only)"
	}
	line1 := fmt.Sprintf("%s %s  %s  fill: %s  %s  latency %s  skipped %d  %s", hTitle.Render(title), badge, hTitle.Render(bot),
		h.FillSource, h.DataHealth, lat, h.Skipped, istClock(s.Clock))
	line2 := hDim.Render(fmt.Sprintf("capital ₹%s", hMoney(s.Tiles.Capital))) + kill
	return truncate(line1, w) + "\n" + line2
}

func (m Harness) replayBar(w int) string {
	ss := m.snap.Session
	state := "▶ PLAYING"
	switch ss.State {
	case "PAUSED":
		state = "⏸ PAUSED"
	case "DONE", "FAILED", "CANCELLED":
		state = "■ " + ss.State
	}
	steps := max(ss.Steps, 1)
	barWidth := 30
	filled := min(ss.Step*barWidth/steps, barWidth)
	bar := strings.Repeat("█", filled) + strings.Repeat("░", barWidth-filled)
	var sp []string
	for i, v := range speeds {
		label := fmt.Sprintf("%d:%s", i+1, v)
		if v == ss.Speed {
			label = "[" + label + "]"
		}
		sp = append(sp, label)
	}
	controls := "  [space] play/pause [s] step [c] capital"
	if m.readOnly {
		controls = ""
	}
	line := fmt.Sprintf("REPLAY %s day %d/%d  %s  %s  %d/%d  speed %s%s", ss.SessionDate, ss.Day, ss.Days, state,
		bar, ss.Step, steps, strings.Join(sp, " "), controls)
	if len(ss.Warnings) > 0 {
		line += "\n" + hWarn.Render("⚠ "+strings.Join(ss.Warnings, "; "))
	}
	return truncate(line, w)
}

func (m Harness) contextLine() string {
	c := m.snap.Context
	next := time.Duration(c.NextDecisionInSeconds) * time.Second
	return hDim.Render(fmt.Sprintf("regime %s · pulse %s · next decision in %s", hOrDash(c.Regime), hOrDash(c.Pulse), next))
}

func (m Harness) tiles(w int) string {
	t := m.snap.Tiles
	tiles := [][2]string{
		{"Day P&L", hMoney(t.DayPnl)}, {"Open P&L", hMoney(t.OpenPnl)}, {"Total", hMoney(t.TotalPnl)}, {"Capital", hMoney(t.Capital)},
		{"In use", hMoney(t.InUse)}, {"Free", hMoney(t.Free)}, {"Risk/trade", hMoney(t.RiskPerTrade)}, {"Trades", strconv.Itoa(t.Trades)},
		{"Hit rate", hPct(t.HitRate)}, {"Expectancy", rMultiple(t.ExpectancyR)}, {"Profit factor", hNum(t.ProfitFactor, 2)}, {"Max DD", hMoney(t.MaxDrawdown)},
		{"Avg win", hMoney(t.AvgWin)}, {"Avg loss", hMoney(t.AvgLoss)}, {"Avg hold", hMinutes(t.AvgHoldMinutes)}, {"Friction", hMoney(t.FrictionPaid)},
		{"Loss halt", hMoney(t.LossHalt)}, {"LLM", llm(t.LlmTokens, t.LlmCostRupees)},
	}
	perRow := 9
	if w < WideLayout {
		perRow = 6
	}
	tileWidth := w/perRow - 1
	var lines []string
	for start := 0; start < len(tiles); start += perRow {
		var cells []string
		for _, tl := range tiles[start:min(start+perRow, len(tiles))] {
			cells = append(cells, lipgloss.NewStyle().Width(tileWidth).Render(hDim.Render(truncate(tl[0], tileWidth))+"\n"+hTitle.Render(truncate(tl[1], tileWidth))))
		}
		lines = append(lines, lipgloss.JoinHorizontal(lipgloss.Top, cells...))
	}
	return strings.Join(lines, "\n")
}

func (m Harness) equity(width, height int) []string {
	var values []float64
	for _, p := range m.snap.Equity {
		values = append(values, p.Equity)
	}
	if len(values) == 0 || height < 2 {
		return []string{hDim.Render("no equity yet")}
	}
	lo, hi := values[0], values[0]
	for _, v := range values {
		lo, hi = min(lo, v), max(hi, v)
	}
	out := Braille(values, max(width, 10), max(height-2, 1))
	return append(out, hDim.Render(fmt.Sprintf("last ₹%s  peak ₹%s  trough ₹%s", hRupees(values[len(values)-1]), hRupees(hi), hRupees(lo))))
}

func (m Harness) positions(width, _ int) []string {
	rows := [][]string{{"SYMBOL", "SIDE", "QTY", "ENTRY", "LTP", "STOP", "STOP@", "NOTIONAL", "OPEN P&L", "R", "MFE", "MAE"}}
	for _, p := range m.snap.Positions {
		rows = append(rows, []string{p.Symbol, p.Side, strconv.Itoa(p.Quantity), hPrice(p.Entry), hPrice(p.Ltp), hMoney(p.Stop), stopAt(p.StopLocation),
			hRupees(p.Notional), hRupees(p.OpenPnl), hNum(p.R, 2), hNum(p.Mfe, 2), hNum(p.Mae, 2)})
	}
	lines := table(rows)
	// each position's thesis on its own line under the table row
	for i := len(m.snap.Positions) - 1; i >= 0; i-- {
		if t := m.snap.Positions[i].Thesis; t != "" {
			at := i + 2
			lines = append(lines[:at], append([]string{hDim.Render("  ↳ " + t)}, lines[at:]...)...)
		}
	}
	if len(m.snap.Positions) == 0 {
		lines = append(lines, hDim.Render("flat"))
	}
	if len(m.snap.WorkingOrders) > 0 {
		lines = append(lines, hDim.Render("working orders"))
		for _, o := range m.snap.WorkingOrders {
			lines = append(lines, fmt.Sprintf("  %s %s %s ×%d trigger %s limit %s %s", o.Symbol, o.Side, o.Type, o.Quantity, hMoney(o.Trigger), hMoney(o.Limit), o.State))
		}
	}
	return lines
}

func (m Harness) candidates(_, _ int) []string {
	if len(m.snap.Candidates) == 0 {
		return []string{hDim.Render("the bot sent no candidates at its last decision point")}
	}
	var longs, shorts []map[string]any
	for _, c := range m.snap.Candidates {
		if strings.EqualFold(fmt.Sprint(c["side"]), "short") {
			shorts = append(shorts, c)
		} else {
			longs = append(longs, c)
		}
	}
	var out []string
	for _, group := range []struct {
		name string
		list []map[string]any
	}{{"long", longs}, {"short", shorts}} {
		if len(group.list) == 0 {
			continue
		}
		sort.SliceStable(group.list, func(i, j int) bool { return score(group.list[i]) > score(group.list[j]) })
		out = append(out, hDim.Render(group.name))
		for i, c := range group.list {
			mark := "  "
			if c["sent"] == true {
				mark = "▶ "
			}
			out = append(out, fmt.Sprintf("%s%d. %-14s %.2f", mark, i+1, fmt.Sprint(c["instrument"]), score(c)))
		}
	}
	return out
}

func (m Harness) trades(_, _ int) []string {
	rows := [][]string{{"TIME", "LEG", "SYMBOL", "SIDE", "QTY", "ENTRY", "EXIT", "P&L", "HOLD", "EXIT BY", "WHY", "DECISION"}}
	for _, t := range m.snap.Trades {
		hold := "—"
		if t.HoldMinutes != nil {
			hold = fmt.Sprintf("%dm", *t.HoldMinutes)
		}
		rows = append(rows, []string{istClock(t.Time), t.Leg, t.Symbol, t.Side, strconv.Itoa(t.Quantity), hPrice(t.Entry), hPrice(t.Exit), hRupees(t.Pnl), hold,
			t.ExitReason, t.Why, t.Attribution})
	}
	if len(m.snap.Trades) == 0 {
		return append(table(rows), hDim.Render("no closed trades"))
	}
	return table(rows)
}

func (m Harness) logLines(_, _ int) []string {
	var out []string
	if m.streamErr != "" {
		out = append(out, hWarn.Render(m.streamErr))
	}
	for _, l := range m.snap.Log {
		// server lines start with an instant: shown as IST time of day like the other panels
		if first, rest, ok := strings.Cut(l, " "); ok {
			l = istClock(first) + " " + rest
		}
		out = append(out, l)
	}
	if len(out) == 0 {
		return []string{hDim.Render("nothing yet")}
	}
	return out
}

func (m Harness) decisions(_, _ int) []string {
	rows := [][]string{{"TIME", "STAGE", "SYMBOL", "ACTION", "SCORES", "CONF", "LAT", "OUTCOME"}}
	for _, d := range m.snap.Decisions {
		lat := "—"
		if d.LatencyMs != nil {
			lat = fmt.Sprintf("%dms", *d.LatencyMs)
		}
		rows = append(rows, []string{istClock(d.Time), d.Stage, d.Symbol, d.Action, scores(d.Scores), hNum(d.Confidence, 2), lat, d.Outcome})
	}
	return table(rows)
}

func (m Harness) footer() string {
	var parts []string
	if m.confirmKill {
		parts = append(parts, hWarn.Render("Kill switch: stop all new orders? [y] yes, any other key cancels"))
	}
	if m.editingCapital {
		parts = append(parts, hWarn.Render("capital (₹, before the first step): "+m.capital+"▏ [enter] set [esc] cancel"))
	}
	keys := "[tab] panel  [p] pause bot  [K] kill  [q] quit"
	if m.readOnly {
		keys = "[tab] panel  [q] quit"
	} else if m.sim() {
		keys = "[space] play/pause  [1-5] speed  [s] step  [c] capital  " + keys
	}
	status := ""
	if m.status != "" {
		status = "  · " + m.status
	}
	parts = append(parts, hDim.Render(keys)+status)
	return strings.Join(parts, "\n")
}

// --- formatting ---

// stopAt abbreviates where the protective stop lives: at the exchange, simulated by the paper broker, or software only.
func stopAt(location string) string {
	switch location {
	case "exchange":
		return "exch"
	case "simulated":
		return "sim"
	case "software":
		return "soft"
	}
	return location
}

func table(rows [][]string) []string {
	widths := make([]int, len(rows[0]))
	for _, r := range rows {
		for i, c := range r {
			widths[i] = max(widths[i], lipgloss.Width(c))
		}
	}
	out := make([]string, len(rows))
	for j, r := range rows {
		cells := make([]string, len(r))
		for i, c := range r {
			cells[i] = c + strings.Repeat(" ", widths[i]-lipgloss.Width(c))
		}
		line := strings.TrimRight(strings.Join(cells, "  "), " ")
		if j == 0 {
			line = hDim.Render(line)
		}
		out[j] = line
	}
	return out
}

func truncate(s string, width int) string {
	if width <= 0 {
		return ""
	}
	lines := strings.Split(s, "\n")
	for i, l := range lines {
		if lipgloss.Width(l) > width {
			r := []rune(l)
			for lipgloss.Width(string(r)) > width-1 && len(r) > 0 {
				r = r[:len(r)-1]
			}
			lines[i] = string(r) + "…"
		}
	}
	return strings.Join(lines, "\n")
}

// rupees formats with Indian digit grouping: 1234567.8 → 12,34,567.80.
func hRupees(v float64) string {
	neg := v < 0
	if neg {
		v = -v
	}
	s := strconv.FormatFloat(v, 'f', 2, 64)
	whole, frac := s[:len(s)-3], s[len(s)-3:]
	if len(whole) > 3 {
		head, tail := whole[:len(whole)-3], whole[len(whole)-3:]
		var groups []string
		for len(head) > 2 {
			groups = append([]string{head[len(head)-2:]}, groups...)
			head = head[:len(head)-2]
		}
		if head != "" {
			groups = append([]string{head}, groups...)
		}
		whole = strings.Join(groups, ",") + "," + tail
	}
	if neg {
		return "-" + whole + frac
	}
	return whole + frac
}

func hMoney(v *float64) string {
	if v == nil {
		return "—"
	}
	return hRupees(*v)
}

func hPrice(v float64) string { return strconv.FormatFloat(v, 'f', 2, 64) }

func hNum(v *float64, places int) string {
	if v == nil {
		return "—"
	}
	return strconv.FormatFloat(*v, 'f', places, 64)
}

func hPct(v *float64) string {
	if v == nil {
		return "—"
	}
	return strconv.FormatFloat(*v*100, 'f', 1, 64) + "%"
}

func rMultiple(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%+.2fR", *v)
}

func hMinutes(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%.0fm", *v)
}

func llm(tokens *int64, cost *float64) string {
	if tokens == nil {
		return "—"
	}
	return fmt.Sprintf("%d tok ₹%s", *tokens, hMoney(cost))
}

func hOrDash(s string) string {
	if s == "" {
		return "—"
	}
	return s
}

func score(c map[string]any) float64 {
	switch v := c["score"].(type) {
	case float64:
		return v
	case int:
		return float64(v)
	}
	return 0
}

func scores(s map[string]any) string {
	if len(s) == 0 {
		return ""
	}
	keys := make([]string, 0, len(s))
	for k := range s {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var parts []string
	for _, k := range keys {
		parts = append(parts, fmt.Sprintf("%s=%v", k, s[k]))
	}
	return strings.Join(parts, " ")
}

// istClock shows an instant as IST time of day (HH:MM:SS); anything else unchanged.
func istClock(t string) string {
	ts, err := time.Parse(time.RFC3339, t)
	if err != nil {
		return t
	}
	return ts.In(time.FixedZone("IST", 5*3600+1800)).Format("15:04:05")
}

// RunReport opens a stored session report read-only (plan M7.5).
func RunReport(s api.HarnessSnapshot) error {
	_, err := tea.NewProgram(NewHarnessReport(s), tea.WithAltScreen()).Run()
	return err
}

// RunHarness runs the harness screen against a server: the first snapshot over REST, then the /ws/harness stream,
// reconnecting (with a fresh snapshot) after a drop.
func RunHarness(client *api.Client, botID, sessionID string) error {
	p := tea.NewProgram(NewHarness(client), tea.WithAltScreen())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		backoff := time.Second
		for ctx.Err() == nil {
			if s, err := client.HarnessSnapshot(botID, sessionID); err == nil {
				p.Send(SnapshotMsg{Snapshot: s})
			}
			err := api.StreamHarness(ctx, client, botID, sessionID, func(s api.HarnessSnapshot) {
				p.Send(SnapshotMsg{Snapshot: s})
				backoff = time.Second
			})
			if ctx.Err() != nil {
				return
			}
			p.Send(StreamStatusMsg{Err: err})
			time.Sleep(backoff)
			backoff = min(backoff*2, 15*time.Second)
		}
	}()
	_, err := p.Run()
	return err
}
