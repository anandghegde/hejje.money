package api

import (
	"net/http"
	"net/url"
	"strconv"
	"time"
)

// Daily context (plan Phase 8): ratings, bases, lists, the screener, the watchlist and historical analogs.
// Prices arrive as JSON strings or numbers depending on the type; Flex accepts both.

// DailyRating is one stock's price/volume ratings for a session.
type DailyRating struct {
	SessionDate    string        `json:"sessionDate"`
	Symbol         string        `json:"symbol"`
	RsRating       *int          `json:"rsRating"`
	AdGrade        string        `json:"adGrade"`
	TechComposite  *int          `json:"techComposite"`
	OffHighPct     *float64      `json:"offHighPct"`
	OffLowPct      *float64      `json:"offLowPct"`
	VolVsAvg50Pct  *float64      `json:"volVsAvg50Pct"`
	UpDownVolRatio *float64      `json:"upDownVolRatio"`
	AvgTurnoverCr  *float64      `json:"avgTurnoverCr"`
	Close          Flex          `json:"close"`
	ChangePct      *float64      `json:"changePct"`
	GroupID        string        `json:"groupId"`
	GroupRank      *int          `json:"groupRank"`
	Surveillance   *Surveillance `json:"surveillance"`
}

// Surveillance is a stock's NSE ASM/GSM measure as of the session (flag NONE, ASM_LT_n, ASM_ST_n or GSM_n).
type Surveillance struct {
	Flag  string `json:"flag"`
	Code  string `json:"code"`
	AsOf  string `json:"asOf"`
	Stale bool   `json:"stale"`
}

// Base is a detected base with its informational trade plan and its status as of a session.
type Base struct {
	Symbol          string         `json:"symbol"`
	Type            string         `json:"type"`
	StartDate       string         `json:"startDate"`
	DetectedDate    string         `json:"detectedDate"`
	DepthPct        float64        `json:"depthPct"`
	Pivot           Flex           `json:"pivot"`
	BuyHigh         Flex           `json:"buyHigh"`
	Stop            Flex           `json:"stop"`
	Goal            Flex           `json:"goal"`
	Status          string         `json:"status"`
	StatusDate      string         `json:"statusDate"`
	TriggerDate     string         `json:"triggerDate"`
	VolumeConfirmed *bool          `json:"volumeConfirmed"`
	OutcomePct      *float64       `json:"outcomePct"`
	OutcomeR        *float64       `json:"outcomeR"`
	Evidence        map[string]any `json:"evidence"`
}

// SetupRow is a row of a list: the ratings and, for the setup lists, the base.
type SetupRow struct {
	Rating *DailyRating `json:"rating"`
	Base   *Base        `json:"base"`
}

// GroupRank is an industry group's rank for a session.
type GroupRank struct {
	Name     string  `json:"name"`
	Rank     int     `json:"rank"`
	Strength float64 `json:"strength"`
	Members  int     `json:"members"`
}

// RatingsList is GET /ratings/lists/{name}.
type RatingsList struct {
	Name   string      `json:"name"`
	Date   string      `json:"date"`
	Rows   []SetupRow  `json:"rows"`
	Groups []GroupRank `json:"groups"`
}

// ScreenFilter is one field / operator / value condition.
type ScreenFilter struct {
	Field string `json:"field"`
	Op    string `json:"op"`
	Value any    `json:"value"`
}

// ScreenResult is the screener's answer: flat rows of fields.
type ScreenResult struct {
	Date     string           `json:"date"`
	Universe int              `json:"universe"`
	Matched  int              `json:"matched"`
	Rows     []map[string]any `json:"rows"`
}

// WatchlistItem is a watched symbol.
type WatchlistItem struct {
	Symbol  string    `json:"symbol"`
	Note    string    `json:"note"`
	AddedAt time.Time `json:"addedAt"`
}

// AnalogOutcome is what followed the matches over one forward window; every rate stands next to its count.
type AnalogOutcome struct {
	Forward         string    `json:"forward"`
	Count           int       `json:"count"`
	WinRate         float64   `json:"winRate"`
	Mean            float64   `json:"mean"`
	Median          float64   `json:"median"`
	P25             float64   `json:"p25"`
	P75             float64   `json:"p75"`
	MaeMedian       float64   `json:"maeMedian"`
	MfeMedian       float64   `json:"mfeMedian"`
	AvgPath         []float64 `json:"avgPath"`
	DistinctSymbols int       `json:"distinctSymbols"`
	DistinctYears   int       `json:"distinctYears"`
	Direction       string    `json:"direction"`
	Consistency     string    `json:"consistency"`
	Reliability     string    `json:"reliability"`
	Risk            string    `json:"risk"`
	Outlier         bool      `json:"outlier"`
}

// AnalogSplit is the same matches in two groups, each with its count.
type AnalogSplit struct {
	Name         string  `json:"name"`
	Forward      string  `json:"forward"`
	Count        int     `json:"count"`
	WinRate      float64 `json:"winRate"`
	Median       float64 `json:"median"`
	OtherCount   int     `json:"otherCount"`
	OtherWinRate float64 `json:"otherWinRate"`
	OtherMedian  float64 `json:"otherMedian"`
}

// AnalogSession is the session-analog extras.
type AnalogSession struct {
	Count           int     `json:"count"`
	HighHeld        int     `json:"highHeld"`
	LowHeld         int     `json:"lowHeld"`
	MedianHighTime  string  `json:"medianHighTime"`
	MedianLowTime   string  `json:"medianLowTime"`
	MedianReturnAtr float64 `json:"medianReturnAtr"`
}

// AnalogSummary is the evidence for one symbol, session and lookback.
type AnalogSummary struct {
	SessionDate   string          `json:"sessionDate"`
	Symbol        string          `json:"symbol"`
	Kind          string          `json:"kind"`
	Lookback      int             `json:"lookback"`
	Checkpoint    string          `json:"checkpoint"`
	Candidates    int             `json:"candidates"`
	Compared      int             `json:"compared"`
	Matches       int             `json:"matches"`
	MedianQuality float64         `json:"medianQuality"`
	QualityTag    string          `json:"qualityTag"`
	Outcomes      []AnalogOutcome `json:"outcomes"`
	Splits        []AnalogSplit   `json:"splits"`
	Narrative     []string        `json:"narrative"`
	Session       *AnalogSession  `json:"session"`
}

// DailyCandle is a D1 candle for the stock chart.
type DailyCandle struct {
	OpenTime time.Time `json:"openTime"`
	Close    Flex      `json:"close"`
}

// Flex is a number that the server sends either as a JSON number or as a string ("1495.00").
type Flex float64

func (f *Flex) UnmarshalJSON(b []byte) error {
	s := string(b)
	if s == "null" {
		return nil
	}
	if len(s) > 1 && s[0] == '"' {
		s = s[1 : len(s)-1]
	}
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return err
	}
	*f = Flex(v)
	return nil
}

func (c *Client) Rating(symbol string) (DailyRating, error) {
	var r DailyRating
	return r, c.do(http.MethodGet, "/ratings/"+url.PathEscape(symbol), nil, false, &r)
}

func (c *Client) BasesOf(symbol string) ([]Base, error) {
	var b []Base
	return b, c.do(http.MethodGet, "/ratings/"+url.PathEscape(symbol)+"/bases", nil, false, &b)
}

func (c *Client) RatingsList(name string) (RatingsList, error) {
	var l RatingsList
	return l, c.do(http.MethodGet, "/ratings/lists/"+url.PathEscape(name), nil, false, &l)
}

func (c *Client) Screen(filters []ScreenFilter, sort string, limit int) (ScreenResult, error) {
	var r ScreenResult
	body := map[string]any{"filters": filters, "limit": limit}
	if sort != "" {
		body["sort"] = sort
	}
	return r, c.do(http.MethodPost, "/ratings/screen", body, false, &r)
}

func (c *Client) Watchlist() ([]WatchlistItem, error) {
	var w []WatchlistItem
	return w, c.do(http.MethodGet, "/ratings/watchlist", nil, false, &w)
}

func (c *Client) Watch(symbol, note string) (WatchlistItem, error) {
	var w WatchlistItem
	return w, c.do(http.MethodPost, "/ratings/watchlist", map[string]string{"symbol": symbol, "note": note}, false, &w)
}

func (c *Client) Unwatch(symbol string) error {
	return c.do(http.MethodDelete, "/ratings/watchlist/"+url.PathEscape(symbol), nil, false, nil)
}

func (c *Client) DailyAnalogs(symbol string, lookback int) (AnalogSummary, error) {
	var s AnalogSummary
	return s, c.do(http.MethodGet, "/analogs/"+url.PathEscape(symbol)+"?lookback="+strconv.Itoa(lookback), nil, false, &s)
}

func (c *Client) SessionAnalogs(symbol, checkpoint string) (AnalogSummary, error) {
	var s AnalogSummary
	path := "/analogs/session/" + url.PathEscape(symbol)
	if checkpoint != "" {
		path += "?checkpoint=" + url.QueryEscape(checkpoint)
	}
	return s, c.do(http.MethodGet, path, nil, false, &s)
}

// DailyCandles returns the D1 candles of the last `days` calendar days.
func (c *Client) DailyCandles(instrumentID string, days int) ([]DailyCandle, error) {
	var out []DailyCandle
	to := time.Now().UTC()
	from := to.AddDate(0, 0, -days)
	q := url.Values{"instrumentId": {instrumentID}, "timeframe": {"D1"}, "from": {from.Format(time.RFC3339)}, "to": {to.Format(time.RFC3339)}}
	return out, c.do(http.MethodGet, "/market/candles?"+q.Encode(), nil, false, &out)
}
