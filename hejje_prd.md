# Hejje — Product Requirements Document

**Working domain:** `hejje.money`  
**Product category:** Intraday trading strategy discovery, decision support, backtesting, automation, and execution for Indian markets  
**Initial broker:** Zerodha via Kite Connect  
**Primary market:** India  
**Document status:** Draft v1  
**Date:** September 2026

---

# 1. Executive Summary

Hejje is an intraday trading platform designed around one core question:

> **What is the best strategy to trade right now, why, and should I execute it?**

Traditional trading platforms expose charts, indicators, watchlists, news, scanners, orders, and positions independently. The trader is expected to combine all of that information manually.

Hejje should instead operate as a **strategy decision and execution system**.

It should:

1. Maintain a library of deterministic trading strategies.
2. Backtest and rank those strategies using realistic historical performance.
3. Determine which strategies are best suited to the current market regime.
4. Incorporate market context, news bias, and upcoming event risk.
5. Present a simple, explainable recommendation.
6. Allow the user or an LLM agent to prepare and execute the trade.
7. Enforce deterministic risk controls before any broker order is sent.
8. Route all transactional broker calls through a user-controlled Linux execution server with a whitelisted static IP.
9. Provide both a remote Web interface and a remote terminal/TUI interface.
10. Be architected so that LLM agents can safely operate every major workflow through structured APIs and tools.

The intended mental model is:

> **Quant engine calculates → Context engine adjusts → LLM explains/reasons → Risk engine authorizes → Execution daemon sends → Broker executes**

The LLM must not directly improvise orders from charts or bypass deterministic controls.

---

# 2. Product Principles

## 2.1 Decision-first, not chart-first

Hejje should minimize the cognitive work required to decide whether a trade is worth taking.

Instead of presenting twenty indicators and asking the user to interpret them, Hejje should answer:

- What is happening?
- Which strategies fit?
- Which strategy has the strongest evidence?
- What is the expected risk/reward?
- What could invalidate the trade?
- Should we execute, wait, or avoid?

Charts and raw indicators remain available for inspection but are secondary to the decision.

---

## 2.2 Strategies are first-class objects

The core object in Hejje is not an order or a chart.

It is a **Strategy Version**.

Every strategy should have:

- Strategy ID
- Name
- Version
- Description
- Supported instruments
- Supported market segments
- Entry rules
- Exit rules
- Stop rules
- Position-sizing rules
- Allowed trading window
- Market-regime preferences
- Event/news constraints
- Backtest dataset
- Backtest results
- Out-of-sample results
- Paper-trading results
- Live-trading results
- Current score
- Deployment status
- Risk limits

This makes strategies measurable, comparable, deployable, and auditable.

---

## 2.3 No-trade is a valid outcome

Hejje must not manufacture trading opportunities.

The platform should comfortably report:

> **No strategy currently meets your minimum quality threshold.**

“No Trade” must be a first-class decision.

---

## 2.4 LLMs reason; deterministic systems calculate and enforce

LLMs are useful for:

- interpreting structured results,
- comparing strategies,
- explaining recommendations,
- interacting through natural language,
- generating candidate strategy definitions,
- investigating performance,
- orchestrating tools.

LLMs should not be responsible for:

- calculating indicators from raw ticks,
- calculating P&L,
- enforcing position limits,
- deciding whether an order violates a hard risk rule,
- sending arbitrary broker payloads,
- storing broker credentials,
- bypassing strategy definitions.

---

## 2.5 Execution must remain under user-controlled infrastructure

The production execution path should run on a Linux server controlled by the user.

The server will:

- have a static public IP,
- contain broker credentials/tokens,
- host the Hejje execution daemon,
- perform order validation,
- perform risk validation,
- call Kite Connect,
- maintain authoritative order/position state,
- expose secure Hejje APIs to remote clients and agents.

Remote clients must never need direct access to Zerodha credentials.

---

# 3. Regulatory and Broker Architecture Requirement

## 3.1 Static IP requirement

For client API-based algo trading in India, API access for transactional broker calls must comply with broker/exchange requirements around registered static IPs.

Hejje will therefore assume:

- A primary static IP is registered with the broker.
- A secondary static IP may optionally be registered for redundancy.
- The production execution daemon runs behind one of those approved public egress IPs.
- Transactional broker operations originate only from this execution environment.

Examples of transactional operations:

- Place order
- Modify order
- Cancel order
- Basket/multi-leg execution
- Automated exit
- Position-closing order

Market-data and non-transactional broker capabilities may have different IP requirements depending on broker rules, but Hejje should still centralize broker integration where practical.

## 3.2 Initial Zerodha assumptions

Initial implementation will target Kite Connect.

Hejje must support:

- Zerodha authentication/session establishment
- Daily session/token lifecycle
- Instruments/master contract ingestion
- Quotes
- Historical candles
- WebSocket streaming
- Orders
- Order updates
- Trades
- Positions
- Holdings
- Funds/margins
- Order modification
- Order cancellation

The architecture must not permanently couple the core product to Zerodha-specific objects.

---

# 4. High-Level Architecture

```text
                         ┌───────────────────────────┐
                         │       Hejje Web UI        │
                         │   Browser / Mobile Web    │
                         └─────────────┬─────────────┘
                                       │
                                       │ HTTPS / WSS
                                       ▼
┌─────────────────────┐      ┌──────────────────────────────┐
│     Remote TUI      │─────▶│      Hejje API Gateway      │
│ Terminal / SSH-safe │      │ Auth / RBAC / Agent APIs    │
└─────────────────────┘      └──────────────┬───────────────┘
                                             │
                  ┌──────────────────────────┼───────────────────────────┐
                  │                          │                           │
                  ▼                          ▼                           ▼
       ┌──────────────────┐      ┌────────────────────┐      ┌────────────────────┐
       │ Strategy Engine  │      │ Context / News     │      │ Agent Orchestrator │
       │ Signals / Scores │      │ Events / Regimes   │      │ LLM Tool Calling   │
       └────────┬─────────┘      └─────────┬──────────┘      └──────────┬─────────┘
                │                          │                             │
                └──────────────────────────┴─────────────────────────────┘
                                           │
                                           ▼
                               ┌─────────────────────────┐
                               │   Execution Controller  │
                               │ Intent → Validation     │
                               └────────────┬────────────┘
                                            │
                                            ▼
                               ┌─────────────────────────┐
                               │      Risk Engine        │
                               │ HARD deterministic rules│
                               └────────────┬────────────┘
                                            │
                                            ▼
                              STATIC-IP LINUX SERVER
                         ┌──────────────────────────────────┐
                         │       Hejje Execution Daemon     │
                         │                                  │
                         │ Broker adapter                   │
                         │ Rate limiter                     │
                         │ Session manager                  │
                         │ Order state machine              │
                         │ Audit log                        │
                         │ Reconciliation                   │
                         └──────────────┬───────────────────┘
                                        │
                                        │ Whitelisted static IP
                                        ▼
                              ┌──────────────────────┐
                              │ Zerodha Kite Connect│
                              │ Future broker APIs   │
                              └──────────────────────┘
```

---

# 5. Component Architecture

## 5.1 Hejje Execution Daemon

This is the most security-sensitive and authoritative component.

It runs continuously on the static-IP Linux host.

### Responsibilities

- Broker authentication
- Secure credential storage/access
- Session/token lifecycle
- Order placement
- Order modification
- Order cancellation
- Position closure
- Broker WebSocket consumption
- Broker order-update processing
- Order-state reconciliation
- Rate limiting
- Idempotency
- Risk-engine enforcement
- Audit logging
- Health reporting
- Heartbeats
- Strategy execution scheduling when required
- Emergency kill switch

### Requirement

There must be no supported production path that allows the Web client, TUI, strategy process, webhook, or LLM agent to send broker orders around the daemon.

---

## 5.2 Broker Adapter Layer

Inspired by OpenAlgo's normalized broker layer.

Create a broker-neutral contract such as:

```text
BrokerAdapter
 ├── authenticate()
 ├── refresh_or_login()
 ├── get_quote()
 ├── get_history()
 ├── stream_market_data()
 ├── place_order()
 ├── modify_order()
 ├── cancel_order()
 ├── get_order()
 ├── get_orders()
 ├── get_trades()
 ├── get_positions()
 ├── get_holdings()
 ├── get_funds()
 └── get_instruments()
```

Initial implementation:

```text
BrokerAdapter
    └── ZerodhaKiteAdapter
```

Future adapters could include:

- Dhan
- Upstox
- Fyers
- Angel One
- Groww
- Kotak
- others

Strategy logic must use Hejje normalized instrument/order models instead of broker-specific schemas.

---

## 5.3 Remote Web Client

The Web client should be the primary rich interface.

It should include:

- Today
- Pulse
- Strategies
- Strategy detail
- Positions
- Orders
- Trade history
- Lab
- Agent
- Risk controls
- Broker/server status
- Settings

The Web application must not contain broker secrets.

---

## 5.4 Remote TUI

Hejje should ship a fast terminal client for monitoring and order management.

Example:

```text
 HEJJE                                       LIVE ●
────────────────────────────────────────────────────────
 NIFTY     24,921.4    ▲ 0.48%     TRENDING ↑
 VIX       13.82        ▲ 1.1%

 BEST HEJJE
 ORB + VWAP                    SCORE 87
 LONG NIFTY                    R:R 2.1
 Entry 24,930                  SL 24,885
 Risk ₹1,500                   Qty 50

 [E] Execute   [D] Details   [S] Skip

 POSITIONS
 NIFTY SEP FUT    +50     +₹2,340
────────────────────────────────────────────────────────
 Daily P&L +₹2,340 / Risk limit -₹5,000
 Server ● Broker ● Market Data ●
```

### TUI functions

- Dashboard
- Strategy recommendations
- Strategy detail
- Confirm execution
- Place manual order
- Modify order
- Cancel order
- Close position
- Close all
- Kill switch
- Orderbook
- Positions
- P&L
- Risk utilization
- Logs
- Server health
- Broker-session status
- Agent chat/command mode

The TUI should use the same authenticated Hejje APIs as the Web client.

---

# 6. Core User Experience

The fundamental daily workflow:

```text
Market opens
    ↓
Hejje determines current regime
    ↓
Candidate strategies generate signals
    ↓
Strategies ranked by historical + current-context performance
    ↓
News/event/context adjustments applied
    ↓
Risk-adjusted recommendation produced
    ↓
User sees:
TRADE / CAUTION / WAIT / AVOID
    ↓
User or LLM agent prepares trade
    ↓
Risk engine validates
    ↓
User confirms OR automation policy authorizes
    ↓
Execution daemon places order
    ↓
Hejje monitors and manages trade
    ↓
Trade reconciled with strategy + performance statistics
```

---

# 7. Information Architecture

Primary navigation:

1. **Today**
2. **Pulse**
3. **Strategies**
4. **Positions**
5. **Lab**
6. **Hejje AI**

Secondary sections:

- Orders
- Trades
- Portfolio/Funds
- Risk
- Broker
- Server
- Logs
- Settings

---

# 8. Today Screen

The Today screen should answer:

> **What should I consider trading right now?**

## 8.1 Market header

Example:

```text
NIFTY          +0.62%
BANK NIFTY     +0.31%
INDIA VIX      14.1

Market Regime
TRENDING ↑

Breadth
POSITIVE

Event Risk
MEDIUM
```

## 8.2 Best Hejje

The most important card:

```text
NIFTY — Opening Range Breakout + VWAP

HEJJE SCORE                      87 / 100

Direction                        LONG
Signal                           ACTIVE
Confidence                       HIGH

Backtested expectancy            +0.42 R
Historical win rate              61%
Similar-regime win rate          69%
Profit factor                    1.74
Max drawdown                     -9.2 R

Market regime                    +6
News context                     +2
Event risk                       -1

Entry                            24,930
Stop                             24,885
Target                           25,025

Risk                             ₹1,500
Expected reward                  ₹3,150

[ EXECUTE ]     [ DETAILS ]     [ SKIP ]
```

## 8.3 Ranked opportunities

Example:

| Rank | Instrument | Strategy | Score | Direction | Status |
|---|---|---|---:|---|---|
| 1 | NIFTY | ORB + VWAP | 87 | Long | Trade |
| 2 | ICICIBANK | PDH Breakout | 83 | Long | Trade |
| 3 | BANKNIFTY | VWAP Reversion | 76 | Short | Caution |
| 4 | RELIANCE | Momentum Breakout | 64 | Long | Wait |

---

# 9. Strategy Library

## 9.1 Initial strategy families

### Trend / Momentum

- Opening Range Breakout
- Opening Range Breakdown
- VWAP Trend Continuation
- EMA Pullback
- Previous Day High Breakout
- Previous Day Low Breakdown
- Momentum breakout
- Volume breakout
- Gap-and-Go

### Mean Reversion

- VWAP Reversion
- Bollinger Band Reversion
- RSI Reversion
- Gap Fill
- Intraday overextension reversal

### Index / Futures

- NIFTY ORB
- BANKNIFTY ORB
- Trend-following futures
- Opening gap strategies
- Breadth-confirmed index setups

### Options — later phases

- Directional option buying
- Defined-risk spreads
- Iron condor
- Iron fly
- Short straddle/strangle
- Expiry strategies
- Volatility-event strategies

Options strategies should require stronger suitability/risk controls than cash or futures strategies.

---

# 10. Strategy Definition

Strategies should be stored in structured form.

Example:

```yaml
name: nifty_orb_vwap
version: 3

universe:
  - NIFTY

timeframe: 5m

entry:
  all:
    - close > opening_range_high
    - close > vwap
    - relative_volume > 1.4

direction: long

stop:
  type: opening_range_low

target:
  type: risk_multiple
  value: 2.0

trade_window:
  start: "09:30"
  end: "12:00"

max_trades_per_day: 1

regime_preferences:
  trending: preferred
  ranging: avoid

event_rules:
  high_risk_event_within_minutes: 15
  action: block
```

The definition must be deterministic and executable without an LLM.

---

# 11. Strategy Versioning

Every material strategy change creates a new version.

Example:

```text
ORB v1
ORB v2 — Added volume filter
ORB v3 — Added VWAP confirmation
ORB v4 — Changed stop methodology
```

Hejje should compare versions objectively.

Example:

```text
                 v2          v3
Profit Factor    1.48        1.74
Expectancy       .25R        .42R
Max DD           11.4R       9.2R
Trades           2,012       1,842

Verdict:
v3 improved risk-adjusted performance,
but reduced the trade sample by 8.4%.
```

---

# 12. Backtesting Engine

Backtesting is central to Hejje.

## 12.1 Required metrics

For each strategy:

- Total trades
- Winning trades
- Losing trades
- Win rate
- Loss rate
- Average win
- Average loss
- Win/loss ratio
- Expectancy
- Profit factor
- Total return
- CAGR where meaningful
- Sharpe
- Sortino
- Maximum drawdown
- Maximum drawdown duration
- Consecutive wins
- Consecutive losses
- Average holding time
- Largest win
- Largest loss
- R-multiple distribution
- Monthly performance
- Day-of-week performance
- Time-of-day performance

---

## 12.2 Realistic transaction costs

Backtests should account for configurable estimates of:

- Brokerage
- STT
- Exchange transaction charges
- GST
- SEBI charges
- Stamp duty
- Slippage
- Bid/ask spread

Example:

```text
Gross strategy return       28.4%
Estimated execution costs   -8.7%
Net strategy return         19.7%
```

Costs must be visible.

---

## 12.3 Backtest quality

Hejje should detect and warn about:

- Look-ahead bias
- Survivorship bias
- Insufficient sample size
- Overfitting
- Excess parameter tuning
- Unrealistic fills
- Unrealistic liquidity
- Missing data
- Corporate-action distortion
- Extremely concentrated performance

---

## 12.4 Train / validation / test

Strategies should support:

```text
Development period
    ↓
In-sample backtest
    ↓
Validation period
    ↓
Out-of-sample test
    ↓
Walk-forward evaluation
    ↓
Paper trading
    ↓
Live deployment
```

The Strategy Score should weight out-of-sample performance more heavily than optimized in-sample results.

---

# 13. Market Regime Engine

Historical strategy performance is only useful if matched with relevant market conditions.

Hejje should classify each market session and historical period.

Possible regime dimensions:

### Trend

- Strong uptrend
- Uptrend
- Range
- Downtrend
- Strong downtrend

### Volatility

- Very low
- Low
- Normal
- High
- Extreme

### Opening behavior

- Gap up
- Gap down
- Flat
- Gap continuation
- Gap rejection

### Breadth

- Strong positive
- Positive
- Mixed
- Negative
- Strong negative

### Intraday structure

- Trend day
- Range day
- Reversal day
- High-volatility chop
- Low-volatility compression

### Event environment

- Normal
- Earnings-heavy
- RBI
- Fed
- Budget
- Macro-event session
- Expiry session

Hejje should answer:

> **How has this strategy historically performed in days resembling today?**

---

# 14. Hejje Score

The Hejje Score is the primary strategy-ranking mechanism.

Range:

```text
0–100
```

It must be explainable.

Potential components:

```text
Historical robustness
Out-of-sample performance
Expectancy
Profit factor
Drawdown
Consistency
Sample size
Current regime compatibility
Recent live performance
Slippage sensitivity
Instrument compatibility
News context
Event compatibility
```

Example:

```text
Base Backtest Score                  78

Current regime                      +6
Recent live performance             +3
Instrument compatibility            +2
News context                         +2
Event risk                           -4

FINAL HEJJE SCORE                    87
```

Avoid presenting a mathematically precise score unless its methodology is documented.

---

# 15. Recommendation States

Hejje should reduce strategy evaluation to four primary states:

## TRADE

The strategy exceeds all confidence/risk thresholds.

## TRADE WITH CAUTION

The strategy is viable but context contains meaningful risk.

## WAIT

Setup is incomplete or timing is poor.

## AVOID

The trade violates strategy, event, liquidity, or risk criteria.

---

# 16. Pulse

Pulse answers:

> **What kind of market are we trading today?**

It is not intended to become a generic financial-news portal.

## 16.1 Technical Pulse

Possible inputs:

- Index trend
- Market breadth
- Advance/decline
- VWAP relationship
- Volume
- Relative volume
- Volatility
- India VIX
- Sector rotation
- Futures basis
- Gap behavior
- Support/resistance
- Momentum

Output:

```text
Technical Pulse
BULLISH — STRONG
```

---

## 16.2 Market Pulse

Example:

```text
Market regime         Trending ↑
Volatility            Moderate
Breadth               Positive
Banking               Strong
IT                    Neutral
Midcaps               Strong
Global context        Neutral
```

The exact inputs should be configurable and data-source dependent.

---

# 17. News Bias

News should be used as context, not as an unrestricted “LLM reads headlines and trades” feature.

## 17.1 News indicator

Every relevant instrument may have:

```text
STRONGLY BULLISH
BULLISH
NEUTRAL
BEARISH
STRONGLY BEARISH
```

Example:

```text
News Bias
BULLISH +0.6

Why:
• Material contract announcement this morning
• Positive management guidance
• No material negative developments
• Price/volume reaction confirms positive interpretation
```

## 17.2 News scoring dimensions

The news engine should consider:

- Instrument relevance
- Sector relevance
- Recency
- Source reliability
- Materiality
- Novelty
- Confirmation by multiple sources
- Expected market impact
- Current price reaction
- Volume reaction
- Whether the news was already known/discounted

The LLM may classify and summarize news, but Hejje should retain the source items and evidence.

---

# 18. Event Risk Engine

Events are different from news sentiment.

A stock may have:

```text
News Bias        BULLISH
Event Risk       HIGH
```

Both should be shown independently.

## 18.1 Instrument events

- Quarterly results
- Earnings call
- Board meeting
- Dividend announcement
- Ex-dividend date
- Bonus
- Stock split
- Buyback
- AGM/EGM
- Corporate action
- Regulatory ruling
- Major court event
- Promoter transaction
- Block/bulk deal
- Index inclusion/removal

## 18.2 Market events

- RBI policy
- Fed decision
- India CPI
- US CPI
- Employment data
- Budget
- Election results
- Major geopolitical events
- F&O expiry
- Index rebalance

## 18.3 Event proximity

Example:

```text
Q2 Results
Today 16:00

Event Risk:
HIGH
```

Strategy-specific event rules are preferable to global rules.

Example:

> Mean-reversion strategy historically performs poorly on earnings days → block.

Another strategy may explicitly target post-results momentum and therefore treat the same event differently.

---

# 19. Strategy Context Card

On every strategy screen show:

```text
CONTEXT

Technical Fit       🟢 Strong
Market Regime       🟢 Favorable
News Bias           🟢 Bullish +0.4
Event Risk          🟠 High
Sector              🟢 Strong

Next Event
Q2 Results — Today 16:00

Net Context Impact
-1 point
```

The user should be able to expand each indicator and inspect the evidence.

---

# 20. Explainability — “Why this trade?”

Every recommendation should expose:

## Supporting evidence

Example:

```text
✓ Opening range broken
✓ Price above VWAP
✓ Relative volume 1.8×
✓ Market breadth positive
✓ Banking sector outperforming
✓ Strategy performs strongly in current regime
```

## Risks

```text
⚠ India VIX rising
⚠ RBI speech in 35 minutes
⚠ Reward/risk has fallen from 2.3 to 1.65
```

## Final decision

```text
TRADE WITH CAUTION
```

The explanation should derive from structured evidence, not purely generated prose.

---

# 21. Strategy Comparison

Users should compare strategies directly.

Example:

| Metric | ORB | VWAP Pullback | EMA Breakout |
|---|---:|---:|---:|
| Trades | 1,842 | 922 | 1,305 |
| Win rate | 59% | 67% | 52% |
| Profit factor | 1.74 | 1.51 | 1.91 |
| Expectancy | +0.31R | +0.22R | +0.38R |
| Max DD | -9.2R | -6.1R | -13.4R |
| Similar-regime performance | Strong | Medium | Strong |
| Hejje Score | 87 | 78 | 84 |

Hejje AI may then explain:

> EMA has the highest expectancy, but ORB has the strongest risk-adjusted performance in the current regime.

---

# 22. Natural-Language Strategy Builder

A user should eventually be able to say:

> Buy NIFTY when price closes above the first 15-minute high, provided it is above VWAP and relative volume exceeds 1.5. Stop below the opening range and target 2R.

The LLM converts this into deterministic strategy configuration.

The generated strategy must be presented for review before activation.

Workflow:

```text
Natural-language description
    ↓
LLM creates structured strategy
    ↓
Schema validation
    ↓
User reviews rules
    ↓
Backtest
    ↓
Out-of-sample test
    ↓
Paper mode
    ↓
Live eligibility
```

LLM-generated code or rules must never become live immediately.

---

# 23. Strategy Lab

The Lab is where users research and develop strategies.

Functions:

- Create strategy
- Clone strategy
- Version strategy
- Backtest strategy
- Compare variants
- Parameter sweep
- Walk-forward test
- Out-of-sample validation
- Analyze trade distribution
- Inspect individual historical trades
- Paper deploy
- Promote to live
- Retire strategy

---

# 24. Agent-Assisted Strategy Discovery

Advanced functionality:

User:

> Improve my NIFTY ORB strategy.

Agent may generate candidate experiments:

```text
Base ORB
ORB + VWAP
ORB + relative volume
ORB + gap filter
ORB + VIX regime
ORB + breadth filter
ORB + VWAP + relative volume
...
```

The experiment engine—not the LLM—runs the backtests.

Hejje should warn aggressively about data mining and overfitting.

Agent recommendations should prioritize:

- out-of-sample improvement,
- robustness,
- simplicity,
- adequate trade count,
- lower drawdown,

rather than maximum in-sample return.

---

# 25. Live-vs-Backtest Drift

Hejje must continuously compare actual live performance with historical expectations.

Example:

```text
VWAP Reversion

Backtested win rate          61%
Paper/live trailing 60       47%

Expected expectancy          +0.28R
Recent expectancy            -0.07R

STATUS
DEGRADING
```

Possible automated actions:

- Lower Hejje Score
- Alert user
- Reduce allowed size
- Move to paper mode
- Pause strategy

Automatic pausing should occur only when predefined statistical/risk thresholds are met.

---

# 26. Execution Modes

Inspired partly by OpenAlgo's Live/Analyzer separation.

Hejje should have clearly separated environments:

## BACKTEST

Historical simulation only.

## PAPER

Real market data, simulated orders and fills.

## CONFIRM

Signals are generated automatically, but user must approve each trade.

## AUTO

Eligible strategies can execute automatically within policy.

Modes must be impossible to confuse visually.

Production should prominently show:

```text
● LIVE
```

---

# 27. Automation Levels

Each strategy or account can have an autonomy level.

## Level 0 — Research

Agent can inspect only.

## Level 1 — Recommend

Agent can produce recommendations.

## Level 2 — Prepare

Agent can create an executable order proposal.

## Level 3 — Confirm & Execute

Agent prepares the order; human confirmation is required.

## Level 4 — Auto Execute

Strategy signals execute automatically if deterministic controls pass.

## Level 5 — Autonomous Management

Entry, modification, stop management, exit, and strategy lifecycle actions can occur automatically within explicit policy.

MVP should prioritize Levels 0–3.

---

# 28. LLM Agent Architecture

Hejje should expose a strongly typed tool surface.

Example agent tools:

```text
get_market_snapshot()
get_market_regime()
get_news_context()
get_event_calendar()

list_strategies()
get_strategy()
get_strategy_rankings()
get_strategy_backtest()
compare_strategies()
get_strategy_signal()

get_positions()
get_orders()
get_trades()
get_account_risk()
calculate_position_size()

prepare_order()
submit_order_intent()
modify_order_intent()
cancel_order_intent()
close_position_intent()

run_backtest()
create_strategy_draft()
clone_strategy()
compare_strategy_versions()
```

Important distinction:

```text
submit_order_intent()
```

is preferable to giving the LLM raw:

```text
broker.place_order()
```

The intent passes through validation and risk controls.

---

# 29. Agent Decision Object

Agents should consume a compact structured decision object.

Example:

```json
{
  "instrument": "NIFTY",
  "strategy": "orb_vwap_v3",
  "score": 87,
  "decision": "TRADE",
  "direction": "LONG",
  "entry": 24930,
  "stop": 24885,
  "target": 25025,
  "risk_rupees": 1500,
  "regime": "TRENDING_UP",
  "news_bias": 0.4,
  "event_risk": "LOW",
  "signal_valid_until": "10:42:00",
  "hard_blocks": []
}
```

This keeps agent reasoning simple and reduces hallucination risk.

---

# 30. Order Intent Model

Remote callers should generate a Hejje order intent.

Example:

```json
{
  "strategy_id": "orb_vwap_v3",
  "signal_id": "sig_12345",
  "instrument_id": "NIFTY26SEP...",
  "side": "BUY",
  "desired_quantity": 50,
  "order_type": "LIMIT",
  "limit_price": 24930,
  "stop_price": 24885,
  "target_price": 25025,
  "max_risk_rupees": 1500,
  "reason": "STRATEGY_SIGNAL"
}
```

The execution server then independently verifies the intent.

---

# 31. Risk Engine

The risk engine sits above every broker adapter.

It is authoritative and deterministic.

## 31.1 Account controls

- Maximum loss per day
- Maximum realized loss
- Maximum total loss including unrealized P&L
- Maximum capital deployed
- Maximum margin utilization
- Maximum open positions
- Maximum gross exposure
- Maximum directional exposure
- Maximum number of trades/day

## 31.2 Trade controls

- Maximum risk/trade
- Maximum quantity
- Maximum notional
- Minimum risk/reward
- Mandatory stop
- Maximum stop distance
- Minimum liquidity
- Maximum spread
- Maximum slippage tolerance

## 31.3 Strategy controls

- Max trades/day
- Max concurrent trades
- Allowed symbols
- Allowed segment
- Allowed weekdays
- Allowed trading hours
- Minimum Hejje Score
- Minimum sample size
- Maximum drawdown threshold
- Event restrictions

## 31.4 Behavioral protections

Example:

```text
Stop trading after 3 consecutive losses
No new trade after 14:45
No averaging down
No re-entry within 10 minutes
No new position if daily loss > ₹5,000
```

These rules cannot be overridden by the LLM.

---

# 32. Kill Switch

The application needs a prominent kill switch.

Options:

```text
STOP NEW ORDERS
```

and separately:

```text
CANCEL ALL OPEN ORDERS
```

and:

```text
CLOSE ALL POSITIONS
```

The final action should require deliberate confirmation depending on user configuration.

The kill switch must be available from:

- Web
- TUI
- API
- local execution-server CLI

---

# 33. Smart Orders

Inspired by OpenAlgo.

Hejje should support position-aware order intents.

Example:

User wants:

```text
Target position: +100 INFY
```

Current position:

```text
-50 INFY
```

Execution planner determines that a purchase of 150 is required.

This avoids forcing callers/agents to calculate position deltas.

---

# 34. Basket / Multi-Leg Orders

Support grouped execution:

- Multiple equity orders
- Futures hedge
- Option spreads
- Multi-leg options strategies

Basket execution should track:

- basket ID,
- child orders,
- partial completion,
- failure,
- rollback/hedging policy.

Multi-leg strategies need explicit handling for legging risk.

---

# 35. Order Splitting

Borrow the concept from OpenAlgo.

If an order exceeds configured quantity/liquidity limits, the execution planner may split it into child orders.

Controls:

- Maximum child size
- Delay between children
- Price movement tolerance
- Cancellation conditions
- Overall execution deadline

---

# 36. Order State Machine

Never assume that a successful HTTP request equals a successful trade.

Internal order states should include:

```text
CREATED
VALIDATING
RISK_REJECTED
READY
SUBMITTING
BROKER_ACCEPTED
OPEN
PARTIALLY_FILLED
FILLED
MODIFY_PENDING
CANCEL_PENDING
CANCELLED
REJECTED
UNKNOWN
RECONCILING
```

Broker events should update the local authoritative model.

---

# 37. Idempotency

Every transactional request from a remote client must have an idempotency key.

If the network retries:

```text
execute signal ABC
```

Hejje must not accidentally submit the trade twice.

---

# 38. Broker Reconciliation

The execution daemon should periodically reconcile:

```text
Hejje orders ↔ Broker orderbook
Hejje trades ↔ Broker tradebook
Hejje positions ↔ Broker positions
```

Any mismatch becomes an alert.

Example:

```text
CRITICAL
Broker reports +100 RELIANCE.
Hejje expected +50.
```

Auto trading may be paused depending on severity.

---

# 39. Rate Limiting

The execution service must enforce the applicable broker/exchange order-rate policies before requests reach the broker.

Requirements:

- Per-account rate limiter
- Per-operation limits
- Queue visibility
- Rejection before broker call if exceeding policy
- Metrics and alerts
- Future broker-specific limits

Rate limits must be configuration-driven rather than hardcoded in strategy logic.

---

# 40. Broker Session Management

The Linux daemon owns the broker session.

It should expose status:

```text
Zerodha
CONNECTED

Session established
07:42:18

Expires
End of trading session / broker-defined lifecycle
```

If authentication is unavailable:

```text
BROKER DISCONNECTED
LIVE TRADING DISABLED
```

Strategies may continue analysis, but no production order should be attempted.

---

# 41. Server Health

Web and TUI should show:

```text
Execution Server      ● Healthy
Static IP             ● Verified
Broker                 ● Connected
Market Data            ● Streaming
Database               ● Healthy
Clock Sync             ● Healthy
Risk Engine            ● Enabled
Order Queue            0
```

---

# 42. Static IP Verification

The daemon should periodically determine its public egress IP and compare it against configured expected values.

Example:

```text
Expected primary IP    203.x.x.x
Current egress IP      203.x.x.x

STATIC IP VERIFIED
```

If it changes unexpectedly:

```text
CRITICAL
EGRESS IP MISMATCH

Live transactional API calls disabled.
```

---

# 43. High Availability

MVP may use a single server.

Later:

```text
Primary execution server
Secondary warm standby
```

The design must prevent split-brain execution.

Only one server may own the `ACTIVE_EXECUTOR` lease at a time.

The secondary IP can be used only after controlled failover and broker configuration compatibility.

---

# 44. Latency Monitoring

Borrowing from OpenAlgo's latency observability.

For every broker request capture:

```text
Client → Hejje API latency
Strategy evaluation latency
Risk validation latency
Execution-daemon queue time
Daemon → Broker API latency
Broker acknowledgement latency
Signal → Order acknowledgement
Signal → Fill
```

Example dashboard:

```text
Median order acknowledgement     82 ms
P95                               141 ms
P99                               203 ms
```

Unexpected latency should trigger warnings.

---

# 45. Market Data Architecture

MVP:

- Kite WebSocket
- Kite historical data
- Local normalized market-data events

Later the system may support independent licensed market-data providers.

Internal event:

```text
MarketTick {
    instrument
    timestamp
    last_price
    bid
    ask
    volume
    oi
}
```

Strategies must not depend directly on Kite-specific tick schemas.

---

# 46. Instrument Master / Universal Symbols

Take inspiration from OpenAlgo's normalized symbol layer.

Hejje should maintain:

```text
HejjeInstrument
BrokerInstrumentMapping
```

Example:

```text
Underlying: NIFTY
Exchange: NFO
Type: FUTURE
Expiry: 2026-09-24

Broker mapping:
Zerodha instrument_token: ...
trading_symbol: ...
```

This becomes especially important when multiple brokers are added.

---

# 47. Event-Sourced Trade History

Every significant action should produce an immutable audit event.

Examples:

```text
SIGNAL_CREATED
STRATEGY_RECOMMENDED
AGENT_RECOMMENDED
USER_APPROVED
RISK_CHECK_PASSED
ORDER_SUBMITTED
BROKER_ACCEPTED
ORDER_FILLED
STOP_MODIFIED
POSITION_CLOSED
STRATEGY_PAUSED
KILL_SWITCH_ENABLED
```

Record:

- Timestamp
- User/agent
- Strategy
- Signal
- Order intent
- Risk decision
- Broker response ID
- Client source
- Correlation ID

---

# 48. Security

## 48.1 Broker credentials

Broker API secrets/tokens should exist only on the execution infrastructure.

Never expose them to:

- Browser
- TUI config
- LLM prompts
- external strategy clients
- logs

## 48.2 Remote API

Require:

- TLS
- short-lived auth tokens
- revocable client credentials
- scopes
- rate limiting
- audit logging

Possible scopes:

```text
market:read
strategies:read
strategies:write
orders:prepare
orders:execute
orders:cancel
positions:close
risk:read
risk:write
admin
```

## 48.3 Agent credentials

Agents should receive narrowly scoped tool credentials.

Example:

Research agent:

```text
market:read
strategies:read
```

Execution agent:

```text
market:read
strategies:read
orders:prepare
```

No agent automatically receives administrative access.

---

# 49. Approval Model

Order policy examples:

```text
Manual orders             Human confirmation
Score < 80                Human confirmation
Score >= 80               Human confirmation
Approved AUTO strategy    Auto
New strategy version      Never auto
Event risk HIGH           Human confirmation
Daily loss > threshold    Block
```

Policies should be explicit and inspectable.

---

# 50. Paper / Analyzer Mode

Inspired by OpenAlgo's analyzer mode.

Paper mode should simulate:

- order acceptance,
- slippage,
- fills,
- partial fills,
- fees,
- positions,
- P&L.

It should use live market data while avoiding broker transactional APIs.

Paper and live results should remain separate.

---

# 51. Orders Screen

Show:

- Pending
- Open
- Filled
- Cancelled
- Rejected

Columns:

```text
Time
Instrument
Strategy
Side
Qty
Type
Requested price
Average fill
Status
Broker ID
Source
Agent/User
```

Allow:

- modify,
- cancel,
- inspect audit trail.

---

# 52. Positions Screen

Show:

```text
Instrument
Strategy
Qty
Entry
LTP
Unrealized P&L
Stop
Target
Risk remaining
Time held
```

Position actions:

- Modify stop
- Modify target
- Reduce
- Close
- Inspect strategy
- Explain current recommendation

---

# 53. Strategy Attribution

Every automated trade must belong to a strategy.

This enables:

```text
P&L by strategy
P&L by version
P&L by regime
P&L by instrument
P&L by weekday
P&L by time
P&L by event context
```

Manual trades can use:

```text
strategy = MANUAL
```

---

# 54. Portfolio / Account Risk Dashboard

Example:

```text
TODAY

Realized P&L          +₹3,200
Unrealized P&L        -₹600
Net P&L               +₹2,600

Daily loss limit       ₹5,000
Risk currently open    ₹2,100
Margin used            31%

Trades                 4
Wins                   2
Losses                 1
Open                   1
```

---

# 55. Post-Trade Review

Every closed trade should receive a structured postmortem.

Example:

```text
Strategy
ORB + VWAP v3

Outcome
+1.8R

Expected setup
VALID

Execution
Entry slippage     0.07%
Exit slippage      0.03%

Context
Trending day
Bullish breadth
Neutral news
No major event

Rule adherence
100%
```

For manual trades, Hejje can identify deviations from the selected setup.

---

# 56. Hejje AI

Hejje AI should function as an operating assistant over the platform.

Examples:

> What is working today?

> Why is ORB ranked first?

> Show strategies with profit factor >1.5 and max drawdown below 10R.

> Why didn't we take Reliance?

> What caused most of my losses this month?

> Compare ORB v2 and v3.

> Prepare the highest-ranked NIFTY trade with maximum ₹1,500 risk.

> Cancel the pending ICICI order.

> Close NIFTY.

For transactional actions, Hejje AI must use the same order-intent/risk architecture as every other client.

---

# 57. Performance Investigation by Agent

Example:

User:

> What lost me money this month?

Agent might report:

```text
62% of losses came from mean-reversion strategies
during high-trend sessions.

Counterfactual:
Disabling mean reversion when ADX/regime score
exceeded the configured threshold would have reduced
drawdown from ₹31,400 to approximately ₹18,700.
```

Counterfactual analysis must clearly distinguish simulated outcomes from actual results.

---

# 58. Notifications

Possible channels:

- Web
- Desktop/browser
- Email
- Telegram/WhatsApp later
- TUI notification

Events:

- Signal generated
- High-score setup
- Order rejected
- Stop triggered
- Position closed
- Daily risk threshold
- Broker disconnected
- Server unhealthy
- Static IP mismatch
- Strategy drift
- Major event approaching
- News context changed materially

---

# 59. External Signals and Webhooks

Borrowing from OpenAlgo's integration model.

Hejje should eventually expose authenticated webhook endpoints.

Sources might include:

- TradingView
- custom Python
- n8n
- external scanners
- other Hejje services

External signals produce:

```text
SignalIntent
```

not a raw broker order.

They still pass through:

```text
Validation
→ Strategy mapping
→ Risk engine
→ Execution daemon
```

---

# 60. Public / Internal API

Design Hejje as API-first.

Major resources:

```text
/api/v1/market
/api/v1/instruments
/api/v1/strategies
/api/v1/signals
/api/v1/backtests
/api/v1/context
/api/v1/news
/api/v1/events
/api/v1/orders
/api/v1/positions
/api/v1/risk
/api/v1/broker
/api/v1/server
/api/v1/agents
```

This API powers:

- Web
- TUI
- LLM tools
- custom clients
- future mobile app

---

# 61. Observability

Required logs/metrics:

### System

- CPU
- Memory
- Disk
- service uptime
- DB health
- clock drift

### Broker

- connectivity
- API errors
- rate-limit errors
- WebSocket reconnects
- login/session health

### Orders

- submitted
- rejected
- filled
- cancelled
- duplicate attempts
- latency

### Strategy

- signals
- recommendations
- executed signals
- skipped signals
- strategy P&L
- drift

### Agents

- tool calls
- proposed actions
- rejected actions
- approvals
- token/cost metrics where applicable

---

# 62. Failure Handling

Hejje should explicitly design for:

- Internet loss
- Broker API outage
- WebSocket disconnect
- Stale quote
- Server restart
- DB restart
- Partial order execution
- Broker timeout
- Unknown order status
- Duplicate remote request
- Static IP mismatch
- Invalid broker session
- Agent timeout
- LLM unavailable
- News service unavailable
- Event service unavailable

Trading safety cannot depend on LLM availability.

---

# 63. Recovery on Restart

On daemon startup:

```text
1. Acquire executor lease
2. Validate server clock
3. Verify static egress IP
4. Restore broker session / require login
5. Fetch broker orderbook
6. Fetch trades
7. Fetch positions
8. Reconcile local state
9. Restore active strategy state
10. Enable execution only after consistency checks pass
```

---

# 64. Database Domains

Suggested logical entities:

```text
User
ClientCredential
BrokerAccount
BrokerSession

Instrument
BrokerInstrumentMapping
MarketCandle
MarketTick

Strategy
StrategyVersion
StrategyParameter
StrategyDeployment

Backtest
BacktestTrade
BacktestMetric

MarketRegime
NewsItem
NewsAssessment
MarketEvent

Signal
StrategyScore
Recommendation

OrderIntent
RiskDecision
Order
OrderEvent
Trade
Position

Agent
AgentAction
Approval

AuditEvent
SystemMetric
BrokerMetric
```

---

# 65. OpenAlgo Ideas to Adopt

OpenAlgo provides useful architecture patterns for an India-focused algo platform.

Hejje should consider adopting the concepts—not necessarily its implementation—of:

## Normalized broker interface

Build core strategy/execution logic independently of Kite-specific payloads.

## Static-IP self-hosting

Keep the broker execution service on user-controlled infrastructure whose IP is directly registered with the broker.

## API-first execution

Web/TUI/external systems all communicate through a normalized Hejje API.

## Live and analyzer/paper modes

Make safe simulation structurally separate from production execution.

## Smart/position-aware orders

Allow clients to express desired final position rather than calculating every delta.

## Basket orders

Support grouped multi-order execution.

## Split orders

Allow deterministic order subdivision when needed.

## Universal instrument representation

Keep symbol mapping separate from strategies.

## WebSocket normalization

Create a common internal stream instead of leaking broker tick formats throughout the system.

## Strategy webhooks

Allow external systems to trigger Hejje signals without obtaining broker credentials.

## Rate limiting

Enforce limits inside the execution service, not inside individual strategies.

## Latency dashboard

Track broker API and execution-path latency.

## Plugin architecture

Make adding another broker an adapter implementation rather than a platform rewrite.

---

# 66. How Hejje Should Differentiate from OpenAlgo

Hejje should not simply become another broker-neutral API gateway.

Its primary differentiation should be:

### 1. Strategy intelligence

Rank strategies based on backtested quality.

### 2. Current-regime matching

Answer which historical strategy is best suited to today's conditions.

### 3. News and event context

Adjust recommendations using relevant information and event risk.

### 4. Explainable decision making

Produce Trade / Caution / Wait / Avoid.

### 5. Strategy lifecycle

Backtest → validate → paper → live → monitor → pause.

### 6. Performance drift

Detect when live results no longer resemble historical performance.

### 7. Agent-native architecture

Make every research and execution workflow operable through safe, typed LLM tools.

### 8. Human-friendly decision surface

Focus on “what should I do?” instead of exposing infrastructure complexity.

---


# 66A. Product Edge Over OpenAlgo

Hejje should not compete with OpenAlgo primarily on broker count or execution API breadth.

OpenAlgo is strongest as a broker-neutral execution and automation layer. Hejje's primary edge should be the **strategy intelligence and decision layer above execution**.

The key distinction is:

> **OpenAlgo helps run a strategy. Hejje helps decide which strategy is worth running now.**

Hejje should differentiate through the following capabilities.

## Strategy intelligence

Hejje ships with and manages a curated strategy library rather than assuming users already know which strategies they want to run.

It should continuously answer:

- Which strategies have the strongest historical evidence?
- Which strategy versions are most robust?
- Which strategies have degraded?
- Which strategies perform best under the current market regime?

## Best-strategy-now ranking

Hejje should rank strategies in real time using:

- historical robustness,
- out-of-sample performance,
- current technical fit,
- market regime,
- instrument suitability,
- recent live performance,
- news bias,
- event compatibility,
- risk eligibility.

The primary product question is:

> **What should I trade now?**

## Regime-aware strategy selection

A strategy with excellent five-year performance may still be unsuitable today.

Hejje should explicitly identify market conditions and rank strategies using their performance in historically similar conditions.

## News and event context

Hejje should incorporate:

- instrument-specific news,
- sector context,
- quarterly results,
- corporate actions,
- RBI/Fed/macroeconomic events,
- expiry and index events.

The platform should distinguish between:

```text
News Bias
```

and:

```text
Event Risk
```

These may influence the strategy recommendation independently.

## Explainable decisions

Hejje should translate quantitative evidence into a simple outcome:

```text
TRADE
TRADE WITH CAUTION
WAIT
AVOID
```

Every result must be explainable using underlying structured evidence.

## Strategy lifecycle management

Hejje owns the full strategy lifecycle:

```text
Idea
→ Definition
→ Backtest
→ Out-of-sample validation
→ Paper trading
→ Live deployment
→ Drift monitoring
→ Pause / retirement
```

## Live-vs-backtest drift

Hejje should continuously compare actual trading results against historical expectations.

If a strategy materially degrades:

- lower its score,
- warn the user,
- reduce its eligibility,
- move it to paper mode,
- or pause it based on configured rules.

## Agent-native research and operation

LLM agents should be able to:

- inspect market conditions,
- compare strategies,
- explain recommendations,
- run backtests,
- investigate losses,
- propose strategy variants,
- prepare orders,
- manage approved workflows.

The LLM remains an analyst/orchestrator rather than the source of trading truth.

---

# 66B. Recommended Technology Stack

Hejje should use a deliberately simple stack initially.

The goal is to invest engineering effort in strategy quality, execution reliability, backtesting, risk, and agent tooling rather than infrastructure complexity.

## Core backend

**Java 21 + Spring Boot**

Responsibilities:

- REST APIs
- WebSocket APIs
- strategy engine
- signal engine
- risk engine
- execution engine
- broker adapters
- order state machine
- market data normalization
- scoring
- authentication
- auditing
- LLM tool endpoints
- configuration

Recommended supporting libraries/components:

- Spring Boot 3
- Spring Web
- Spring WebSocket
- Spring Security
- Jackson
- Flyway
- PostgreSQL driver

Reactive programming should be used only where it materially helps, such as high-volume streaming, rather than as a blanket architectural requirement.

## Architectural style

Start as a **modular monolith**.

Suggested modules:

```text
market/
instruments/

strategy/
signals/
backtest/
scoring/
regime/

news/
events/

orders/
execution/
positions/
risk/

broker/
  zerodha/

agent/
llm/

auth/
audit/
system/
```

Do not start with microservices.

## Static-IP execution deployment

For the first version, run the complete backend on the Linux server with the approved static public IP.

Example:

```text
hejje-server.jar
```

This single deployment may host:

- REST API
- WebSocket server
- strategy engine
- market-data services
- risk engine
- execution engine
- Kite adapter
- agent APIs

This minimizes deployment complexity while ensuring all transactional broker calls originate from the correct static-IP environment.

A separate executor service may be extracted later if Hejje evolves into a cloud-control-plane + local-executor model.

## Web frontend

**React + TypeScript**

Recommended stack:

- React
- TypeScript
- Vite
- React Router
- TanStack Query
- TanStack Table
- lightweight financial chart library where needed

Potential primary routes:

```text
/today
/pulse
/strategies
/strategies/{id}
/lab
/orders
/positions
/trades
/risk
/agent
/system
/settings
```

The browser must never receive broker secrets.

## TUI and CLI

The TUI should be a first-class client.

Recommended implementation:

**Go + Bubble Tea**

Supporting libraries may include:

- Bubble Tea
- Bubbles
- Lip Gloss

The TUI must contain no trading logic.

It communicates with Hejje through the same authenticated REST and WebSocket APIs used by the Web frontend.

Example commands:

```text
hejje
hejje status
hejje best
hejje strategies
hejje strategy <id>
hejje positions
hejje orders
hejje cancel <order-id>
hejje close <instrument>
hejje risk
hejje kill
```

## Operational database

**PostgreSQL**

Use PostgreSQL for:

- users
- strategy metadata
- strategy versions
- signals
- recommendations
- orders
- positions
- trades
- event metadata
- audit events
- configuration
- broker/account state

## Historical market data

Use:

```text
Parquet
+
DuckDB
```

for larger historical datasets and analytical queries.

This prevents operational PostgreSQL from becoming a tick-data warehouse.

## Research and backtesting

The production trading engine remains Java-based.

For strategy research and large backtests, Hejje may use a Python worker layer:

```text
Python
Polars
NumPy
DuckDB
Parquet
```

The strategy definition format must remain language-independent so both Java and Python can evaluate the same strategy rules.

## Cache / distributed coordination

Redis is optional for the initial version.

Potential later uses:

- short-lived quote cache
- executor lease
- distributed locks
- session caching
- WebSocket fan-out

It should not become a hard MVP dependency unless required.

## Messaging

Do not introduce Kafka or another distributed message bus in the MVP.

Use a typed in-process event model.

Example internal events:

```text
MarketTickEvent
CandleClosedEvent
SignalGeneratedEvent
StrategyScoredEvent
OrderIntentCreatedEvent
RiskApprovedEvent
OrderSubmittedEvent
OrderFilledEvent
PositionChangedEvent
```

A distributed event platform can be introduced later only if scale or architecture genuinely requires it.

## Deployment

Recommended MVP deployment:

```text
Linux VM
Static IPv4
Docker
Hejje backend
PostgreSQL
Reverse proxy / TLS
```

Prefer an India-region deployment with good connectivity to the broker.

Avoid initially:

```text
Kubernetes
Kafka
microservices
complex service mesh
vector database
large agent frameworks
```

---

# 66C. LLM Provider Architecture

Hejje must be **LLM-provider agnostic**.

The trading platform should work fully when the LLM is disabled.

LLMs enhance usability, research, analysis, and orchestration, but must not be required for:

- market-data processing,
- signal generation,
- indicator calculation,
- strategy scoring,
- risk checks,
- order execution,
- stop management,
- broker reconciliation.

## Supported provider model

Hejje should support:

### OpenAI-compatible endpoints

Allow users to configure any server implementing the expected OpenAI-style API contract.

Example configuration:

```yaml
llm:
  providers:
    primary:
      type: openai-compatible
      base_url: https://api.example.com/v1
      api_key_env: HEJJE_LLM_API_KEY
      model: model-name
```

This design can support compatible providers such as:

- OpenAI API
- OpenRouter
- vLLM
- LM Studio
- compatible local gateways
- internal enterprise gateways
- other OpenAI-compatible services

## Native provider adapters

Hejje should also support native provider adapters where useful.

Initial provider abstractions:

```text
OpenAICompatibleProvider
GeminiProvider
```

Potential later adapters:

```text
AnthropicProvider
LocalProvider
```

The rest of the Hejje application must not depend on provider-specific request/response schemas.

Conceptual Java interface:

```java
interface LlmProvider {
    LlmResponse complete(LlmRequest request);
    Publisher<LlmChunk> stream(LlmRequest request);
}
```

## Consumer subscription caveat

Hejje must not assume that a consumer AI subscription automatically provides API access.

For example, ChatGPT subscriptions and Gemini consumer subscriptions may have separate API billing and credentials.

Hejje should therefore configure LLMs using explicit API credentials or compatible local/private endpoints.

## Multiple model profiles

Users should be able to route different workloads to different models.

Suggested profiles:

```text
FAST
REASONING
NEWS
RESEARCH
```

Example:

```yaml
llm:
  profiles:

    fast:
      provider: local
      model: small-fast-model

    reasoning:
      provider: primary
      model: reasoning-model

    news:
      provider: primary
      model: fast-classifier

    research:
      provider: primary
      model: larger-reasoning-model
```

This allows inexpensive/local models to handle simple explanation tasks while stronger models are used only for complex research.

---

# 66D. Role of LLMs in Hejje

LLMs should operate in four primary roles:

```text
Interface
Analyst
Researcher
Orchestrator
```

They must not be treated as the trading engine.

## 1. Natural-language interface

The user should be able to ask:

> What are the best NIFTY strategies right now?

The LLM selects appropriate Hejje tools and retrieves structured results.

Example:

```text
get_market_regime()
get_strategy_rankings()
get_event_context()
```

The strategy score is generated by Hejje's deterministic scoring engine, not invented by the LLM.

## 2. Explanation and analysis

The LLM translates quantitative evidence into understandable reasoning.

Example:

> ORB ranks above VWAP reversion because the market is currently trending and ORB's historical expectancy improves significantly in comparable regimes.

The explanation should always derive from structured Hejje data.

## 3. Strategy comparison

User:

> Why ORB instead of VWAP reversion?

The LLM may retrieve:

- historical backtests,
- current regime results,
- current signal quality,
- news context,
- event compatibility,
- risk metrics.

It then summarizes the tradeoffs.

## 4. News understanding

News is unstructured and therefore a strong LLM use case.

The LLM may classify a news item into:

```text
instrument
relevance
direction
materiality
confidence
event_type
```

Example:

```json
{
  "instrument": "ABC",
  "relevance": "HIGH",
  "direction": "POSITIVE",
  "materiality": "HIGH",
  "confidence": 0.91
}
```

This classification becomes one input into the deterministic context engine.

The LLM itself must not translate a positive headline directly into a broker order.

## 5. Event extraction

LLMs may help extract unusual events from unstructured disclosures or news.

Known scheduled events such as quarterly results should preferably come from structured sources.

## 6. Performance investigation

User:

> Why am I losing money this month?

The LLM can orchestrate analytical tools to inspect:

- P&L by strategy,
- P&L by market regime,
- P&L by time,
- event context,
- strategy versions,
- execution/slippage,
- rule adherence.

It may then explain the likely drivers.

Any counterfactual result must come from a deterministic backtest or analytics engine.

## 7. Strategy creation

User:

> Buy NIFTY when it crosses the first 15-minute high above VWAP with relative volume over 1.5.

The LLM may translate this into a structured strategy definition.

Workflow:

```text
Natural language
→ LLM-generated strategy draft
→ Schema validation
→ Human review
→ Backtest
→ OOS validation
→ Paper mode
→ Live eligibility
```

LLM-generated rules must never go directly to live execution.

## 8. Strategy research agent

The LLM may act as a research agent.

Example request:

> Improve ORB without increasing maximum drawdown.

Possible workflow:

```text
Inspect strategy
→ Analyze losing trades
→ Propose candidate filters
→ Create strategy variants
→ Run backtests
→ Compare OOS performance
→ Reject likely overfit variants
→ Present recommendation
```

The LLM proposes experiments.

The deterministic backtester produces the results.

## 9. Order preparation

User:

> Take the best NIFTY setup with maximum ₹2,000 risk.

The LLM may:

```text
get_best_strategy()
get_current_signal()
get_account_risk()
prepare_order()
```

The Hejje risk engine calculates quantity and validates constraints.

The LLM can present the resulting order proposal.

## 10. Transaction orchestration

Agents should work through intents.

Preferred tool:

```text
submit_order_intent()
```

Not:

```text
broker.place_order()
```

Execution remains:

```text
LLM / Agent
    ↓
Order Intent
    ↓
Validation
    ↓
Risk Engine
    ↓
Policy Engine
    ↓
Execution Engine
    ↓
Broker Adapter
```

## 11. Active-trade assistance

LLMs may explain or analyze active trades.

They should not be responsible for deterministic stop-loss or target management that is already part of strategy rules.

Protective logic must continue working even when:

```text
LLM unavailable
```

---

# 66E. Explicit LLM Non-Responsibilities

Do not use the LLM as the source of truth for:

- indicator calculations,
- candle calculations,
- P&L calculations,
- live tick-by-tick trading logic,
- position sizing,
- hard risk approval,
- broker payload construction,
- stop-loss enforcement,
- target enforcement,
- broker reconciliation,
- backtest metrics,
- account balances,
- regulatory controls.

The core requirement is:

> **Hejje must remain a fully functional trading system with LLM functionality disabled.**

The LLM transforms Hejje from a deterministic trading platform into an intelligent trading assistant; it does not become the trading engine.

---


# 67. MVP Scope

## Phase 1 — Execution Foundation

### Infrastructure

- Linux execution daemon
- Static-IP validation
- Zerodha Kite adapter
- Authentication/session flow
- Instruments sync
- Market-data WebSocket
- Quotes
- Historical candles
- Order placement
- Modify
- Cancel
- Order updates
- Positions
- Trades
- Funds
- Rate limiting
- Reconciliation
- Audit logging

### Clients

- Basic Web client
- Basic TUI

### Risk

- Max loss/day
- Max risk/trade
- Max position size
- Max open positions
- Trading hours
- Kill switch

### Modes

- Paper
- Confirm/live

---

# 68. Phase 2 — Strategy Platform

- Strategy definition format
- Initial strategy library
- Strategy versioning
- Backtesting engine
- Cost/slippage model
- Strategy comparison
- Strategy Score v1
- Signal engine
- Today ranking
- Strategy attribution
- Post-trade analytics

Suggested initial strategies:

1. NIFTY Opening Range Breakout
2. NIFTY Opening Range Breakdown
3. VWAP Trend Continuation
4. VWAP Reversion
5. Previous-Day High/Low breakout
6. EMA pullback

Start narrow and make the evidence strong rather than shipping dozens of strategies.

---

# 69. Phase 3 — Context

- Regime classifier
- Technical Pulse
- Market breadth
- India VIX context
- Sector relative strength
- Event calendar
- Quarterly result dates
- Macro events
- News ingestion
- News relevance
- News bias
- Event risk
- Strategy-context scoring

---

# 70. Phase 4 — Agent Layer

- Hejje AI chat
- Read-only agent tools
- Strategy-comparison agent
- Performance-analysis agent
- Order-preparation tool
- Human-confirmed execution
- Natural-language strategy builder
- Backtest orchestration
- Strategy-experiment agent

---

# 71. Phase 5 — Advanced Automation

- Auto mode
- Strategy-specific autonomy
- Automatic strategy pausing
- Live-vs-backtest drift
- Smart orders
- Basket orders
- Split orders
- Multi-leg strategies
- Options analytics
- Multi-broker adapters
- Active/standby execution

---

# 72. Suggested MVP Screens

## Web

```text
Login
Today
Strategy Detail
Strategies
Orders
Positions
Trades
Risk
Broker / Server
Settings
```

## TUI

```text
dashboard
strategies
strategy <id>
positions
orders
order <id>
cancel <id>
close <instrument>
risk
broker
server
logs
kill
```

---

# 73. Initial Today Screen Recommendation Model

For MVP:

```text
Strategy Backtest Quality
          ×
Current Technical Compatibility
          ×
Signal Validity
          ×
Risk Eligibility
          =
Recommendation
```

News/event scoring can arrive in Phase 3.

This ensures Hejje can become useful before building expensive external-data integrations.

---

# 74. Source-of-Truth Hierarchy

For live trading:

```text
Broker
    ↓
Execution Daemon
    ↓
Hejje Database
    ↓
Web/TUI/Agents
```

For strategy state:

```text
Strategy Engine
    ↓
Signal Store
    ↓
Recommendation Engine
```

Never trust browser state as authoritative.

---

# 75. Key Non-Functional Requirements

## Reliability

A frontend crash must not affect an active strategy or protective order.

## Security

No broker secret reaches the browser or LLM.

## Auditability

Every live trade must be attributable to a strategy/user/agent and reason.

## Determinism

The same strategy inputs should produce the same strategy signal.

## Explainability

Every recommendation score should be decomposable.

## Recoverability

A restarted daemon must reconstruct broker state.

## Portability

Core platform should not depend permanently on Zerodha.

## Agent compatibility

Every core workflow should eventually be available through typed APIs.

---

# 76. Success Metrics

## Product

- Time from opening strategy screen to confident decision
- Percentage of recommendation decisions inspected/executed
- Percentage of sessions resulting in “No Trade”
- Strategy ranking usefulness

## Trading-system quality

- Signal-to-order latency
- Broker error rate
- Order-state reconciliation failures
- Duplicate-order rate
- Risk-control violations
- Static-IP failures

## Strategy quality

- Live vs backtest expectancy
- Live drawdown vs expected
- Strategy survival rate after paper stage
- Percentage of live strategies paused due to drift

## Agent quality

- Correct tool selection
- Transaction proposals rejected by risk engine
- Human approval rate
- Unintended/duplicate execution count — target zero

---

# 77. Explicit Non-Goals for Early Versions

Hejje MVP is not:

- A social trading network
- A strategy marketplace
- An advisory service
- A generic financial news reader
- A Bloomberg replacement
- An HFT platform
- A copy-trading system
- A portfolio-management platform
- A fully autonomous “AI trader”
- A multi-user commercial algo-provider platform

The initial product should be designed as a **personal, user-operated trading system**.

If the product later distributes strategies, executes for unrelated users, provides investment advice, or becomes a commercial algo-provider platform, the regulatory/compliance model must be reassessed before those features are launched.

---

# 78. Key Product Thesis

The long-term value of Hejje is not merely broker automation.

Broker automation is infrastructure.

Hejje's product advantage should be the decision layer:

```text
What is happening?
        ↓
Which strategies work here?
        ↓
Which has the best evidence?
        ↓
What is the risk?
        ↓
What could invalidate it?
        ↓
Should I trade?
        ↓
Execute safely.
```

The intended experience is:

> **Hejje finds the next move; the user remains in control of the risk.**

---

# 79. References / Architecture Inputs

The following public material informed the regulatory and architecture portions of this PRD:

1. SEBI — *Safer participation of retail investors in Algorithmic trading*, Circular dated 4 February 2025.
2. NSE — *Implementation Standards for safer participation of retail investors in Algorithmic trading*, including static-IP/API access requirements.
3. Zerodha Support — Static IP requirements for Kite API-based order placement.
4. Zerodha Kite Connect API FAQs — Static IP, API sessions, and order-rate information.
5. OpenAlgo documentation and GitHub repository — broker abstraction, self-hosted static-IP execution, REST APIs, WebSockets, smart orders, basket/split orders, analyzer mode, plugins, and latency monitoring.

Current regulations, exchange standards, and broker requirements should be revalidated before production launch because they may change.

