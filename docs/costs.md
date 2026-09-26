# Transaction costs and paper mode

## Cost model (PRD 12.2)

CostModel.compute(CostFill) returns an itemized CostBreakdown (brokerage, STT, exchange txn, GST, SEBI, stamp duty,
total), all in paise. Rates live in config/costs.yaml (imported into hejje.costs.*; a classpath copy provides defaults)
and were verified on 2026-09-19 (plan M6.1) and re-checked unchanged on 2026-09-26 against zerodha.com/charges, NSE
circulars FATAX/73524 (STT) and FA/73061 (transaction charges incl. IPFT) and NSE's levies and stamp duty pages (URLs in
the file header; `verify: false`):
NSE transaction charges 0.00307 % (equity), 0.00183 % (futures), 0.03553 % (options premium); STT 0.025 % equity
intraday sell, 0.05 % futures sell and 0.15 % options premium sell from 1 April 2026 (Finance Act 2026; previously
0.02 % and 0.1 %). Options pay the flat ₹20 per executed order; other segments pay the lower of ₹20 and 0.03 %. The
model has one rate set, so backtests apply today's rates to all history (conservative for F&O before April 2026).
Segments: equity intraday,
equity delivery (no brokerage, STT both sides), futures, options (STT on premium). The model is shared by the paper fills
and, later, the backtester.

Fills apply their cost at the orders layer: Position.realizedPnl stays gross, Position.fees accumulates costs, and
Position.netRealizedPnl() is realized minus fees. GET /trades/{id}/costs recomputes a fill's breakdown deterministically.

Depository (DP) charge (plan M11.1): a delivery (CNC) sell of an equity pays hejje.costs.dp-charge (15.34 rupees: 3.50
CDSL + 9.50 Zerodha + 2.34 GST, per zerodha.com/charges, checked 2026-09-26) once per scrip and IST day, whatever the
quantity or the number of sell fills; it is itemized as dpCharges (GST included, not in the gst item). The orders layer
charges it to the day's first delivery sell fill of the scrip; CostFill.dpCharge=false prices a later one.

## Paper mode (PRD 50)

hejje.mode=PAPER runs paper trading. With the fake adapter (dev/test) the fake itself simulates fills. With the real
(zerodha) adapter, PaperBrokerAdapter wraps it: quotes, history, instruments, streaming and the broker session use live
data, while transactional operations are simulated - MARKET fills at the last price with hejje.paper.slippage-bps
slippage, LIMIT/SL fill when a tick crosses, positions and funds are simulated, and it never places a real order.

Every response carries X-Hejje-Mode. Orders, positions and trades are filtered by the current mode; ?mode= inspects the
other ledger. Switching mode requires a restart. Starting in CONFIRM/AUTO with open PAPER positions logs a warning.
