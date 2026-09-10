export type ExecutionMode = 'PAPER' | 'CONFIRM' | 'AUTO';

export interface Health {
  status: string;
  mode: ExecutionMode;
  version: string;
  executionEnabled: boolean;
  reasons: string[];
  staticIp: Check;
  broker: Check;
  marketData: Check;
  database: Check;
  clockSync: Check;
  riskEngine: Check;
  orderQueue: Check;
}
export interface Check { status: string; detail: string; }

export interface BrokerStatus {
  broker: string;
  state: 'CONNECTED' | 'EXPIRED' | 'DISCONNECTED' | 'ERROR';
  brokerUserId?: string;
  liveTradingEnabled: boolean;
  detail: string;
}

export interface Instrument {
  id: string; symbol: string; name: string; exchange: string; type: string;
  lotSize: number; tickSize: number; hejjeSymbol: string;
}

export interface StopSuggestion {
  entry: string; stop: string; basis: 'ATR' | 'PERCENT' | 'MAX_DISTANCE'; atr: number | null; distancePct: number; maxDistancePct: number;
}

export interface Order {
  id: string; instrumentId: string; side: 'BUY' | 'SELL'; quantity: number; filledQuantity: number;
  averagePrice: number; orderType: string; product: string; state: string; brokerOrderId?: string;
  limitPrice?: number; updatedAt: string;
}

export interface Position {
  id: string; instrumentId: string; product: string; netQuantity: number; averagePrice: number;
  realizedPnl: { paise: number }; fees: { paise: number };
}

export interface Trade { id: string; orderId: string; instrumentId: string; side: string; quantity: number; price: number; ts: string; strategyId?: string; }

export interface RiskDashboard {
  realizedPnl: { paise: number }; unrealizedPnl: { paise: number }; netPnl: { paise: number };
  dailyLossLimit: { paise: number }; openPositions: number; maxOpenPositions: number; tradesToday: number;
  maxTradesPerDay: number; consecutiveLosses: number; marginUsedPct: number; killSwitchStopNewOrders: boolean;
}

export interface KillSwitch { mode: ExecutionMode; stopNewOrders: boolean; reason?: string; }

// --- Phase 2 (M2.7) ---

export interface Money { paise: number }

export interface Strategy {
  id: string; slug: string; family: string; name: string; createdAt: string; retiredAt?: string;
  latestVersion: number; latestVersionId?: string; latestStatus?: string;
}

export interface StrategyVersion {
  id: string; strategyId: string; version: number; definitionYaml: string; definition: any; definitionHash: string;
  changeNote: string; parentVersionId?: string; createdBy: string; createdAt: string; status: string;
}

export interface Deployment {
  id: string; versionId: string; strategyId: string; mode: ExecutionMode; instrumentIds: string[]; autonomyLevel: number;
  enabled: boolean; params: Record<string, unknown>; createdAt: string; pausedAt?: string; pauseReason?: string; sizeMultiplier: number;
}

export type DriftStatus = 'INSUFFICIENT_DATA' | 'HEALTHY' | 'WATCH' | 'DEGRADING' | 'FAILED';

export interface DriftSide {
  trades: number; winRate: number; expectancyR: number; profitFactor?: number; maxDrawdownR: number; backtestId?: string; split?: string;
}

export interface DriftReport {
  deploymentId: string; versionId: string; version: number; strategyId: string; mode: ExecutionMode; enabled: boolean; sizeMultiplier: number;
  status: DriftStatus; window: { maxTrades: number; sessions: number; from: string; to: string }; live: DriftSide; backtest?: DriftSide;
  stats?: { winRatePValue: number; expectancyLow: number; expectancyHigh: number; confidence: number; expectancyRatio?: number; drawdownMultiple?: number };
  triggered: string[]; evidence: string[]; assessedAt: string;
}

export interface DriftState {
  deploymentId: string; status: DriftStatus; actedStatus: DriftStatus; triggered: string[]; overrideStatus?: DriftStatus; overrideReason?: string;
  overrideBy?: string; overrideAt?: string; updatedAt: string;
}

export interface DeploymentDrift {
  report: DriftReport; state?: DriftState; history: { id: string; status: DriftStatus; actions: string[]; triggered: string[]; at: string }[];
}

export interface StrategyDrift { strategyId: string; enabled: boolean; deployments: DeploymentDrift[] }

export interface BacktestMetrics {
  totalTrades: number; winningTrades: number; losingTrades: number; winRate: number; expectancyR: number; profitFactor?: number;
  maxDrawdown: Money; maxDrawdownR: number; maxDrawdownPct: number; sharpe?: number; sortino?: number; grossPnl: Money; totalCosts: Money;
  netPnl: Money; totalReturnPct: number; averageHoldingMinutes: number; maxConsecutiveLosses: number;
  equityCurve: { time: string; value: Money }[]; monthly: Record<string, { trades: number; netPnl: Money; winRate: number }>;
  rDistribution: Record<string, number>;
}

export interface Backtest {
  id: string; versionId: string; status: string; progressPct: number; createdAt: string; finishedAt?: string; spec: any;
  metrics?: BacktestMetrics; bySplit: Record<string, BacktestMetrics>; warnings: { code: string; severity: string; message: string }[];
  sessionsExpected: number; sessionsWithData: number; resultHash?: string; error?: string;
}

export interface RegimeBucket { key: string; trades: number; winRate: number; expectancyR: number; profitFactor?: number; netPnl: Money }
export interface RegimeBreakdown {
  dims: string[]; byRegime: RegimeBucket[];
  similar?: RegimeBucket & { current: string; overallTrades: number; overallExpectancyR: number }; note?: string;
}

export interface BacktestTrade {
  id: string; instrumentId: string; split: string; entryTime: string; exitTime: string; side: string; qty: number; entryPrice: number;
  exitPrice: number; stop?: number; target?: number; grossPnl: Money; costs: Money; netPnl: Money; rMultiple: number; exitReason: string;
  evidence: { condition: string; status: string; lhs?: number; rhs?: number }[];
}

export interface ScoreBreakdown {
  id: string; versionId: string; instrumentId?: string; computedAt: string; baseBacktestId?: string; base: number; cap?: string;
  components: { name: string; weight: number; score: number; contribution: number; evidence: Record<string, unknown> }[];
  adjustments: { name: string; delta: number; min: number; max: number; evidence: string[] }[];
  finalScore: number;
}

export interface ScoreView { strategyId: string; versionId: string; version: number; breakdown?: ScoreBreakdown; instruments: { instrumentId: string; finalScore: number }[] }

export interface ValidationReport { valid: boolean; errors: { path: string; message: string }[]; definition?: any }

export interface Signal {
  id: string; versionId: string; strategyId: string; deploymentId?: string; instrumentId: string; side: 'BUY' | 'SELL';
  referencePrice: number; stop: number; target?: number; riskPerUnit: number; barTime: string; validUntil: string;
  evidence: { condition: string; status: string; lhs?: number; rhs?: number }[]; status: string; note?: string; orderId?: string;
}

export interface PreparedOrder {
  signal: Signal;
  proposal: { instrumentId: string; side: string; quantity: number; orderType: string; product: string; stopPrice?: string; targetPrice?: string; maxRisk?: Money };
  risk: { outcome: string; checks: { name: string; passed: boolean; message: string; observed?: string; limit?: string }[] };
  sizing: Record<string, unknown>;
  notes: string[];
}

export interface Recommendation {
  versionId: string; strategyId: string; strategy: string; version: number; deploymentId: string; instrumentId: string; instrument: string;
  score?: number; decision: 'TRADE' | 'TRADE_WITH_CAUTION' | 'WAIT' | 'AVOID'; direction?: 'BUY' | 'SELL'; signalId?: string; signalStatus?: string; signalValidUntil?: string;
  entry?: number; stop?: number; target?: number; quantity?: number; riskRupees?: number; expectedRewardRupees?: number; regime?: string;
  newsBias?: number; eventRisk: string; nextEvent?: string; hardBlocks: string[]; cautions: { code: string; message: string }[]; supportingEvidence: string[]; risks: string[];
  backtest: Record<string, unknown>; scoreBreakdown: Record<string, unknown>; context?: StrategyContext;
}

export interface TodayView {
  header: { indexQuotes: Record<string, { lastPrice: number; stale: boolean }>; vix?: number; regime?: string; breadth?: string; eventRisk?: string; nextEvent?: string; mode: string };
  best?: Recommendation; ranked: Recommendation[]; noTrade?: string;
}

export interface TradeReview {
  id: string; positionId?: string; strategyId?: string; strategyVersionId?: string; signalId?: string; instrumentId: string; entryOrderId: string;
  side: string; quantity: number; entryPrice: number; exitPrice: number; openedAt: string; closedAt: string; grossPnl: Money; fees: Money; netPnl: Money;
  outcomeR?: number; expectedSetupValid?: boolean; entrySlippageBps?: number; exitSlippageBps?: number; ruleAdherencePct?: number; closeReason?: string;
  context: Record<string, string>; notes?: string;
}

export interface PnlBucket { key: string; label: string; trades: number; wins: number; grossPnl: Money; fees: Money; netPnl: Money; winRate: number; averageR?: number }
export interface PnlReport { groupBy: string; mode: string; buckets: PnlBucket[]; summary: { roundTrips: number; grossPnl: Money; fees: Money; netPnl: Money } }

// --- Phase 3 (M3.2) ---
export interface PulseComponent { name: string; weight: number; value?: number; contribution: number; evidence: string }
export interface TechnicalPulse { direction: string; strength: string; score: number; coverage: number; components: PulseComponent[]; evidence: string[] }
export interface SectorStrength { name: string; symbol: string; label: string; changePct?: number; relativePct?: number }
export interface MarketPulse { regime: string; volatility: string; breadth: string; sectors: SectorStrength[]; globalContext: string }
export interface PulseSnapshot { date: string; asOf: string; technical: TechnicalPulse; market: MarketPulse }
export interface Instrument { id: string; symbol: string; name: string; exchange: string; type: string }
export interface Candle { instrumentId: string; timeframe: string; openTime: string; open: number; high: number; low: number; close: number; volume: number }

// --- Phase 3 (M3.3) ---
export interface MarketEvent {
  id: string; type: string; scope: string; instrumentId?: string; symbol?: string; title: string; startsAt: string; endsAt?: string; allDay: boolean;
  source: string; confidence: number;
}
export interface EventRisk { level: string; nextEvent?: MarketEvent; minutesTo?: number; evidence: string[]; available: boolean }

// --- Phase 3 (M3.4) ---
export interface NewsContribution {
  itemId: string; title: string; url: string; source: string; publishedAt: string; direction: number; materiality: number; confidence: number; weight: number;
  summary?: string;
}
export interface NewsBias { instrumentId: string; computedAt: string; score: number; label: string; items: number; evidence: string[]; available: boolean; sources: NewsContribution[] }

// --- Phase 3 (M3.5) ---
export interface ContextItem { name: string; status: 'GREEN' | 'AMBER' | 'RED' | 'UNKNOWN'; value: string; delta?: number; evidence: string[] }
export interface StrategyContext {
  versionId: string; instrumentId?: string; asOf: string; technicalFit: ContextItem; marketRegime: ContextItem; newsBias: ContextItem; eventRisk: ContextItem;
  sector: ContextItem; nextEvent?: string; netImpact: number; items: ContextItem[];
}

// Hejje AI (M4.3)
export interface AiStatus { enabled: boolean; llmEnabled: boolean; profile: string; followUpProfile: string; maxSteps: number; reason: string | null }
export interface AiTraceStep { actionId: string; tool: string; status: string; requiredScope: string | null; latencyMs: number; error: string | null }
export interface Grounding { verifiedNumbers: string[]; unverifiedNumbers: string[]; citedIds: string[]; unknownIds: string[] }
export interface AiTurn {
  conversationId: string; messageId: string; answer: string; grounding: Grounding; trace: AiTraceStep[]; steps: number; profile: string;
  flow: string | null; stepLimitReached: boolean;
}

// Approvals (M4.4)
export interface ApprovalCheck { name: string; passed: boolean; observed: string | null; limit: string | null; message: string | null }
export interface Approval {
  id: string; kind: 'ORDER_NEW' | 'ORDER_MODIFY' | 'ORDER_CANCEL' | 'POSITION_CLOSE'; status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'FAILED';
  mode: string; intentId: string | null; signalId: string | null; strategyId: string | null; instrumentId: string | null; instrument: string | null;
  orderId: string | null; requestedBy: string; requestedByType: string; summary: string; rationale: string | null; proposal: any;
  risk: { outcome: string; checks: ApprovalCheck[] } | null; policy: { decision: string; rule: string | null; reason: string } | null;
  createdAt: string; expiresAt: string; decidedBy: string | null; decidedAt: string | null; decisionNote: string | null; result: any;
}

// Performance investigation (M4.5)
export interface LossBucket { key: string; trades: number; losers: number; netPnl: number; losses: number; lossSharePct: number }
export interface LossReport {
  mode: string; from: string; to: string;
  attribution: { trades: number; winners: number; losers: number; netPnl: number; grossLosses: number; grossWins: number;
    dimensions: { name: string; buckets: LossBucket[] }[]; familyByTrend: LossBucket[]; headline: string | null };
}
export interface SlippageSide { trades: number; meanBps: number | null; medianBps: number | null; p90Bps: number | null; worstBps: number | null; costRupees: number }
export interface SlippageReport { mode: string; from: string; to: string; slippage: { entry: SlippageSide; exit: SlippageSide; totalCostRupees: number } }
export interface AdherenceReport {
  mode: string; from: string; to: string;
  adherence: { trades: number; withAdherence: number; meanAdherencePct: number | null; fullAdherence: number; setupInvalid: number; manualExits: number;
    netFullAdherence: number; netPartialAdherence: number };
}
export interface Outcome { trades: number; winners: number; netPnl: number; maxDrawdown: number; winRate: number | null; profitFactor: number | null }
export interface CounterfactualReport {
  mode: string; from: string; to: string;
  counterfactual: { basis: 'SIMULATED'; note: string; actual: Outcome; simulated: Outcome; excludedTrades: number; excludedNetPnl: number;
    netDifference: number; drawdownDifference: number };
}

// Natural-language strategy builder (M4.6)
export interface StrategyDraftAttempt { iteration: number; yaml: string; errors: string[] }
export interface StrategyDraft {
  created: boolean; strategyId: string | null; slug: string | null; versionId: string | null; version: number | null; status: string | null;
  changeNote: string | null; yaml: string; rules: string[]; parentYaml: string | null; parentVersion: number | null; attempts: StrategyDraftAttempt[]; errors: string[];
}

// Strategy experiments (M4.7)
export interface SplitSummary { trades: number; expectancyR: number; profitFactor: number | null; maxDrawdownR: number; winRate: number; netPnl: number }
export interface VariantMetrics { overall: SplitSummary; inSample: SplitSummary | null; validation: SplitSummary | null; outOfSample: SplitSummary | null;
  walkForwardStdR: number | null; windows: number; qualityWarnings: string[] }
export interface ExperimentVariant {
  id: string; ordinal: number; name: string; description: string | null; delta: Record<string, unknown>; status: 'QUEUED' | 'DONE' | 'FAILED' | 'INVALID';
  metrics: VariantMetrics | null; rank: number | null; score: number | null; verdict: string | null; warnings: string[]; parameterCount: number;
  conditionCount: number; error: string | null; promotedVersionId: string | null;
}
export interface Experiment {
  id: string; baseVersionId: string; strategyId: string; goal: string | null; status: 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'; createdBy: string; createdAt: string;
  finishedAt: string | null; error: string | null; notes: string[]; variants: ExperimentVariant[]; dataset: { from: string; to: string };
}

export type PolicyDecision = 'ALLOW' | 'REQUIRE_APPROVAL' | 'DENY';

export interface PolicyRule {
  id: string; name: string; priority: number; condition: string; actions: string[]; decision: PolicyDecision; params: Record<string, unknown>;
  enabled: boolean; description: string; updatedAt: string; updatedBy: string;
}

export interface PolicyView { rules: PolicyRule[]; defaultDecision: PolicyDecision; notes: string[] }
