package api

import (
	"bytes"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// Client is a typed Hejje API client. It authenticates with an API key (client credential) and sends a
// client-generated Idempotency-Key on every transactional request.
type Client struct {
	baseURL string
	apiKey  string
	http    *http.Client
}

func New(baseURL, apiKey string) *Client {
	return &Client{baseURL: baseURL + "/api/v1", apiKey: apiKey, http: &http.Client{Timeout: 10 * time.Second}}
}

// APIError carries the server's problem+json for a failed request.
type APIError struct {
	Status  int
	Detail  string
	Reasons []string
}

func (e *APIError) Error() string {
	if len(e.Reasons) > 0 {
		return fmt.Sprintf("%d: %s (%v)", e.Status, e.Detail, e.Reasons)
	}
	return fmt.Sprintf("%d: %s", e.Status, e.Detail)
}

func newIdempotencyKey() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func (c *Client) do(method, path string, body any, idempotent bool, out any) error {
	var reader io.Reader
	if body != nil {
		buf, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(buf)
	}
	req, err := http.NewRequest(method, c.baseURL+path, reader)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	if idempotent {
		req.Header.Set("Idempotency-Key", newIdempotencyKey())
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		var problem struct {
			Detail  string   `json:"detail"`
			Title   string   `json:"title"`
			Reasons []string `json:"reasons"`
		}
		_ = json.Unmarshal(data, &problem)
		detail := problem.Detail
		if detail == "" {
			detail = problem.Title
		}
		return &APIError{Status: resp.StatusCode, Detail: detail, Reasons: problem.Reasons}
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}

func (c *Client) Health() (Health, error) {
	var h Health
	return h, c.do(http.MethodGet, "/server/health", nil, false, &h)
}

func (c *Client) BrokerStatus() (BrokerStatus, error) {
	var b BrokerStatus
	return b, c.do(http.MethodGet, "/broker/status", nil, false, &b)
}

func (c *Client) Orders() ([]Order, error) {
	var o []Order
	return o, c.do(http.MethodGet, "/orders", nil, false, &o)
}

func (c *Client) Order(id string) (Order, error) {
	var view struct {
		Order Order `json:"order"`
	}
	err := c.do(http.MethodGet, "/orders/"+id, nil, false, &view)
	return view.Order, err
}

func (c *Client) Positions() ([]Position, error) {
	var p []Position
	return p, c.do(http.MethodGet, "/positions", nil, false, &p)
}

func (c *Client) Trades() ([]Trade, error) {
	var t []Trade
	return t, c.do(http.MethodGet, "/trades", nil, false, &t)
}

func (c *Client) Risk() (RiskDashboard, error) {
	var r RiskDashboard
	return r, c.do(http.MethodGet, "/risk", nil, false, &r)
}

func (c *Client) ResolveInstrument(symbol string) (Instrument, error) {
	var i Instrument
	return i, c.do(http.MethodGet, "/instruments/resolve?symbol="+url.QueryEscape(symbol), nil, false, &i)
}

// PlaceOrderRequest is the body of POST /orders/intents.
type PlaceOrderRequest struct {
	InstrumentID string  `json:"instrumentId"`
	Side         string  `json:"side"`
	Quantity     int     `json:"quantity"`
	OrderType    string  `json:"orderType"`
	Product      string  `json:"product"`
	LimitPrice   *string `json:"limitPrice,omitempty"`
	StopPrice    *string `json:"stopPrice,omitempty"`
	TargetPrice  *string `json:"targetPrice,omitempty"`
	Reason       string  `json:"reason"`
}

func (c *Client) PlaceOrder(req PlaceOrderRequest) (Order, error) {
	var o Order
	return o, c.do(http.MethodPost, "/orders/intents", req, true, &o)
}

func (c *Client) CancelOrder(id string) error {
	return c.do(http.MethodPost, "/orders/"+id+"/cancel", nil, true, nil)
}

func (c *Client) CancelAll() (map[string]any, error) {
	var m map[string]any
	return m, c.do(http.MethodPost, "/orders/cancel-all", nil, true, &m)
}

func (c *Client) ClosePosition(instrumentID, product string) (map[string]any, error) {
	var m map[string]any
	return m, c.do(http.MethodPost, "/positions/close", map[string]string{"instrumentId": instrumentID, "product": product}, true, &m)
}

func (c *Client) CloseAllPositions() (map[string]any, error) {
	var m map[string]any
	return m, c.do(http.MethodPost, "/positions/close-all", nil, true, &m)
}

func (c *Client) KillSwitch(action, confirmation string) (KillSwitch, error) {
	var k KillSwitch
	body := map[string]string{"action": action}
	if confirmation != "" {
		body["confirmation"] = confirmation
	}
	return k, c.do(http.MethodPost, "/risk/kill-switch", body, true, &k)
}

func (c *Client) BaseWSURL() string {
	u, err := url.Parse(c.baseURL)
	if err != nil {
		return ""
	}
	scheme := "ws"
	if u.Scheme == "https" {
		scheme = "wss"
	}
	return fmt.Sprintf("%s://%s", scheme, u.Host)
}

func (c *Client) APIKey() string { return c.apiKey }

// --- Phase 2 (M2.8) ---

func (c *Client) Today() (TodayView, error) {
	var t TodayView
	return t, c.do(http.MethodGet, "/today", nil, false, &t)
}

func (c *Client) Strategies() ([]Strategy, error) {
	var s []Strategy
	return s, c.do(http.MethodGet, "/strategies", nil, false, &s)
}

func (c *Client) Strategy(id string) (Strategy, error) {
	var s Strategy
	return s, c.do(http.MethodGet, "/strategies/"+url.PathEscape(id), nil, false, &s)
}

func (c *Client) StrategyVersions(id string) ([]StrategyVersion, error) {
	var v []StrategyVersion
	return v, c.do(http.MethodGet, "/strategies/"+url.PathEscape(id)+"/versions", nil, false, &v)
}

func (c *Client) Score(id string) (ScoreView, error) {
	var s ScoreView
	return s, c.do(http.MethodGet, "/strategies/"+url.PathEscape(id)+"/score", nil, false, &s)
}

func (c *Client) Deployments() ([]Deployment, error) {
	var d []Deployment
	return d, c.do(http.MethodGet, "/deployments", nil, false, &d)
}

func (c *Client) Backtests(versionID string) ([]Backtest, error) {
	var b []Backtest
	return b, c.do(http.MethodGet, "/backtests?versionId="+url.QueryEscape(versionID), nil, false, &b)
}

func (c *Client) Signals(status string) ([]Signal, error) {
	var s []Signal
	path := "/signals"
	if status != "" {
		path += "?status=" + url.QueryEscape(status)
	}
	return s, c.do(http.MethodGet, path, nil, false, &s)
}

func (c *Client) ActiveSignals() ([]Signal, error) {
	var s []Signal
	return s, c.do(http.MethodGet, "/signals/active", nil, false, &s)
}

// PrepareSignal sizes the order and dry-runs risk; nothing is submitted.
func (c *Client) PrepareSignal(id string) (PreparedOrder, error) {
	var p PreparedOrder
	return p, c.do(http.MethodPost, "/signals/"+url.PathEscape(id)+"/prepare", nil, false, &p)
}

// ExecuteSignal is the human confirmation: it submits the prepared order with a fresh Idempotency-Key.
func (c *Client) ExecuteSignal(id string) (Order, error) {
	var o Order
	return o, c.do(http.MethodPost, "/signals/"+url.PathEscape(id)+"/execute", nil, true, &o)
}

func (c *Client) SkipSignal(id, reason string) (Signal, error) {
	var s Signal
	return s, c.do(http.MethodPost, "/signals/"+url.PathEscape(id)+"/skip", map[string]string{"reason": reason}, false, &s)
}

// --- Phase 3 (M3.2) ---

func (c *Client) Pulse() (PulseSnapshot, error) {
	var p PulseSnapshot
	return p, c.do(http.MethodGet, "/context/pulse", nil, false, &p)
}
