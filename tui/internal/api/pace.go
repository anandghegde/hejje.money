package api

import (
	"net/http"
	"net/url"
)

// PaceRow is one bucket of the pace report (plan M9.7); rates are nil without trades.
type PaceRow struct {
	Bucket           string   `json:"bucket"`
	Trades           int      `json:"trades"`
	Wins             int      `json:"wins"`
	WinRate          *float64 `json:"winRate"`
	ExpectancyR      *float64 `json:"expectancyR"`
	WithR            int      `json:"withR"`
	ExpectancyRupees *float64 `json:"expectancyRupees"`
	NetPnl           float64  `json:"netPnl"`
}

type PaceReport struct {
	Mode            string    `json:"mode"`
	From            string    `json:"from"`
	To              string    `json:"to"`
	Trades          int       `json:"trades"`
	ByTradesThatDay []PaceRow `json:"byTradesThatDay"`
	BySequence      []PaceRow `json:"bySequence"`
	ByHour          []PaceRow `json:"byHour"`
}

func (c *Client) Pace(from, to, mode, strategy string) (PaceReport, error) {
	var r PaceReport
	q := url.Values{}
	for k, v := range map[string]string{"from": from, "to": to, "mode": mode, "strategy": strategy} {
		if v != "" {
			q.Set(k, v)
		}
	}
	return r, c.do(http.MethodGet, "/analytics/pace?"+q.Encode(), nil, false, &r)
}
