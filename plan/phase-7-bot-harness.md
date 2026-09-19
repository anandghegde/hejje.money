# Phase 7 — Bot Harness (replay arena and trading TUI)

Goal of the phase: let bots trade Hejje end to end and let a human watch them do it from one terminal screen. A bot is
any program that reads market state and makes trade decisions: an external script, a deployed strategy, or an
LLM-driven agent. Bots first trade historical sessions replayed at speed with simulated fills, then PAPER, and only
then, with a track record, the live account under the existing autonomy policies.

Binding rules for this phase:

- **Bots send decisions, never orders.** A decision becomes a signal of the bot's deployment (the webhook path, M5.5)
  or a position action. Hejje sizes the order from the risk per trade and the bot's stop; the risk engine, kill switch,
  loss halt and idempotency apply exactly as for strategies (PRD 59, §66E).
- **No risk bypass.** A simulation session may set its own capital, risk per trade and loss halt, but the risk pipeline
  stays on in every mode.
- **One mode per process** (plan/README). Replays run in a separate Hejje instance in the new `SIM` mode, with its own
  database; the live instance is never switched.
- **No look-ahead.** Nothing a bot or a context service reads may be later than the simulation clock.

Order: M7.1 → M7.2 → M7.3 → M7.4 → M7.5. M7.4 can start against a fixture stream once M7.3's message shapes are fixed.

---

## M7.1 SIM mode and a controllable clock  (size: L)

**Tasks.**
1. Server mode `SIM` (dev and a `sim` profile only; refuses to start with a real broker's transactional adapter). Deploy
   recipe: a second compose service `hejje-sim` on its own port and database, reading the same historical Parquet store
   read-only.
2. `SimClock` (a `java.time.Clock` that can be set, advanced and paused) injected into `HejjeClock` in SIM. Replace the
   remaining direct `Instant.now()` / `LocalDate.now()` / `System.currentTimeMillis()` calls in business code
   (`signals/internal/StrategyRunner`, `signals/internal/SignalController`, `system/ServerController`,
   `notify/internal/Digest`, `llm/LlmService`, `broker/fake/FakeBrokerAdapter`) with `HejjeClock`.
3. Simulation-time scheduling: in SIM, every `@Scheduled` job (26 today: candle close, pulse, regime, reconciliation,
   drift, options monitor, ...) is driven by a `SimScheduler` that fires it when simulation time crosses its interval,
   in a fixed order, at each replay step. A test fails when a new `@Scheduled` method is not registered with it.
4. Determinism: the same session, bot version and bot decisions give the same fills and the same result hash.

**Verification.** `./gradlew test --tests '*SimClock*' --tests '*SimScheduler*'`; a SIM run of a fixture day at speed MAX
and at 60× produces identical trades; force exit at 15:10 fires at simulated 15:10.

---

## M7.2 Historical session replay with simulated fills  (size: M)

**Tasks.**
1. `CandleReplaySource`: replays a stored session from M1 candles as synthetic ticks into the same `MarketPipeline`
   (per bar: open, then low and high in the conventional order (low first on an up bar, high first on a down bar), then
   close; bar volume split across the ticks), so candles, indicators, regime and pulse are computed exactly as live. Days
   with recorded ticks use `ReplayMarketDataSource` instead. One replay step is one minute: a session is 375 steps.
2. Backfill M1 for the NIFTY 50 constituents (today only M5/D1, docs/data.md) so stock bots can replay at one-minute
   resolution.
3. Session API (scope `sim:run`): `POST /sim/sessions {dates | from/to, instruments | universe, capitalRupees,
   riskPerTradeRupees, lossHaltRupees, maxPositions, bots[]}`; `POST /sim/sessions/{id}/control {play | pause | step |
   speed: 1 | 10 | 60 | 300 | MAX}`; `GET /sim/sessions/{id}` (progress `step/375`, state). Multi-day runs play the
   sessions in order.
4. Fills through `PaperBrokerAdapter` on replayed quotes (market at last price plus `hejje.paper.slippage-bps`,
   LIMIT/SL when a tick crosses); costs from `CostModel`, reported as "friction paid".
5. Look-ahead guard: `MarketService` candle and quote reads, context services and bot tools are capped at simulation
   now; a test asks for data after the clock and gets none.

**Verification.** `SimReplayIT`: a fixture session replays 375 steps, a scripted entry fills on the next tick with the
expected slippage and cost, the stop fills when crossed, and a candle read past the clock returns nothing.

---

## M7.3 Bot protocol  (size: L)

**Tasks.**
1. Bots: `bot(id, name, version, kind EXTERNAL | STRATEGY | LLM, knowledge_cutoff?, allowed_modes)` and a `bot` credential
   preset (`market:read strategies:read bot:decide`). A bot is attached to a deployment like a strategy, so autonomy,
   daily budgets and drift apply to it unchanged.
2. Stream `/ws/bot` (per bot): at each decision point (every closed bar, or every N minutes per bot) one message with
   the clock, the bars just closed, quotes of the bot's universe, its positions and working orders, account figures and
   the regime and pulse snapshot. In SIM the replay waits for the bot's answer (lockstep) up to
   `hejje.sim.decision-timeout`; a timeout counts as `SKIPPED`. Latency p50/p90 is tracked per bot.
3. Decisions (WebSocket reply or `POST /bots/{id}/decisions`, idempotent per decision point):
   `{instrument, action: ENTER_LONG | ENTER_SHORT | EXIT | TAKE_PROFIT | MOVE_STOP | HOLD | NONE, stop, target?,
   confidence 0..1, thesis, stage?, scores{}?, candidates[]?}`. Entries require a stop; quantity is always Hejje's.
   Entries become signals of the bot's deployment; exits and stop moves become position actions (stops only tighten).
   Execution follows the mode: SIM and PAPER execute automatically; CONFIRM creates approvals; AUTO needs the M5.2
   eligibility (paper track record) like any strategy.
4. `bot_decision` table (every field, latency, outcome, linked signal/order/trade) and trade attribution: each trade
   carries the deciding decision id and thesis.
5. Built-in `STRATEGY` bot adapter: any deployed strategy can run as a bot in a session, so strategies and bots compete
   on the same sessions.
6. Reference bot `research/bots/example_bot.py` (standard library only): connects, trades a simple opening-range rule,
   and shows the message loop. Docs: `docs/bots.md`.
7. LLM bots: a session dated before the bot's `knowledge_cutoff` is flagged in its report, because the model may already
   know what happened that day.

**Verification.** `BotProtocolIT`: the example flow (decision point → ENTER_LONG → fill → MOVE_STOP → EXIT) in SIM; a
missing stop is refused; a timeout is SKIPPED; CONFIRM mode turns an entry into an approval; the kill switch refuses
bot entries.

---

## M7.4 Harness screen in the TUI  (size: L)

**Tasks.** `hejje harness [session-id]`, a Bubble Tea screen fed by one `/ws/harness` stream plus
`GET /sim/sessions/{id}/snapshot` on (re)connect, redrawn at most 10 times a second at any replay speed:

| Area | Content |
|---|---|
| Header | bot name, mode badge (SIM / PAPER / LIVE), fill source, data health (`HIST OK · N names · M quotes`), bot latency p50/p90, skipped count, clock; capital, pause bot, kill |
| Replay bar (SIM only) | session date and time, pause/play, speed 1× 10× 60× 300× MAX, progress `step/375` |
| Context line | regime, pulse, time to the next decision point |
| Stat tiles | day P&L, open P&L, total, capital, in use, free, risk per trade, trades, hit rate, expectancy, profit factor, max drawdown, average win/loss, average hold, friction paid, loss halt, LLM tokens and cost |
| Equity curve | braille line chart with last, peak and trough |
| Positions | symbol, side, qty, entry, LTP, stop, stop location (exchange / simulated), notional, open P&L, R, thesis, MFE, MAE; working orders below |
| Candidates | the bot's `candidates[]` ranking per side, with the one sent marked |
| Trades | time, leg, symbol, side, qty, entry, exit, P&L, hold, why (thesis), attribution |
| Log and decisions | event log; scrollable decisions table (time, stage, symbol, action, scores, confidence, latency) |

Keys: space play/pause, `1`–`5` speed, `s` step, `p` pause bot, `K` kill (existing confirmation), `c` set capital (SIM
only), tab cycles panels, `q` quits. The same screen attaches to PAPER and LIVE without the replay bar. Terminals
narrower than 160 columns stack the panels.

**Verification.** `go test ./...` with teatest golden renders at 200×50 and 120×40 from a fixture stream; replay
controls hidden outside SIM; kill asks for confirmation.

---

## M7.5 Sessions, leaderboard and promotion  (size: M)

**Tasks.**
1. Session reports stored per run: equity curve, trades, decisions, costs, bot version, decisions hash; `hejje harness
   sessions` lists them and opens one read-only.
2. Leaderboard: bots (and strategy bots) run on the same sessions with the same capital, ranked by expectancy net of
   costs with profit factor, max drawdown and trade count alongside; `GET /sim/leaderboard?from=&to=`; TUI view.
3. Promotion: a bot can be deployed in PAPER after `hejje.bots.min-sim-sessions` (20) sessions with positive expectancy;
   PAPER → LIVE follows the existing paper track-record and drift rules.

**Verification.** `LeaderboardIT` with two fixture bots on three sessions; the promotion rule refuses a bot with too few
sessions.

---

## Phase 7 exit checklist

- [ ] SIM instance replays a stored session deterministically at any speed (M7.1, M7.2)
- [ ] A reference bot trades a session end to end through the risk pipeline (M7.3)
- [ ] Harness screen shows a live replay with the reference bot (M7.4)
- [ ] Leaderboard compares at least one bot and one strategy bot on the same sessions (M7.5)
