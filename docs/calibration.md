# Calibration (plan Phase 9, M9.2)

A probability is worth acting on only if it means something: of the answers that said 0.7, about 70 % should come true.
Hejje measures that for every Jev purpose and for bots' entry confidence before any such number may change a decision.
This page fixes the outcome rules and the pass bar **before any result is looked at**. Changing a rule or the bar
later needs a dated note in the change log at the bottom, and results measured under the old rule are not reused.

## What is recorded

A caller that makes a probabilistic prediction records it (`CalibrationService.record`) with:

| Field | Meaning |
|---|---|
| source, source id, key | `jev` + the call id + the question key, or `bot` + the decision id + `confidence` |
| purpose, version | what is calibrated (`signal-check`, `news.direction`, `bot-stage2`, `bot:<name>` …) and the question set or bot version. Versions are never pooled |
| probability | the probability the prediction gives to the event its rule labels 1 |
| instrument, side, stop, time | what the rule needs; `stop` only for entries |
| rule | one of the four rules below |

Today's recorders: **bot entries** (`ENTER_LONG` / `ENTER_SHORT` with a `confidence`, executed or sent to an approval;
refused entries are not recorded because their stop may be invalid) under the purpose `bot:<name>` and the bot's
version. The Jev purposes record themselves as they arrive (M9.3 news, M9.5 bot and signal check).

## Outcome rules

Every rule starts at the **reference bar**: the first stored M1 candle that opens at or after the decision time. Its
open is the reference price. Nothing before the decision is used, so a label never looks back past the decision, and
nothing after the window end is used, so later data cannot change it. Windows end at the session close (15:30 IST) at
the latest.

| Rule | Used for | Label 1 (hit) | Label 0 (miss) | NONE (counted, excluded from rates) |
|---|---|---|---|---|
| `ENTRY_1R` | entry setups: bot `ENTER_*` confidence, Jev bot stage 2, signal check | with entry = reference open and R = abs(entry − stop), the price reaches +1R before −1R within **30 minutes** | −1R first; **both in one bar count as −1R first** (pessimistic); a reference open already through the stop | neither within the window |
| `DIRECTION` | direction answers (Jev bot stage 1 long/short) | the close of the last M1 bar in the **60-minute** window is above (long) / below (short) the reference open | the other way | exactly unchanged |
| `DIRECTION_NEXT_CLOSE` | news direction | from the first price after the decision to the close of that price's session, in the answer's direction. Uses M1 when stored, else the D1 candle of the session (open → close) | the other way | unchanged |
| `EXIT` | exit answers (Jev bot `exit_now`, `take_profit`) | over the **15-minute** window the price moves against the position (reference → worst low/high) by more than it moves for it (reference → best high/low): exiting was right | not more against than for | — |

The probability recorded is always the probability of the label-1 event: `P(+1R first)` for an entry (the bot's
confidence, P(setup) for Jev), `P(up)` for a long direction question, `P(exit is right)` for `exit_now`.

Horizons are `hejje.calibration.entry-horizon-minutes` (30), `direction-horizon-minutes` (60) and
`exit-horizon-minutes` (15).

### When labels are made

- **Live and PAPER**: a nightly job at 18:45 IST (weekdays) labels every prediction whose window has passed, from the
  stored M1 candles. A prediction with no candle in its window stays pending (a backfill may still bring the data) and
  is labelled NONE after `no-data-after-days` (5).
- **SIM**: at the end of the session, before the session reports are written, from the replayed candles. The nightly
  job is skipped in SIM.
- A label is written once and never changed.

## Report

`CalibrationReport` per purpose and version over sessions in a date range (no version given: the newest one; answers
are never pooled across versions):

- **Buckets**: ten equal-width probability buckets [0, 0.1), …, [0.9, 1.0]. Each shows its count, hits and mean
  probability; a bucket with fewer than `min-bucket-count` (20) labelled answers shows **no rate**. Rates come with
  the Wilson 95 % interval.
- **Brier score**: mean of (p − outcome)² over labelled answers.
- **ECE** (expected calibration error): Σ over non-empty buckets of (bucket's share of answers) × |mean probability −
  hit rate|.
- **Top vs bottom**: the highest and lowest buckets with a rate; *separated* when the top's Wilson lower bound is above
  the bottom's Wilson upper bound.
- Counts: labelled (`n`), `none`, `pending`, distinct `sessions` of the labelled answers.

## Pass bar

A purpose and version **passes** when all of these hold:

1. at least **300** labelled answers (`min-labelled`),
2. from at least **15** distinct sessions (`min-sessions`),
3. expected calibration error at most **0.07** (`max-ece`),
4. the top populated bucket is **separated** from the bottom populated bucket (so the probability ranks outcomes).

`CalibrationService.passes(purpose, version)` is what the gates check (M9.3 shows it beside news, M9.5's signal-check
gate is refused until it passes). A constant source (every answer 0.9) cannot pass: it populates one bucket, so there
is nothing to separate, whatever its hit rate.

## Bot confidence in SIM reports and the leaderboard

Each SIM session report (M7.5) carries `confidenceCalibration`: the bot's labelled entries in that session (`n`,
`none`, `pending`, `brier`, non-empty `buckets` with counts). The leaderboard shows the bot's **pooled Brier score**
(Σ brier × n / Σ n over its reports) and the count beside expectancy. A bot that sends no confidence has no Brier.

## Surfaces

- API (`market:read`): `GET /api/v1/calibration?purpose=&version=&bot=&from=&to=`, `GET /api/v1/calibration/purposes`.
- TUI: `hejje calibration` (purposes) and `hejje calibration <purpose> [--version --bot --from --to]` (bucket table).
- Web: the Analytics page's calibration table.

## Out of scope

Recalibrating Jev (Platt scaling, isotonic regression). Only if the reports show a consistent, monotone
miscalibration, as a follow-up.

## Change log

- 2026-09-23: rules and bar registered (M9.2). Rules use M1 candles throughout (the plan's M5 close-to-close for
  direction answers is replaced by the M1 close at the window end, which is the same price at a finer grid).
