# Pulse (PRD §16, plan M3.2)

Pulse answers "what kind of market are we trading today?" with two rule-based outputs, recomputed every
`hejje.pulse.interval` (1 min) during the session and stored in `market_pulse`:

- **Technical Pulse** (PRD 16.1): `BULLISH | NEUTRAL | BEARISH` × `STRONG | MODERATE | WEAK` with a −100..+100 score.
- **Market Pulse** (PRD 16.2): the label table (regime, volatility, breadth, one row per sector index, global context).

Weights and thresholds live in `config/pulse.yaml` (`hejje.pulse.*`); sector indices in
`config/universe/sectors.yaml`. All inputs come from stored M5 candles and the regime snapshot; nothing external.

## Technical Pulse rules

Each rule yields a value in −1..+1 from its inputs; the score is the weight-normalised sum over the rules whose
inputs exist, × 100. A rule without inputs is listed as `n/a` and its weight is excluded (`coverage` reports the share
of the total weight that was available). Nothing is estimated for a missing input.

| Rule | Weight | Value |
|---|---|---|
| `index_trend` | 20 | regime trend: STRONG_UP +1, UP +0.5, RANGE 0, DOWN −0.5, STRONG_DOWN −1 |
| `index_vs_vwap` | 15 | (index − session average) / session average, in %, ±1 at ±`vwap-full-pct` (0.3 %). The index "VWAP" is the equal-weighted mean of the typical prices of today's bars (index candles carry no volume) |
| `day_change` | 10 | index change vs the previous session's close, ±1 at ±`day-change-full-pct` (0.75 %) |
| `momentum` | 10 | rate of change over the last `roc-bars` (12) M5 bars, ±1 at ±`roc-full-pct` (0.4 %); needs 3 bars |
| `breadth` | 15 | regime breadth: STRONG_POSITIVE +1 … STRONG_NEGATIVE −1 |
| `relative_volume` | 5 | nearest future's cumulative volume so far vs the average at the same time of day over the last `relative-volume-sessions` (10); confirmation = clip((rv − 1) / (`relative-volume-full` − 1)) applied in the direction of the day change (low volume contributes 0, never the opposite sign) |
| `vix` | 10 | −(VIX change on the day / `vix-full-change-pct` (5 %)), clipped; an `EXTREME` volatility regime subtracts a further 0.5 |
| `sectors` | 10 | (strong − weak) / sectors with data; a sector is STRONG when its change minus the index change ≥ `sector-strong-pct` (0.5 points), WEAK at ≤ −0.5 |
| `futures_basis` | 5 | (nearest future − index) / index in %, ±1 at `basis-neutral-pct` (0.1 %) ± `basis-full-pct` (0.15 %) |
| `gap` | 5 | regime opening: GAP_CONTINUATION ±1 in the gap's direction, GAP_REJECTION ∓1, GAP_UP +0.5, GAP_DOWN −0.5, FLAT 0 |

Direction: score ≥ `bullish-score` (20) → BULLISH, ≤ −20 → BEARISH, else NEUTRAL. Strength: |score| ≥
`strong-score` (60) → STRONG, ≥ `moderate-score` (35) → MODERATE, else WEAK.

## Market Pulse

| Row | Source |
|---|---|
| Market regime | regime trend → `Trending ↑`, `Trending ↓`, `Ranging`, `Unknown` |
| Volatility | regime volatility → `Very low`, `Low`, `Moderate`, `High`, `Extreme`, `Unknown` |
| Breadth | regime breadth → `Strong positive` … `Strong negative`, `Unknown` |
| Sector rows | each sector index's change vs its previous close and relative to the index: `STRONG`, `NEUTRAL`, `WEAK`; `UNKNOWN` for that row only when its candles are missing |
| Global context | `NEUTRAL` placeholder until a data source exists |

The sector indices are added to the default `hejje.market.watchlist` so they stream in FULL mode; a symbol
missing from the instrument master is skipped with a warning and its row is `UNKNOWN`.

## Surfaces

- `GET /api/v1/context/pulse` (current, cached for the interval) and `GET /api/v1/context/pulse/history?date=`.
- Web `/pulse`: composite, sector strength bars, Market Pulse table, VIX sparkline (from `/market/candles`), evidence.
- TUI `hejje pulse` (`--json` for the raw snapshot).
- The Today header now shows the regime trend and breadth from the regime snapshot.
