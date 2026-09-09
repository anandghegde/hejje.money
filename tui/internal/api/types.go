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

// --- Phase 2 (M2.8) ---

type Recommendation struct {
	VersionID            string         `json:"versionId"`
	StrategyID           string         `json:"strategyId"`
	Strategy             string         `json:"strategy"`
	Version              int            `json:"version"`
	DeploymentID         string         `json:"deploymentId"`
	InstrumentID         string         `json:"instrumentId"`
	Instrument           string         `json:"instrument"`
	Score                *int           `json:"score"`
	Decision             string         `json:"decision"`
	Direction            string         `json:"direction"`
	SignalID             string         `json:"signalId"`
	SignalStatus         string         `json:"signalStatus"`
	SignalValidUntil     string         `json:"signalValidUntil"`
	Entry                *float64       `json:"entry"`
	Stop                 *float64       `json:"stop"`
	Target               *float64       `json:"target"`
	Quantity             *int           `json:"quantity"`
	RiskRupees           *float64       `json:"riskRupees"`
	ExpectedRewardRupees *float64       `json:"expectedRewardRupees"`
	EventRisk            string         `json:"eventRisk"`
	NextEvent            string         `json:"nextEvent"`
	HardBlocks           []string       `json:"hardBlocks"`
	SupportingEvidence   []string       `json:"supportingEvidence"`
	Risks                []string       `json:"risks"`
	Backtest             map[string]any `json:"backtest"`
	ScoreBreakdown       map[string]any `json:"scoreBreakdown"`
}

type TodayView struct {
	Header  map[string]any   `json:"header"`
	Best    *Recommendation  `json:"best"`
	Ranked  []Recommendation `json:"ranked"`
	NoTrade string           `json:"noTrade"`
}

type Signal struct {
	ID             string  `json:"id"`
	VersionID      string  `json:"versionId"`
	StrategyID     string  `json:"strategyId"`
	InstrumentID   string  `json:"instrumentId"`
	Side           string  `json:"side"`
	ReferencePrice float64 `json:"referencePrice"`
	Stop           float64 `json:"stop"`
	Target         float64 `json:"target"`
	RiskPerUnit    float64 `json:"riskPerUnit"`
	BarTime        string  `json:"barTime"`
	ValidUntil     string  `json:"validUntil"`
	Status         string  `json:"status"`
	Note           string  `json:"note"`
}

type RiskCheck struct {
	Name    string `json:"name"`
	Passed  bool   `json:"passed"`
	Message string `json:"message"`
}

type PreparedOrder struct {
	Signal   Signal `json:"signal"`
	Proposal struct {
		InstrumentID string `json:"instrumentId"`
		Side         string `json:"side"`
		Quantity     int    `json:"quantity"`
		OrderType    string `json:"orderType"`
		Product      string `json:"product"`
		StopPrice    string `json:"stopPrice"`
		TargetPrice  string `json:"targetPrice"`
		MaxRisk      Paise  `json:"maxRisk"`
	} `json:"proposal"`
	Risk struct {
		Outcome string      `json:"outcome"`
		Checks  []RiskCheck `json:"checks"`
	} `json:"risk"`
	Sizing map[string]any `json:"sizing"`
	Notes  []string       `json:"notes"`
}

type Strategy struct {
	ID            string `json:"id"`
	Slug          string `json:"slug"`
	Family        string `json:"family"`
	LatestVersion int    `json:"latestVersion"`
	LatestStatus  string `json:"latestStatus"`
}

type StrategyVersion struct {
	ID         string `json:"id"`
	Version    int    `json:"version"`
	Status     string `json:"status"`
	ChangeNote string `json:"changeNote"`
	CreatedAt  string `json:"createdAt"`
}

type ScoreComponent struct {
	Name         string  `json:"name"`
	Weight       float64 `json:"weight"`
	Score        float64 `json:"score"`
	Contribution float64 `json:"contribution"`
}

type ScoreAdjustment struct {
	Name     string   `json:"name"`
	Delta    int      `json:"delta"`
	Evidence []string `json:"evidence"`
}

type ScoreBreakdown struct {
	Base        float64           `json:"base"`
	Cap         string            `json:"cap"`
	Components  []ScoreComponent  `json:"components"`
	Adjustments []ScoreAdjustment `json:"adjustments"`
	FinalScore  int               `json:"finalScore"`
	ComputedAt  string            `json:"computedAt"`
}

type ScoreView struct {
	Version   int             `json:"version"`
	Breakdown *ScoreBreakdown `json:"breakdown"`
}

type Deployment struct {
	ID            string   `json:"id"`
	VersionID     string   `json:"versionId"`
	StrategyID    string   `json:"strategyId"`
	Mode          string   `json:"mode"`
	InstrumentIDs []string `json:"instrumentIds"`
	Enabled       bool     `json:"enabled"`
	PauseReason   string   `json:"pauseReason"`
}

type Backtest struct {
	ID      string `json:"id"`
	Status  string `json:"status"`
	Metrics *struct {
		TotalTrades  int      `json:"totalTrades"`
		WinRate      float64  `json:"winRate"`
		ExpectancyR  float64  `json:"expectancyR"`
		ProfitFactor *float64 `json:"profitFactor"`
		MaxDrawdownR float64  `json:"maxDrawdownR"`
		NetPnl       Paise    `json:"netPnl"`
	} `json:"metrics"`
	Warnings []struct {
		Code     string `json:"code"`
		Severity string `json:"severity"`
	} `json:"warnings"`
}

// --- Phase 3 (M3.2) ---

type PulseComponent struct {
	Name         string   `json:"name"`
	Weight       float64  `json:"weight"`
	Value        *float64 `json:"value"`
	Contribution float64  `json:"contribution"`
	Evidence     string   `json:"evidence"`
}

type TechnicalPulse struct {
	Direction  string           `json:"direction"`
	Strength   string           `json:"strength"`
	Score      int              `json:"score"`
	Coverage   float64          `json:"coverage"`
	Components []PulseComponent `json:"components"`
	Evidence   []string         `json:"evidence"`
}

type SectorStrength struct {
	Name        string   `json:"name"`
	Symbol      string   `json:"symbol"`
	Label       string   `json:"label"`
	ChangePct   *float64 `json:"changePct"`
	RelativePct *float64 `json:"relativePct"`
}

type MarketPulse struct {
	Regime        string           `json:"regime"`
	Volatility    string           `json:"volatility"`
	Breadth       string           `json:"breadth"`
	Sectors       []SectorStrength `json:"sectors"`
	GlobalContext string           `json:"globalContext"`
}

type PulseSnapshot struct {
	Date      string         `json:"date"`
	AsOf      string         `json:"asOf"`
	Technical TechnicalPulse `json:"technical"`
	Market    MarketPulse    `json:"market"`
}
