# Strategy definition format and condition DSL

Strategies are YAML documents (PRD section 10) validated against `docs/strategy-schema.json` and the semantic rules
below. Definitions are deterministic and executable without an LLM. The parser lives in
`money.hejje.strategy.dsl` and is shared by the validator, the backtester and the live signal engine (README rule 10).

## Document

```yaml
name: nifty_orb_vwap            # ^[a-z][a-z0-9_]{2,63}$ ; also the strategy slug
family: index                   # trend | mean_reversion | index | options   (default: trend)
description: Opening range breakout confirmed by VWAP
universe:                       # at least one entry
  - NIFTY                       # bare name = alias (hejje.strategy.aliases), e.g. NIFTY -> nearest_future: NIFTY
  - "INDEX:NIFTY 50"            # canonical Hejje symbol (docs/symbols.md)
  - NSE:INFY
  - nearest_future: BANKNIFTY   # selector: active future with the earliest expiry on/after today
  - index: NIFTY BANK           # selector: INDEX:<name>
  - symbol: "NFO:NIFTY:FUT:2026-09-24"
timeframe: 5m                   # 1m | 3m | 5m | 15m | 1h   (1d is rejected: intraday only)
direction: long                 # long | short | both
entry:                          # exactly one of all: / any:
  all:
    - close > opening_range_high
    - close > vwap
    - relative_volume > 1.4
exit:                           # optional rule-based exit, evaluated at bar close
  any:
    - close crosses_below vwap
stop:                           # required
  type: opening_range_low       # opening_range_low | opening_range_high | atr_multiple | percent | points |
                                # swing_low | swing_high | prev_day_low | prev_day_high
  value: 1.5                    # required for atr_multiple / percent / points, forbidden otherwise
target:                         # optional (default none)
  type: risk_multiple           # risk_multiple | points | percent | vwap | none
  value: 2.0
trailing_stop:                  # optional
  type: atr_multiple            # atr_multiple | percent | breakeven_at_r
  value: 1.0
trade_window:                   # optional (default 09:15 .. force_exit_time); quote times
  start: "09:30"
  end: "12:00"
force_exit_time: "15:10"        # default 15:10; must be before 15:20 for MIS
max_trades_per_day: 1           # default 1
max_holding_minutes: 120        # optional
signal_validity_minutes: 5      # optional; default = until the next bar close
position_sizing:                # optional
  type: risk_based
  risk_rupees: 2000             # or risk_percent_of_capital: 0.5 ; neither = the deployment's risk_rupees
product: MIS                    # default MIS
regime_preferences:             # optional; keys are lower-case regime names (Phase 3 taxonomy)
  trending: preferred           # preferred | neutral | avoid
  ranging: avoid
event_rules:                    # optional (default allow); docs/events.md
  high_risk_event_within_minutes: 15   # window around a HIGH-risk event; required unless action is allow
  action: block                 # block -> AVOID + risk rejection | caution -> TRADE WITH CAUTION | allow
risk_overrides:                 # optional
  min_reward_risk: 1.5
  max_quantity: 100
version: 3                      # ignored: versions are assigned by the server
```

Options strategies (`family: options`) add `legs` and an optional `combined_exit`; see "Options legs" below.

Unknown keys are errors. Times must be quoted. Every structural problem is reported together as
`{path, message}` pairs (for example `entry.all[2]`, `stop.value`).

## Options legs

An options strategy signals on its underlying (`universe`, entry rules and `stop` as usual; the stop becomes the
underlying stop that closes the position) and trades up to four option legs on each signal:

```yaml
legs:
  - action: buy            # buy | sell (required)
    option: directional    # directional (CE on a long signal, PE on a short one) | opposite | ce | pe
    strike: atm            # atm | {offset: 100} (points from ATM, positive = out of the money for the leg's type) | {delta: 0.3}
    expiry: nearest        # nearest | next | monthly
    lots: 1                # default 1
    stop_pct: 30           # optional premium stop, percent of the entry premium (0-100]
    target_pct: 60         # optional premium target
    hedge_first: true      # optional; placed and filled before the other legs (buy legs only)
combined_exit:             # optional: exits on the combined P&L of all legs
  stop_rupees: 2500
  target_rupees: 4000
```

Validation: `legs` only with `family: options`, and `family: options` needs `legs`; at most four legs; `hedge_first`
only on buy legs; `combined_exit` needs legs. Options strategies cannot be backtested (no option candles) and go
DRAFT → PAPER directly; LIVE needs closed paper options positions (docs/options.md).

## Condition grammar

```
condition := expr compare expr
compare   := '>' | '<' | '>=' | '<=' | '==' | 'crosses_above' | 'crosses_below'
expr      := term (('+' | '-') term)*
term      := factor (('*' | '/') factor)*
factor    := number | ref | '(' expr ')' | '-' factor
ref       := ident [ '(' args ')' ] [ '[' int ']' ]
args      := arg (',' arg)*       arg := number | duration        duration := <int>m | <int>h | <int>d
```

- Series: `open`, `high`, `low`, `close`, `volume`. `close[1]` is one closed bar back; `[0]` is the bar that just closed.
- Identifiers are lower case. `==` compares within 1e-9. `!=` is not supported.
- `a crosses_above b` means `a[0] > b[0] and a[1] <= b[1]`; `crosses_below` is the mirror image.
- Canonical text: arithmetic is fully parenthesised by precedence and omitted defaults are filled in, so
  `close > opening_range_high` is stored as `close > opening_range_high(15m)`. The canonical text is what appears in
  evidence lists and what the definition hash is computed from.

### Indicators

| Call | Arguments | Notes |
|---|---|---|
| `sma(n)`, `ema(n)` | period | |
| `rsi(n)`, `atr(n)`, `adx(n)` | period | Wilder smoothing |
| `bb_upper(n, k)`, `bb_lower(n, k)` | period, std multiple | |
| `vwap` | — | session anchored, resets at 09:15 |
| `opening_range_high(15m)`, `opening_range_low(15m)` | duration (default 15m) | NOT_READY until 09:15 + duration |
| `relative_volume(20)` | sessions (default 20) | bar volume / mean volume of the same time slot |
| `prev_day_high`, `prev_day_low`, `prev_day_close` | — | from daily candles |
| `gap_pct` | — | (today's open − previous close) / previous close × 100 |
| `session_minutes` | — | minutes since 09:15 at the bar close |
| `session_open`, `session_high`, `session_low` | — | today's first-bar open; running high / low of today's bars so far |
| `pivot`, `cpr_top`, `cpr_bottom`, `cpr_width_pct` | — | central pivot range from the previous session's H, L, C (docs/indicators.md) |
| `supertrend(n, k)` | ATR period, band multiple | Supertrend line (Wilder ATR), continuous across sessions |
| `prev_day_nr(n)` | sessions | 1 when yesterday had the narrowest range of the last n sessions (NR7 = `prev_day_nr(7) == 1`), else 0 |
| `opening_return(d)` | duration (required) | % return from the previous close to the close of the bar ending at 09:15 + d; NOT_READY before it |
| `highest(n)`, `lowest(n)` | period | highest high / lowest low of the last n bars |

Implementations arrive in `market.indicators` (M2.2). Until an indicator is warmed up it is *not ready* and every
condition referencing it evaluates to `NOT_READY`, which never counts as true.

### Evaluation

`ConditionEvaluator.evaluate(condition, barContext)` returns `EvalResult{condition, status, observedLhs, observedRhs}`
with `status ∈ {PASSED, FAILED, NOT_READY}`. A rule set with `all:` passes when every condition is `PASSED`; `any:`
when at least one is. Observed values feed the evidence list of every signal.

## Validation

Structural (schema) errors and semantic errors are both returned as `{path, message}`:

- known indicators and arities (see table); argument kinds (period = positive whole number, duration = `15m`);
- `timeframe` intraday; every `opening_range_*` duration (including the one implied by an `opening_range_*` stop) is
  a whole number of bars of the timeframe;
- `trade_window` inside 09:15–15:30, `start < end`, `end <= force_exit_time`; `force_exit_time` before 15:20 for MIS;
- directional stops match `direction` (`opening_range_low`, `swing_low`, `prev_day_low` are long stops;
  `direction: both` needs `atr_multiple`, `percent` or `points`);
- `stop.value` / `target.value` present exactly when the type needs one, and positive;
- `target.value` not below `risk_overrides.min_reward_risk` for `risk_multiple` targets;
- bare universe names must be configured aliases; symbols must be well formed (`docs/symbols.md`);
- `event_rules.high_risk_event_within_minutes` is required when the action is `block` or `caution`.

## Versions and lifecycle

- A strategy is identified by its slug (`name`). `POST /strategies` creates it with version 1; `POST
  /strategies/{id}/versions` adds the next version (the `name` must stay the same; use clone to rename).
- Versions are immutable (a DB trigger rejects any update except `status`, and deletes). `definition_hash` is the
  SHA-256 of the canonical JSON of the parsed definition, so whitespace and comments do not create versions, and a new
  version identical to the latest is refused.
- Status transitions (`StrategyLifecycle`): `DRAFT → BACKTESTED` (a completed backtest exists) `→ VALIDATED` (an
  out-of-sample or walk-forward backtest that passes the minimum-trade rule) `→ PAPER → LIVE`; `PAUSED` from
  PAPER/LIVE and back to PAPER/LIVE; `RETIRED` from anywhere. Evidence comes through the `StrategyEvidence` port
  (the backtest module supplies it from M2.3; until then nothing leaves DRAFT). Every transition is audited
  (`STRATEGY_STATUS_CHANGED`, plus `STRATEGY_PAUSED`).
- Deployments (`strategy_deployment`) bind a version to concrete instruments in one execution mode. A `PAPER`
  deployment needs a version in `PAPER` or `LIVE`; `CONFIRM`/`AUTO` deployments need `LIVE`. Pausing or retiring a
  version pauses its deployments. Instruments default to the resolved universe.
- Bundled definitions in `strategies/*.yaml` are loaded at startup (`hejje.strategy.load-bundled`): unknown slugs are
  created, changed definitions become new versions with change note `bundled`, invalid files are logged and skipped.
