# Simulation (SIM mode, plan Phase 7)

A SIM instance replays history on a **simulation clock** with simulated fills, so bots and strategies can trade past
sessions end to end through the normal pipeline (signals, risk, orders, fills, positions) at any speed. It is a
separate Hejje process with its own database; the live instance is never switched (one mode per process).

## Mode and guards (M7.1)

- `hejje.mode=SIM` starts only under the `sim` or `dev` profile, never with `prod`, and only with
  `hejje.broker.adapter=fake`: a replay instance can never hold a real broker session (`ModeProfileGuard`).
- SIM counts as a simulated mode everywhere PAPER does (`ExecutionMode.simulated()`): version rules for deployments,
  AUTO qualification (a simulation rehearses like paper), off-session order validation, drift's move-to-paper.
- The risk pipeline stays on: SIM has its own `risk_limits` row and kill switch (V34, starting from the PAPER limits).
- The `sim` profile (application.yml) sets the fake broker, instrument sync on startup, no market stream, no tick
  recording (the history is mounted read-only), no news, no regime labelling on startup, no egress or NTP checks.

## The simulation clock

`SimClock` (`common.time`) is a `java.time.Clock` that stands still until it is set or advanced; while paused it refuses
to advance. In SIM, `ClockConfig` makes it the clock under `HejjeClock`, starting at `hejje.sim.start` (default: the
wall-clock time at startup). Business code reads time only through `HejjeClock`; M7.1 removed the last direct
`Instant.now()` reads from the strategy runner, the signal controller, the server controller and notification digests.
Two wall-clock reads remain on purpose: `Ids.newId()` (UUID v7 ordering) and the fake broker's order-id seed (an id
namespace that must not repeat across restarts of a SIM database; ids are never part of a result).

## Scheduled jobs on simulation time

In SIM, Spring's task scheduler drops every task (`SimSchedulingConfig`) and `@EnableScheduling` is off.
`SimScheduler` (module `sim`) discovers every `@Scheduled` method of the context and fires it when the simulation clock
crosses its next firing time: `fixedDelay`/`fixedRate` (placeholders resolved, ISO or `30s`/`5m` durations) from the
start plus the initial delay, `cron` in its zone. At each replay step the due jobs fire once, in the order of the
`SimJobs` registry (market pipeline first, then execution, options, signals, AUTO, approvals, regime, pulse, events,
score, drift); missed occurrences inside one step coalesce into one firing, and a fixed-rate job keeps its phase.

`SimJobs.standard()` decides RUN or SKIP for every job, with the reason. Skipped in SIM: the live market-stream
reconnect, broker session expiry and validation, the instrument sync, the news poller (it would leak information from
after the simulated time), notifications, the egress-IP and NTP checks, operational candle retention, and Spring
Modulith's wall-clock time events. A `@Scheduled` method missing from the registry fails `SimJobsCoverageTest` (Hejje's
classes), `SimModeIT` (the whole context, including library jobs) and the startup of a SIM instance.

`SimTime` (module `sim`) is the one place simulation time moves: `startAt(instant)` sets the clock and reschedules every
job from there (a new session); `advance(step)` moves the clock and fires the due jobs. The same start and steps fire
the same jobs in the same order.

## Running a SIM instance

On the VM, next to the live stack (own container, database `hejje_sim` on its own Postgres, port 127.0.0.1:8091, the
live Parquet history under `data/candles` and `data/ticks` and the exported instrument master under `data/instruments`
mounted read-only; export the master on the live instance first):

```bash
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.sim.yml up -d hejje-sim
```

Locally: `java -jar hejje.jar --spring.profiles.active=sim` with its own `HEJJE_DB_URL` and `HEJJE_DATA_DIR`.

## Historical session replay (M7.2)

**Where ticks come from.** `SessionReplay` (market module) replays a day's recorded ticks
(`<data-dir>/ticks/<day>/ticks.parquet`) when they exist, else the stored **M1 candles**: each becomes four ticks, the
open at :00, the low and the high at :20 and :40 in the conventional order (low first on an up bar, high first on a down
bar), and the close at :59, with the bar's volume split across them as cumulative day volume. The ticks go into the same
`MarketPipeline` as live ticks, so candles, indicators, regime and Pulse are computed exactly as live. A session needs M1
history for every instrument and day: index and futures M1 are in the backfill; NIFTY 50 constituents need the M1
backfill of `docs/data.md` (VM task).

**Steps.** One step is one minute; a session day is 375 steps (09:15–15:30). Within a step the simulation clock moves
to each tick's time (running the scheduled jobs due), the tick goes into the pipeline and the simulated broker, and the
replay waits until everything the tick caused has settled: every `Drainable` (the signal engine, the AUTO worker, the
simulated broker's order-update thread) is drained and no module event published during the session is still in flight,
twice in a row. In SIM the tick bus and the signal dispatch run inline on the replay thread. The step ends at the
minute's close. After the last step the clock runs to 15:36 (the 15:35 regime label) and the next day starts at 09:15.

**Speed** (`1`, `10`, `60`, `300`, `MAX` simulated minutes per wall minute) only paces steps against the wall clock, so a
session gives the same fills at any speed (`SimReplayIT`: a session replayed partly at 60× has the same result hash as
the same session at MAX).

**Fills.** In SIM the paper adapter wraps the fake broker and is fed every replayed tick (`BrokerSimulation`): a MARKET
order fills on the **next** tick at its price plus `hejje.paper.slippage-bps` (never at the price the decision already
saw), LIMIT/SL/SL-M orders when a tick crosses. Costs come from the `CostModel` and are reported as **friction paid**.
The risk pipeline, kill switch, readiness and idempotency apply unchanged.

**Sessions.** `POST /api/v1/sim/sessions` (scope `sim:run`, `docs/api.md`) creates one session at a time: it clears the
simulated ledger of earlier sessions (orders, fills, positions, signals), resets the paper broker to the session's
capital, the market pipeline and the strategy runners, applies the session's risk per trade, loss halt and maximum
positions to the SIM risk limits, and starts at the first day's 09:15, `PAUSED`. `control` plays, pauses, steps one
minute or cancels, with a speed. When it finishes the session stores its fills, friction, net P&L and a **result hash**
(SHA-256 over the fills: time, instrument, side, quantity, price). A session left open by a restart is marked `FAILED`.

**Look-ahead guard.** In SIM `MarketService` caps every read at the simulation clock: no candle whose close is after it
and no quote stamped after it, for strategies, context services (regime, Pulse, scoring) and the REST API alike. The
replay engine reads the day's history directly (it is the only reader allowed to see the future). History coverage
(`/market/history/coverage`) still reports the stored range: metadata, not prices.

**Instrument ids.** The Parquet history is keyed by instrument id, so a SIM instance must use the live ids: the live
instance writes its master with `POST /api/v1/instruments/export` to `<data-dir>/instruments/master.json`, the SIM
compose file mounts that directory read-only, and the SIM instance imports it at startup keeping the ids and mapping
every instrument to the fake broker (its own instrument sync is off in the `sim` profile).

## Not yet

The bot protocol (M7.3), the TUI harness (M7.4) and the leaderboard (M7.5) follow.
