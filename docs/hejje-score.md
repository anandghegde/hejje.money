# Hejje Score v1 (`money.hejje.scoring`)

The Hejje Score is a 0–100 ranking with a documented, reproducible formula (PRD section 14). Every number in the
breakdown comes from stored data, so the same backtest and the same market state always give the same score.

```
Base backtest score                  62.4   (components below, capped at 50 with < 30 out-of-sample trades)
Technical compatibility               +8    (all entry conditions pass on the last closed bar)
Recent paper/live performance          0    (no paper/live round trips yet)
FINAL HEJJE SCORE                     70
```

## Base score

Input: the version's **base backtest** — the newest `DONE` backtest that validates (out-of-sample slice with ≥ 30
trades and no FAIL warning), else the newest with an out-of-sample slice, else the newest completed run. No backtest
→ base 0.

Per-split components use the metrics of each split present (with at least one trade), weighted **out-of-sample 60 %,
validation 25 %, in-sample 15 %** and renormalised over the splits present (a `NONE`-split run is 100 % in-sample).
Each metric maps to 0–100 through a piecewise-linear function (clamped at the ends):

| Component | Weight | Metric → score knots |
|---|---:|---|
| Expectancy (R) | 25 % | 0R→0, 0.1R→30, 0.25R→60, 0.5R→85, 1R→100 |
| Profit factor | 20 % | 1.0→0, 1.2→40, 1.5→70, 2.0→90, 3.0→100 (no losing trade = 100) |
| Max drawdown (R) | 15 % | 0R→100, 5R→80, 10R→60, 20R→30, 40R→0 |
| Consistency | 15 % | share of months with positive net P&L, 0→0 … 1→100 |
| Sample size | 10 % | trades 0→0, 30→30, 100→60, 300→85, 1000→100 |
| Walk-forward stability | 10 % | sample std-dev of the test windows' expectancies: 0→100, 0.25R→70, 0.5R→40, 1R→0; fewer than two windows with trades = neutral 50 |
| Slippage sensitivity | 5 % | the backtest re-run with 2× slippage (at least +5 bps); % drop in overall expectancy: 0→100, 25→70, 50→40, 100→0; not computable = neutral 50 |

`base = Σ weight × component score`, clipped to 0–100, then **capped at 50 when the out-of-sample slice has fewer
than 30 trades** (the cap is stated in the breakdown). Component scores and contributions are rounded to 0.1.

## Adjusters

Adjusters implement `ScoreAdjuster` and are bounded; the breakdown lists each one's delta and evidence sentences.

| Adjuster | Bounds | Rule |
|---|---|---|
| Technical compatibility | −10 … +8 | Warm an indicator context from the last 5 days of candles of the instrument, evaluate the entry conditions on the last closed bar; `delta = round(−10 + 18 × passedShare)` (NOT_READY does not pass); no candles → 0 |
| Current regime | −10 … +10 | Two parts (docs/regime.md, `hejje.regime.adjuster.*`). Preferences: each `regime_preferences` key matching the current snapshot adds +3 (`preferred`) or −6 (`avoid`), that part clipped to [−6, +3]. Similar-regime performance: the base backtest's expectancy in the current `trend × volatility` bucket minus its overall expectancy, ±7 at ±0.5R (linear, clipped), only with ≥ 10 similar trades. Regime unknown → 0 with the reason |
| Event risk | −8 … 0 | `docs/events.md` proximity level for the instrument: HIGH −8, MEDIUM −3, LOW 0; 0 with the reason when the calendar is unavailable |
| Recent paper/live performance | −5 … +5 | Last 20 round trips attributed to the strategy in the current mode (fills paired per instrument until flat) vs the base backtest's expectancy in money: ratio ≥ 1 → +5, ≥ 0.5 → +2, ≥ 0 → −2, < 0 → −5; no round trips → 0 |

M3.4 adds the news-context adjuster as one more `ScoreAdjuster` bean (PRD 14 example).

`final = clip(round(base) + Σ deltas, 0, 100)`.

## Storage and refresh

Scores are rows in `strategy_score` per (version, instrument): base, cap, components, adjustments, final, the base
backtest id and the time. They are recomputed when a backtest finishes (`BacktestFinished` event) and every five
minutes during the session for versions with enabled deployments; `POST /strategies/{id}/score/recompute` forces one.
The slippage re-run is cached per backtest id.

## Comparison

`GET /strategies/compare?versionIds=` renders the PRD 21 table from each version's base backtest (overall metrics)
and headline score (best latest instrument score); "similar-regime performance" is a Phase 3 placeholder.
`GET /strategies/{id}/versions/compare?a=&b=` renders the PRD 11 table with per-metric change percentages and a
templated verdict naming the largest improvement and the largest regression (lower drawdown counts as better).
