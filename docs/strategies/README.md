# Bundled strategies

The bundled definitions live in `strategies/*.yaml` (six from Phase 2, two options strategies from M5.4, eight price/volume
strategies from Phase 6 M6.3 and the 9:20 iron fly from M6.4) and are loaded at startup as versions with change note
`bundled` (see `docs/strategy-dsl.md`). Each page below records the rationale, the rules in words, the parameters,
the expected regime fit, known weaknesses and the baseline backtest results.

Parameters are conventional textbook values written before any backtest was run; they must not be tuned to the
out-of-sample data (README rule: the score weights out-of-sample results). Baseline metrics are filled in once the
historical dataset is backfilled on the VM (`docs/data.md`); until then every version stays `DRAFT`.

| Slug | Family | Universe | Direction |
|---|---|---|---|
| [`nifty_orb`](nifty_orb.md) | index | NIFTY front-month future | long |
| [`nifty_orb_breakdown`](nifty_orb_breakdown.md) | index | NIFTY front-month future | short |
| [`vwap_trend_continuation`](vwap_trend_continuation.md) | trend | NIFTY, BANKNIFTY futures | long |
| [`vwap_reversion`](vwap_reversion.md) | mean reversion | NIFTY, BANKNIFTY futures | long |
| [`pdh_pdl_breakout`](pdh_pdl_breakout.md) | trend | 10 liquid NIFTY 50 stocks | long |
| [`ema_pullback`](ema_pullback.md) | trend | 10 liquid NIFTY 50 stocks | long |
| [`cpr_breakout`](cpr_breakout.md) | index | NIFTY, BANKNIFTY futures | long |
| [`open_low_long`](open_low_long.md) | trend | NIFTY 50 constituents | long |
| [`open_high_short`](open_high_short.md) | trend | NIFTY 50 constituents | short |
| [`nifty_nr7_orb`](nifty_nr7_orb.md) | index | NIFTY front-month future | long |
| [`stock_in_play_orb`](stock_in_play_orb.md) | trend | NIFTY 50 constituents | long |
| [`nifty_intraday_momentum_long`](nifty_intraday_momentum_long.md) | index | NIFTY front-month future | long |
| [`nifty_intraday_momentum_short`](nifty_intraday_momentum_short.md) | index | NIFTY front-month future | short |
| [`supertrend_vwap`](supertrend_vwap.md) | trend | NIFTY, BANKNIFTY futures | long |
| [`nifty_orb_call_buy`](nifty_orb_call_buy.md) | options | NIFTY front-month future → ATM weekly call | long |
| [`nifty_bull_call_spread`](nifty_bull_call_spread.md) | options | NIFTY front-month future → weekly call spread | long |
| [`nifty_920_iron_fly`](nifty_920_iron_fly.md) | options | NIFTY → nearest-expiry iron fly (±200 wings) | neutral |

Options strategies trade option legs on each signal (docs/options.md); they cannot be backtested (no option candles) and
go DRAFT → PAPER directly.

The Phase 6 strategies were pre-registered in `plan/phase-6-strategy-research.md` (M6.3) with conventional
practitioner or paper values before any backtest of them ran; their results come only from the bake-off under one
protocol (`bakeoff.md`). A two-sided setup is two definitions because the backtester does not support
`direction: both`. The NIFTY 50 universes list today's constituents (`config/universe/nifty50.yaml`), so multi-year
results carry survivorship bias.
