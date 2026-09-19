# Transaction costs and paper mode

## Cost model (PRD 12.2)

CostModel.compute(CostFill) returns an itemized CostBreakdown (brokerage, STT, exchange txn, GST, SEBI, stamp duty,
total), all in paise. Rates live in config/costs.yaml (imported into hejje.costs.*; a classpath copy provides defaults)
and were verified on 2026-09-19 (plan M6.1) against zerodha.com/charges and NSE circular FATAX/73524 (`verify: false`):
NSE transaction charges 0.00307 % (equity), 0.00183 % (futures), 0.03553 % (options premium); STT 0.025 % equity
intraday sell, 0.05 % futures sell and 0.15 % options premium sell from 1 April 2026 (Finance Act 2026; previously
0.02 % and 0.1 %). Options pay the flat ₹20 per executed order; other segments pay the lower of ₹20 and 0.03 %. The
model has one rate set, so backtests apply today's rates to all history (conservative for F&O before April 2026).
Segments: equity intraday,
equity delivery (no brokerage, STT both sides), futures, options (STT on premium). The model is shared by the paper fills
and, later, the backtester.

Fills apply their cost at the orders layer: Position.realizedPnl stays gross, Position.fees accumulates costs, and
Position.netRealizedPnl() is realized minus fees. GET /trades/{id}/costs recomputes a fill's breakdown deterministically.

## Paper mode (PRD 50)

hejje.mode=PAPER runs paper trading. With the fake adapter (dev/test) the fake itself simulates fills. With the real
(zerodha) adapter, PaperBrokerAdapter wraps it: quotes, history, instruments, streaming and the broker session use live
data, while transactional operations are simulated - MARKET fills at the last price with hejje.paper.slippage-bps
slippage, LIMIT/SL fill when a tick crosses, positions and funds are simulated, and it never places a real order.

Every response carries X-Hejje-Mode. Orders, positions and trades are filtered by the current mode; ?mode= inspects the
other ledger. Switching mode requires a restart. Starting in CONFIRM/AUTO with open PAPER positions logs a warning.
