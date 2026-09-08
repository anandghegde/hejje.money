package api

// Paise is a money amount serialized by the server as {"paise": n}.
type Paise struct {
	Paise int64 `json:"paise"`
}

type Check struct {
	Status string `json:"status"`
	Detail string `json:"detail"`
}

type Health struct {
	Status           string   `json:"status"`
	Mode             string   `json:"mode"`
	Version          string   `json:"version"`
	ExecutionEnabled bool     `json:"executionEnabled"`
	Reasons          []string `json:"reasons"`
	StaticIP         Check    `json:"staticIp"`
	Broker           Check    `json:"broker"`
	MarketData       Check    `json:"marketData"`
	Database         Check    `json:"database"`
	ClockSync        Check    `json:"clockSync"`
	RiskEngine       Check    `json:"riskEngine"`
	OrderQueue       Check    `json:"orderQueue"`
}

type BrokerStatus struct {
	Broker             string `json:"broker"`
	State              string `json:"state"`
	BrokerUserID       string `json:"brokerUserId"`
	LiveTradingEnabled bool   `json:"liveTradingEnabled"`
	Detail             string `json:"detail"`
}

type Instrument struct {
	ID          string  `json:"id"`
	Symbol      string  `json:"symbol"`
	HejjeSymbol string  `json:"hejjeSymbol"`
	LotSize     int     `json:"lotSize"`
	TickSize    float64 `json:"tickSize"`
	Type        string  `json:"type"`
}

type Order struct {
	ID            string  `json:"id"`
	InstrumentID  string  `json:"instrumentId"`
	Side          string  `json:"side"`
	Quantity      int     `json:"quantity"`
	Filled        int     `json:"filledQuantity"`
	AveragePrice  float64 `json:"averagePrice"`
	OrderType     string  `json:"orderType"`
	Product       string  `json:"product"`
	State         string  `json:"state"`
	BrokerOrderID string  `json:"brokerOrderId"`
	UpdatedAt     string  `json:"updatedAt"`
}

type Position struct {
	ID           string  `json:"id"`
	InstrumentID string  `json:"instrumentId"`
	Product      string  `json:"product"`
	NetQuantity  int     `json:"netQuantity"`
	AveragePrice float64 `json:"averagePrice"`
	RealizedPnl  Paise   `json:"realizedPnl"`
	Fees         Paise   `json:"fees"`
}

type Trade struct {
	ID       string  `json:"id"`
	Side     string  `json:"side"`
	Quantity int     `json:"quantity"`
	Price    float64 `json:"price"`
	TS       string  `json:"ts"`
}

type RiskDashboard struct {
	RealizedPnl              Paise   `json:"realizedPnl"`
	UnrealizedPnl            Paise   `json:"unrealizedPnl"`
	NetPnl                   Paise   `json:"netPnl"`
	DailyLossLimit           Paise   `json:"dailyLossLimit"`
	OpenPositions            int     `json:"openPositions"`
	MaxOpenPositions         int     `json:"maxOpenPositions"`
	TradesToday              int     `json:"tradesToday"`
	MaxTradesPerDay          int     `json:"maxTradesPerDay"`
	MarginUsedPct            float64 `json:"marginUsedPct"`
	KillSwitchStopNewOrders  bool    `json:"killSwitchStopNewOrders"`
}

type KillSwitch struct {
	Mode          string `json:"mode"`
	StopNewOrders bool   `json:"stopNewOrders"`
	Reason        string `json:"reason"`
}
