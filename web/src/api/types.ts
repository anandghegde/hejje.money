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

export interface Order {
  id: string; instrumentId: string; side: 'BUY' | 'SELL'; quantity: number; filledQuantity: number;
  averagePrice: number; orderType: string; product: string; state: string; brokerOrderId?: string;
  limitPrice?: number; updatedAt: string;
}

export interface Position {
  id: string; instrumentId: string; product: string; netQuantity: number; averagePrice: number;
  realizedPnl: { paise: number }; fees: { paise: number };
}

export interface Trade { id: string; orderId: string; instrumentId: string; side: string; quantity: number; price: number; ts: string; }

export interface RiskDashboard {
  realizedPnl: { paise: number }; unrealizedPnl: { paise: number }; netPnl: { paise: number };
  dailyLossLimit: { paise: number }; openPositions: number; maxOpenPositions: number; tradesToday: number;
  maxTradesPerDay: number; consecutiveLosses: number; marginUsedPct: number; killSwitchStopNewOrders: boolean;
}

export interface KillSwitch { mode: ExecutionMode; stopNewOrders: boolean; reason?: string; }
