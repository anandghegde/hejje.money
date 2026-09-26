package api

import "net/http"

// The swing book (plan Phase 11): delivery positions held overnight, protected by a GTT at the broker. PAPER only.

// SwingPosition is one open position of the swing book (GET /swing/positions).
type SwingPosition struct {
	ID            string `json:"id"`
	InstrumentID  string `json:"instrumentId"`
	Symbol        string `json:"symbol"`
	EntryDate     string `json:"entryDate"`
	DaysHeld      int    `json:"daysHeld"`
	Quantity      int    `json:"quantity"`
	EntryPrice    Flex   `json:"entryPrice"`
	Stop          *Flex  `json:"stop"`
	Goal          *Flex  `json:"goal"`
	LastPrice     Flex   `json:"lastPrice"`
	R             *Flex  `json:"r"`
	UnrealizedPnl Paise  `json:"unrealizedPnl"`
	Gtt           string `json:"gtt"`
	GttID         string `json:"gttId"`
}

// SwingSetup is one READY trade plan the swing watcher watches today (GET /swing/setups).
type SwingSetup struct {
	Symbol    string `json:"symbol"`
	Type      string `json:"type"`
	Pivot     Flex   `json:"pivot"`
	BuyHigh   Flex   `json:"buyHigh"`
	Stop      Flex   `json:"stop"`
	Goal      Flex   `json:"goal"`
	State     string `json:"state"`
	LastClose *Flex  `json:"lastClose"`
	Pace      *Flex  `json:"pace"`
	SignalID  string `json:"signalId"`
}

// SwingPositionRisk is one position's gap-adjusted overnight risk.
type SwingPositionRisk struct {
	Symbol   string `json:"symbol"`
	Quantity int    `json:"quantity"`
	Price    Flex   `json:"price"`
	Stop     *Flex  `json:"stop"`
	Industry string `json:"industry"`
	Risk     Paise  `json:"risk"`
}

// SwingRisk is the swing book's overnight risk against its budget (GET /swing/risk).
type SwingRisk struct {
	Mode             string              `json:"mode"`
	OvernightRisk    Paise               `json:"overnightRisk"`
	Budget           Paise               `json:"budget"`
	GapAllowancePct  Flex                `json:"gapAllowancePct"`
	OpenPositions    int                 `json:"openPositions"`
	MaxOpenPositions int                 `json:"maxOpenPositions"`
	Deployed         Paise               `json:"deployed"`
	Capital          Paise               `json:"capital"`
	Positions        []SwingPositionRisk `json:"positions"`
}

func (c *Client) SwingPositions() ([]SwingPosition, error) {
	var p []SwingPosition
	return p, c.do(http.MethodGet, "/swing/positions", nil, false, &p)
}

func (c *Client) SwingSetups() ([]SwingSetup, error) {
	var s []SwingSetup
	return s, c.do(http.MethodGet, "/swing/setups", nil, false, &s)
}

func (c *Client) SwingRisk() (SwingRisk, error) {
	var r SwingRisk
	return r, c.do(http.MethodGet, "/swing/risk", nil, false, &r)
}

// SwingClose exits one swing position by symbol; its GTT is cancelled in the same operation.
func (c *Client) SwingClose(symbol string) (map[string]any, error) {
	var out map[string]any
	return out, c.do(http.MethodPost, "/swing/positions/close", map[string]string{"symbol": symbol}, true, &out)
}
