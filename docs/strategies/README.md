# Bundled strategies

The bundled definitions live in `strategies/*.yaml` (six from Phase 2, two options strategies from M5.4) and are loaded at startup as versions with change note
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
| [`nifty_orb_call_buy`](nifty_orb_call_buy.md) | options | NIFTY front-month future → ATM weekly call | long |
| [`nifty_bull_call_spread`](nifty_bull_call_spread.md) | options | NIFTY front-month future → weekly call spread | long |

Options strategies trade option legs on each signal (docs/options.md); they cannot be backtested (no option candles) and
go DRAFT → PAPER directly.
