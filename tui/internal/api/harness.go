package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"

	"github.com/gorilla/websocket"
)

// HarnessSnapshot is one view of a bot at work (plan M7.4): GET /harness/snapshot and the /ws/harness stream.
type HarnessSnapshot struct {
	Mode          string            `json:"mode"`
	Clock         string            `json:"clock"`
	Session       *HarnessSession   `json:"session"`
	Header        HarnessHeader     `json:"header"`
	Context       HarnessContext    `json:"context"`
	Tiles         HarnessTiles      `json:"tiles"`
	Equity        []EquityPoint     `json:"equity"`
	Positions     []HarnessPosition `json:"positions"`
	WorkingOrders []HarnessOrder    `json:"workingOrders"`
	Candidates    []map[string]any  `json:"candidates"`
	Trades        []HarnessTrade    `json:"trades"`
	Decisions     []HarnessDecision `json:"decisions"`
	Log           []string          `json:"log"`
}

type HarnessSession struct {
	ID          string   `json:"id"`
	State       string   `json:"state"`
	Speed       string   `json:"speed"`
	SessionDate string   `json:"sessionDate"`
	Day         int      `json:"day"`
	Days        int      `json:"days"`
	Step        int      `json:"step"`
	Steps       int      `json:"steps"`
	ResultHash  string   `json:"resultHash"`
	Warnings    []string `json:"warnings"`
}

type HarnessHeader struct {
	Bot          string `json:"bot"`
	BotID        string `json:"botId"`
	BotKind      string `json:"botKind"`
	BotEnabled   *bool  `json:"botEnabled"`
	FillSource   string `json:"fillSource"`
	DataHealth   string `json:"dataHealth"`
	LatencyP50Ms *int64 `json:"latencyP50Ms"`
	LatencyP90Ms *int64 `json:"latencyP90Ms"`
	Skipped      int    `json:"skipped"`
	Connected    bool   `json:"connected"`
	KillSwitch   bool   `json:"killSwitch"`
}

type HarnessContext struct {
	Regime                string `json:"regime"`
	Pulse                 string `json:"pulse"`
	NextDecisionInSeconds int64  `json:"nextDecisionInSeconds"`
}

type HarnessTiles struct {
	DayPnl         *float64 `json:"dayPnl"`
	OpenPnl        *float64 `json:"openPnl"`
	TotalPnl       *float64 `json:"totalPnl"`
	Capital        *float64 `json:"capital"`
	InUse          *float64 `json:"inUse"`
	Free           *float64 `json:"free"`
	RiskPerTrade   *float64 `json:"riskPerTrade"`
	Trades         int      `json:"trades"`
	HitRate        *float64 `json:"hitRate"`
	ExpectancyR    *float64 `json:"expectancyR"`
	ProfitFactor   *float64 `json:"profitFactor"`
	MaxDrawdown    *float64 `json:"maxDrawdown"`
	AvgWin         *float64 `json:"avgWin"`
	AvgLoss        *float64 `json:"avgLoss"`
	AvgHoldMinutes *float64 `json:"avgHoldMinutes"`
	FrictionPaid   *float64 `json:"frictionPaid"`
	LossHalt       *float64 `json:"lossHalt"`
	LlmTokens      *int64   `json:"llmTokens"`
	LlmCostRupees  *float64 `json:"llmCostRupees"`
}

type EquityPoint struct {
	T      string  `json:"t"`
	Equity float64 `json:"equity"`
}

type HarnessPosition struct {
	Symbol       string   `json:"symbol"`
	Side         string   `json:"side"`
	Quantity     int      `json:"quantity"`
	Entry        float64  `json:"entry"`
	Ltp          float64  `json:"ltp"`
	Stop         *float64 `json:"stop"`
	StopLocation string   `json:"stopLocation"`
	Notional     float64  `json:"notional"`
	OpenPnl      float64  `json:"openPnl"`
	R            *float64 `json:"r"`
	Thesis       string   `json:"thesis"`
	Mfe          *float64 `json:"mfe"`
	Mae          *float64 `json:"mae"`
}

type HarnessOrder struct {
	Symbol   string   `json:"symbol"`
	Side     string   `json:"side"`
	Type     string   `json:"type"`
	Quantity int      `json:"quantity"`
	Trigger  *float64 `json:"trigger"`
	Limit    *float64 `json:"limit"`
	State    string   `json:"state"`
	Role     string   `json:"role"`
}

type HarnessTrade struct {
	Time        string   `json:"time"`
	Leg         string   `json:"leg"`
	Symbol      string   `json:"symbol"`
	Side        string   `json:"side"`
	Quantity    int      `json:"quantity"`
	Entry       float64  `json:"entry"`
	Exit        float64  `json:"exit"`
	Pnl         float64  `json:"pnl"`
	HoldMinutes *int64   `json:"holdMinutes"`
	Why         string   `json:"why"`
	ExitReason  string   `json:"exitReason"`
	Attribution string   `json:"attribution"`
}

type HarnessDecision struct {
	Time       string         `json:"time"`
	Stage      string         `json:"stage"`
	Symbol     string         `json:"symbol"`
	Action     string         `json:"action"`
	Scores     map[string]any `json:"scores"`
	Confidence *float64       `json:"confidence"`
	LatencyMs  *int64         `json:"latencyMs"`
	Outcome    string         `json:"outcome"`
}

// HarnessSnapshot fetches one snapshot; empty ids let the server choose (the active session, its first bot).
func (c *Client) HarnessSnapshot(botID, sessionID string) (HarnessSnapshot, error) {
	var s HarnessSnapshot
	q := url.Values{}
	if botID != "" {
		q.Set("bot", botID)
	}
	if sessionID != "" {
		q.Set("session", sessionID)
	}
	path := "/harness/snapshot"
	if len(q) > 0 {
		path += "?" + q.Encode()
	}
	return s, c.do(http.MethodGet, path, nil, false, &s)
}

// SimControl drives a SIM session: action play|pause|step|cancel, speed 1|10|60|300|MAX, capital (before the first step).
func (c *Client) SimControl(sessionID, action, speed string, capitalRupees *int64) error {
	body := map[string]any{}
	if action != "" {
		body["action"] = action
	}
	if speed != "" {
		body["speed"] = speed
	}
	if capitalRupees != nil {
		body["capitalRupees"] = *capitalRupees
	}
	return c.do(http.MethodPost, "/sim/sessions/"+sessionID+"/control", body, true, nil)
}

// SetBotEnabled pauses (false) or resumes (true) a bot.
func (c *Client) SetBotEnabled(botID string, enabled bool) error {
	return c.do(http.MethodPost, "/bots/"+botID+"/enabled", map[string]bool{"enabled": enabled}, true, nil)
}

// StreamHarness connects to /ws/harness and calls onSnapshot for every pushed snapshot until the context ends or the
// connection drops (the caller reconnects).
func StreamHarness(ctx context.Context, c *Client, botID, sessionID string, onSnapshot func(HarnessSnapshot)) error {
	q := url.Values{"token": {c.apiKey}}
	if botID != "" {
		q.Set("bot", botID)
	}
	if sessionID != "" {
		q.Set("session", sessionID)
	}
	dialer := *websocket.DefaultDialer
	dialer.HandshakeTimeout = 10 * time.Second
	conn, _, err := dialer.DialContext(ctx, fmt.Sprintf("%s/ws/harness?%s", c.BaseWSURL(), q.Encode()), nil)
	if err != nil {
		return err
	}
	defer conn.Close()
	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()
	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		var s HarnessSnapshot
		if json.Unmarshal(msg, &s) == nil {
			onSnapshot(s)
		}
	}
}

// SimReport is one bot's result in one finished SIM session (plan M7.5); Snapshot is the harness view at the end.
type SimReport struct {
	ID               string          `json:"id"`
	SessionID        string          `json:"sessionId"`
	BotName          string          `json:"botName"`
	BotVersion       string          `json:"botVersion"`
	BotKind          string          `json:"botKind"`
	SessionDates     []string        `json:"sessionDates"`
	CapitalPaise     int64           `json:"capitalPaise"`
	Trades           int             `json:"trades"`
	Wins             int             `json:"wins"`
	ExpectancyR      *float64        `json:"expectancyR"`
	ProfitFactor     *float64        `json:"profitFactor"`
	MaxDrawdownPaise int64           `json:"maxDrawdownPaise"`
	NetPnlPaise      int64           `json:"netPnlPaise"`
	FrictionPaise    int64           `json:"frictionPaise"`
	DecisionsHash    string          `json:"decisionsHash"`
	ResultHash       string          `json:"resultHash"`
	Snapshot         HarnessSnapshot `json:"snapshot"`
	CreatedAt        string          `json:"createdAt"`
}

// LeaderboardRow is one bot version's aggregate over its session reports.
type LeaderboardRow struct {
	Rank             int      `json:"rank"`
	Bot              string   `json:"bot"`
	Version          string   `json:"version"`
	Kind             string   `json:"kind"`
	Sessions         int      `json:"sessions"`
	Trades           int      `json:"trades"`
	WinRate          *float64 `json:"winRate"`
	ExpectancyR      *float64 `json:"expectancyR"`
	ProfitFactor     *float64 `json:"profitFactor"`
	MaxDrawdownPaise int64    `json:"maxDrawdownPaise"`
	NetPnlPaise      int64    `json:"netPnlPaise"`
	FrictionPaise    int64    `json:"frictionPaise"`
	Brier            *float64 `json:"brier"`
	BrierN           int      `json:"brierN"`
}

type Leaderboard struct {
	From           string           `json:"from"`
	To             string           `json:"to"`
	Common         bool             `json:"common"`
	MinSimSessions int              `json:"minSimSessions"`
	Rows           []LeaderboardRow `json:"rows"`
}

func (c *Client) SimReports(bot string, limit int) ([]SimReport, error) {
	var out []SimReport
	q := url.Values{"limit": {fmt.Sprint(limit)}}
	if bot != "" {
		q.Set("bot", bot)
	}
	return out, c.do(http.MethodGet, "/sim/reports?"+q.Encode(), nil, false, &out)
}

func (c *Client) SimReport(id string) (SimReport, error) {
	var r SimReport
	return r, c.do(http.MethodGet, "/sim/reports/"+id, nil, false, &r)
}

func (c *Client) Leaderboard(from, to string, common bool) (Leaderboard, error) {
	var l Leaderboard
	q := url.Values{"common": {fmt.Sprint(common)}}
	if from != "" {
		q.Set("from", from)
	}
	if to != "" {
		q.Set("to", to)
	}
	return l, c.do(http.MethodGet, "/sim/leaderboard?"+q.Encode(), nil, false, &l)
}
