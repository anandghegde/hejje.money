package ui

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/charmbracelet/x/exp/teatest"
	"github.com/muesli/termenv"

	"hejje.money/tui/internal/api"
)

type fakeHarnessAPI struct {
	controls []string
	enabled  []bool
	kills    int
}

func (f *fakeHarnessAPI) SimControl(sessionID, action, speed string, capital *int64) error {
	c := sessionID + " " + action + " " + speed
	if capital != nil {
		c += " capital"
	}
	f.controls = append(f.controls, strings.TrimSpace(c))
	return nil
}

func (f *fakeHarnessAPI) SetBotEnabled(_ string, enabled bool) error {
	f.enabled = append(f.enabled, enabled)
	return nil
}

func (f *fakeHarnessAPI) KillSwitch(_, _ string) (api.KillSwitch, error) {
	f.kills++
	return api.KillSwitch{StopNewOrders: true}, nil
}

func fixture(t *testing.T, name string) api.HarnessSnapshot {
	t.Helper()
	data, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	var s api.HarnessSnapshot
	if err := json.Unmarshal(data, &s); err != nil {
		t.Fatal(err)
	}
	return s
}

// render drives the model through teatest at a terminal size, feeds the fixture stream and returns the final screen.
func render(t *testing.T, width, height int, snaps ...api.HarnessSnapshot) string {
	t.Helper()
	lipgloss.SetColorProfile(termenv.Ascii)
	tm := teatest.NewTestModel(t, NewHarness(&fakeHarnessAPI{}), teatest.WithInitialTermSize(width, height))
	for _, s := range snaps {
		tm.Send(SnapshotMsg{Snapshot: s})
	}
	time.Sleep(3 * frameInterval) // let a frame apply the last snapshot
	tm.Send(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("q")})
	fm := tm.FinalModel(t, teatest.WithFinalTimeout(5*time.Second)).(Harness)
	return fm.View()
}

func TestHarnessGoldenWide(t *testing.T) {
	out := render(t, 200, 50, fixture(t, "harness_sim.json"))
	teatest.RequireEqualOutput(t, []byte(out))
	for _, want := range []string{"HEJJE HARNESS", "SIM", "REPLAY 2026-09-09", "42/375", "[3:60]", "p50 850ms p90 2100ms", "knowledge cutoff",
		"UP × NORMAL", "opening range holds above VWAP", "▶ 1. NSE:INFY", "gap fill failed", "MOVE_STOP", "10,00,000.00", "41200 tok"} {
		if !strings.Contains(out, want) {
			t.Errorf("wide render lacks %q", want)
		}
	}
	for _, line := range strings.Split(out, "\n") {
		if lipgloss.Width(line) > 200 {
			t.Fatalf("line wider than the terminal: %d", lipgloss.Width(line))
		}
	}
}

func TestHarnessGoldenNarrowStacksThePanels(t *testing.T) {
	out := render(t, 120, 40, fixture(t, "harness_sim.json"))
	teatest.RequireEqualOutput(t, []byte(out))
	for _, line := range strings.Split(out, "\n") {
		if lipgloss.Width(line) > 120 {
			t.Fatalf("line wider than the terminal: %d", lipgloss.Width(line))
		}
	}
	// stacked: every panel is there; the focused one (Equity) in full, the others as one-line summaries
	for _, want := range append(append([]string{}, panelNames...), "NSE:INFY BUY ×200 @ 1500.75", "sent ▶ NSE:INFY long 0.82", "gap fill failed",
		"10:10:00 MOVE_STOP NSE:INFY → MOVED", "MOVE_STOP 0.70 → MOVED") {
		if !strings.Contains(out, want) {
			t.Errorf("narrow render lacks %q", want)
		}
	}
}

func TestReplayControlsOnlyInSim(t *testing.T) {
	lipgloss.SetColorProfile(termenv.Ascii)
	fake := &fakeHarnessAPI{}
	var m tea.Model = NewHarness(fake)
	m, _ = m.Update(SnapshotMsg{Snapshot: fixture(t, "harness_paper.json")})
	if strings.Contains(m.View(), "REPLAY") || strings.Contains(m.View(), "[space]") {
		t.Fatal("PAPER shows replay controls")
	}
	for _, k := range []string{" ", "s", "3", "c"} {
		m, _ = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(k)})
	}
	if len(fake.controls) != 0 {
		t.Fatalf("replay keys acted outside SIM: %v", fake.controls)
	}

	m = NewHarness(fake)
	m, _ = m.Update(SnapshotMsg{Snapshot: fixture(t, "harness_sim.json")})
	for _, k := range []string{" ", "s", "5"} {
		var cmd tea.Cmd
		m, cmd = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(k)})
		m, _ = m.Update(cmd())
	}
	want := []string{"5975dbac-dfe8-4600-b88a-2f1f12c083f9 pause", "5975dbac-dfe8-4600-b88a-2f1f12c083f9 step",
		"5975dbac-dfe8-4600-b88a-2f1f12c083f9  MAX"}
	if strings.Join(fake.controls, "|") != strings.Join(want, "|") {
		t.Fatalf("controls %q, want %q", fake.controls, want)
	}
}

func TestKillAsksForConfirmation(t *testing.T) {
	lipgloss.SetColorProfile(termenv.Ascii)
	fake := &fakeHarnessAPI{}
	var m tea.Model = NewHarness(fake)
	m, _ = m.Update(SnapshotMsg{Snapshot: fixture(t, "harness_paper.json")})
	m, _ = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("K")})
	if !strings.Contains(m.View(), "Kill switch: stop all new orders?") || fake.kills != 0 {
		t.Fatal("K must ask first and not act")
	}
	m, _ = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("n")})
	if fake.kills != 0 || !strings.Contains(m.View(), "kill cancelled") {
		t.Fatal("any key other than y cancels")
	}
	m, _ = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("K")})
	m, cmd := m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("y")})
	m, _ = m.Update(cmd())
	if fake.kills != 1 || !strings.Contains(m.View(), "kill switch: new orders stopped") {
		t.Fatalf("y confirms: kills=%d", fake.kills)
	}
	// pause bot toggles the bot's enabled flag
	m, cmd = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("p")})
	m, _ = m.Update(cmd())
	if len(fake.enabled) != 1 || fake.enabled[0] {
		t.Fatalf("p pauses the running bot: %v", fake.enabled)
	}
}

func TestSnapshotsRedrawAtMostOncePerFrame(t *testing.T) {
	a, b := fixture(t, "harness_sim.json"), fixture(t, "harness_sim.json")
	b.Session.Step = 43
	var m tea.Model = NewHarness(&fakeHarnessAPI{})
	m, _ = m.Update(SnapshotMsg{Snapshot: a})
	m, _ = m.Update(SnapshotMsg{Snapshot: b})
	if m.(Harness).snap.Session.Step != 42 {
		t.Fatal("a snapshot arriving between frames waits for the next frame")
	}
	m, _ = m.Update(frameMsg{})
	if m.(Harness).snap.Session.Step != 43 {
		t.Fatal("the frame applies the latest snapshot")
	}
}

func TestBrailleAndRupees(t *testing.T) {
	got := Braille([]float64{0, 1, 2, 3}, 2, 1)
	if len(got) != 1 || len([]rune(got[0])) != 2 {
		t.Fatalf("braille shape %q", got)
	}
	if strings.Contains(got[0], "⠀") {
		t.Fatalf("a rising line leaves no empty cell: %q", got)
	}
	flat := Braille([]float64{5, 5, 5}, 3, 1)[0]
	if flat != "⠤⠤⠤" && flat != "⠒⠒⠒" {
		t.Fatalf("a flat line sits mid-height: %q", flat)
	}
	for in, want := range map[float64]string{1234567.8: "12,34,567.80", -51.61: "-51.61", 1000: "1,000.00", 100000: "1,00,000.00"} {
		if got := hRupees(in); got != want {
			t.Errorf("hRupees(%v) = %s, want %s", in, got, want)
		}
	}
}

// TestServerSnapshotDecodesAndRenders reads a snapshot the server produced (BotProtocolIT writes it to
// server/build/harness-snapshot.json; copied here) so the Go types stay in step with the server's JSON.
func TestServerSnapshotDecodesAndRenders(t *testing.T) {
	lipgloss.SetColorProfile(termenv.Ascii)
	s := fixture(t, "harness_server.json")
	if s.Mode != "SIM" || s.Session == nil || s.Session.Steps != 375 || s.Tiles.Capital == nil || s.Tiles.Trades != 1 || len(s.Trades) != 1 {
		t.Fatalf("server snapshot decoded badly: %+v", s)
	}
	var m tea.Model = NewHarness(&fakeHarnessAPI{})
	m, _ = m.Update(tea.WindowSizeMsg{Width: 200, Height: 50})
	m, _ = m.Update(SnapshotMsg{Snapshot: s})
	out := m.View()
	for _, want := range []string{"■ DONE", "375/375", "opening range holds", "NSE:INFY"} {
		if !strings.Contains(out, want) {
			t.Errorf("render of the server snapshot lacks %q", want)
		}
	}
}

func TestAStoredReportOpensReadOnly(t *testing.T) {
	lipgloss.SetColorProfile(termenv.Ascii)
	var m tea.Model = NewHarnessReport(fixture(t, "harness_server.json"))
	m, _ = m.Update(tea.WindowSizeMsg{Width: 200, Height: 50})
	out := m.View()
	if !strings.Contains(out, "HEJJE REPORT (read-only)") || strings.Contains(out, "[space]") || strings.Contains(out, "[K]") {
		t.Fatalf("a report shows no controls:\n%s", out)
	}
	for _, k := range []string{" ", "K", "p", "c", "s"} {
		var cmd tea.Cmd
		m, cmd = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(k)})
		if cmd != nil {
			t.Fatalf("key %q acted on a read-only report", k)
		}
	}
	if strings.Contains(m.View(), "Kill switch: stop all new orders?") {
		t.Fatal("no kill prompt on a report")
	}
}

func TestHarnessCandidatesShowImbalanceAndFlowWhenPresent(t *testing.T) {
	m := NewHarnessReport(api.HarnessSnapshot{})
	m.snap.Candidates = []map[string]any{
		{"instrument": "NSE:INFY", "side": "long", "score": 0.8, "sent": true, "imbalance": 0.31, "flowShare": 0.64},
		{"instrument": "NSE:TCS", "side": "long", "score": 0.4, "sent": false},
	}
	lines := strings.Join(m.candidates(80, 10), "\n")
	if !strings.Contains(lines, "imb +0.31  flow 64%") {
		t.Fatalf("micro columns missing:\n%s", lines)
	}
	if strings.Count(lines, "imb ") != 1 {
		t.Fatalf("a candidate without micro data shows it:\n%s", lines)
	}
}
