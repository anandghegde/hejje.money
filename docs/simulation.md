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
live Parquet history under `data/candles` and `data/ticks` mounted read-only):

```bash
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.sim.yml up -d hejje-sim
```

Locally: `java -jar hejje.jar --spring.profiles.active=sim` with its own `HEJJE_DB_URL` and `HEJJE_DATA_DIR`.

## Not yet

Candle replay into the market pipeline, the `/sim/sessions` API with play/pause/step/speed, replay fills and the
look-ahead guard arrive in M7.2; the result hash over a session's fills is defined there too. The bot protocol (M7.3),
the TUI harness (M7.4) and the leaderboard (M7.5) follow.
