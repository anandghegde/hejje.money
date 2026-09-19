# Options (Phase 5, M5.4)

`money.hejje.options`: analytics, strategy legs, execution and risk for index options. Off the trading core's critical
path: without option instruments or quotes, nothing else changes.

## Pricing and the chain

- **Model**: Black-76 on the futures price (European options on futures; NIFTY/BANKNIFTY options settle against the
  index, and the nearest future expiring on or after the options is the forward). Time to expiry is measured to 15:30
  IST on the expiry date in years of 365 days; rates are continuous (`hejje.options.risk-free-rate`, 6.5 %).
- **Implied volatility**: bisection on the (monotonic) price between 0.01 % and 500 %; none when the price is below the
  discounted intrinsic value or at/above the discounted forward (calls) / strike (puts).
- **Greeks**: delta, gamma, vega per volatility point (0.01), theta per calendar day.
- **Chain** (`GET /instruments/options/chain?underlying=NIFTY&expiry=`, default the nearest expiry): per strike the call
  and put with last/bid/ask/OI/volume, IV and greeks; the forward and its source (the future, else the index spot as a
  proxy with a note), the ATM strike (nearest the forward), put/call ratios of OI and volume, and **max pain** (the
  strike, among the chain's, where the total expiry payout to option holders is smallest). A request subscribes the
  chain's instruments to market data. `GET /instruments/options/expiries?underlying=` lists the upcoming expiries.

## Strategy legs

`family: options` strategies signal on the underlying (their `universe`, e.g. `NIFTY`) with the usual entry rules and
`stop`, and trade the options listed in `legs` (docs/strategy-dsl.md, "Options legs"):

```yaml
legs:
  - action: buy            # buy | sell
    option: directional    # directional (CE on a long signal, PE on a short one) | opposite | ce | pe
    strike: atm            # atm | {offset: 100} (points; positive = out of the money for the leg's type) | {delta: 0.3}
    expiry: nearest        # nearest (the first expiry still tradable today) | next | monthly
    lots: 1
    stop_pct: 30           # optional, percent of the entry premium against the leg
    target_pct: 60         # optional
    hedge_first: true      # placed (and filled) before the other legs; buy legs only
combined_exit:             # optional, on the combined P&L of all legs
  stop_rupees: 2500
  target_rupees: 4000
```

Strike selection reads the chain at execution: ATM is the strike nearest the forward; an offset moves up for calls and
down for puts from ATM to the nearest listed strike; a delta target picks the strike whose |delta| is closest (a strike
without IV uses `hejje.options.default-volatility`). Today's expiry is skipped from `hejje.options.expiry-day-cutoff`.

## Execution and management

Confirming (or AUTO-executing) an options signal opens an **options position**: the resolved legs are placed as one
ALL_OR_NOTHING basket with `CLOSE_FILLED_LEGS` rollback (docs/execution.md), hedge legs first and each filled before the
next, so a short leg is never placed before its protection. The signal is EXECUTED and the runner drops it; the
`OptionsPositionMonitor` (every `hejje.options.monitor-interval`) moves the position PENDING → OPEN when the basket
completes (entry premiums from the fills), or FAILED when the basket fails. An OPEN position closes on the first of:
force-exit time, the underlying crossing the signal's stop (for a `direction: neutral` strategy: the underlying leaving
the band around its price at the signal either way, `UNDERLYING_BAND`), a leg's premium stop or target (any leg stop
closes the whole position), the combined P&L stop or target. Closing buys back short legs first and sells long legs only once the shorts are flat (market orders, reason
STRATEGY_EXIT, exposure-reducing); realized P&L is the sum over legs. `max_trades_per_day` counts options positions.
Audit: `BASKET_CREATED`/`BASKET_FINISHED` for the legs, `OPTIONS_POSITION_OPENED`/`OPTIONS_POSITION_CLOSED`.
`GET /options/positions`, `GET /options/positions/{id}`.

## Options risk

For option orders that add exposure (the `optionsLots`, `optionsPremium`, `optionsDefinedRisk`, `optionsExpiryDay`
checks):

- at most `hejje.options.max-lots` lots per order;
- a buy's premium at risk (limit or last price × quantity) at most `hejje.options.max-premium-rupees`;
- **defined risk**: a sell needs long options of the same underlying, expiry and type at least as large as the new short
  plus existing shorts (in a basket the hedge leg fills first, so a vertical spread passes); otherwise it is refused
  unless the strategy's deployment sets `allow_undefined_risk: true`;
- no new option positions on their expiry day from `hejje.options.expiry-day-cutoff` (13:00).

The stop-based account controls (`mandatoryStop`, `minRewardRisk`, `maxStopDistance`) do not apply to option legs and
pass with a note; quantity, notional, margin, loss limits, the kill switch and the rest still apply. Margin for a
strategy's legs is checked on the whole basket with the broker's order-margin call before any leg is placed.

## Data limits and the lifecycle

The historical store has no option candles, so options strategies **cannot be backtested** (the backtester refuses
them) and are **PAPER-only until they have paper history**: DRAFT → PAPER directly (no BACKTESTED/VALIDATED), and
PAPER → LIVE needs `hejje.options.min-paper-trades` (30) closed paper options positions of the version. Backfilling
option candles (NFO option history from the broker, per strike and expiry) would lift this; it is not planned yet.
Options strategies have no Hejje Score base (no backtest), so live AUTO never executes them; a PAPER deployment at
autonomy 4-5 (a rehearsal with simulated fills) may, because `auto_strategy` waives the score for options strategies
in PAPER only (plan M6.4), so that forward paper trades collect without a daily confirmation.
