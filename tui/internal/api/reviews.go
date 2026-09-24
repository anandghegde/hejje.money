package api

import (
	"fmt"
	"net/http"
)

// TradeCause is a review's cause and entry timing (plan M9.6).
type TradeCause struct {
	Cause       string   `json:"cause"`
	EntryTiming *string  `json:"entryTiming"`
	MfeR        *float64 `json:"mfeR"`
	MaeR        *float64 `json:"maeR"`
	JevCause    *string  `json:"jevCause"`
	JevTiming   *string  `json:"jevTiming"`
	Complete    bool     `json:"complete"`
}

// TradeReview is one post-trade review (GET /reviews).
type TradeReview struct {
	ID           string      `json:"id"`
	InstrumentID string      `json:"instrumentId"`
	StrategyID   *string     `json:"strategyId"`
	Side         string      `json:"side"`
	Quantity     int         `json:"quantity"`
	ClosedAt     string      `json:"closedAt"`
	NetPnl       Paise       `json:"netPnl"`
	OutcomeR     *float64    `json:"outcomeR"`
	CloseReason  *string     `json:"closeReason"`
	Cause        *TradeCause `json:"cause"`
}

func (c *Client) Reviews(limit int) ([]TradeReview, error) {
	var out []TradeReview
	return out, c.do(http.MethodGet, fmt.Sprintf("/reviews?limit=%d", limit), nil, false, &out)
}
