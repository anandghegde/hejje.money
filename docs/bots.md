# Bots (plan Phase 7, M7.3)

A **bot** is any program that reads market state and makes trade decisions: an external script, a deployed strategy,
or an LLM-driven agent. Bots send **decisions, never orders**: Hejje sizes every order from the risk per trade and the
bot's stop, and the risk engine, kill switch, loss halt, readiness checks and idempotency apply exactly as for
strategies (PRD 59, §66E).

## Registering a bot

`POST /api/v1/bots` (scope `strategies:write`):

```json
{ "name": "example", "version": "1.0", "kind": "EXTERNAL", "allowedModes": ["SIM", "PAPER"],
  "universe": ["NSE:INFY", "NSE:TCS"], "timeframe": "5m", "decisionEveryMinutes": null, "knowledgeCutoff": null }
```

- `kind`: `EXTERNAL` (a script), `LLM` (the same, with `knowledgeCutoff`: a SIM session dated before it is flagged,
  because the model may already know that day) or `STRATEGY` (give `strategyId`: an existing strategy runs as a bot).
- An EXTERNAL or LLM bot gets a generated **backing strategy** `bot_<name>` (`family: bot`, direction both, the bot's
  universe and timeframe, force exit 15:10). Its runner manages the bot's positions (protective stop, target, force
  exit) and never evaluates entry rules of its own. It goes DRAFT → PAPER directly (there are no rules to backtest).
- **Deploy** the backing strategy like any strategy (`POST /strategies/{id}/versions/1/deployments`: mode, instruments,
  autonomy, budgets). The bot trades through that deployment, so autonomy, daily budgets and drift apply unchanged.
- A bot's credential: `POST /api/v1/auth/clients {"name": "example-bot", "preset": "bot"}` gives `market:read
  strategies:read bot:decide` (never an order scope).

`GET /api/v1/bots`, `GET /api/v1/bots/{id}` (with `stats`: points, answered, skipped, latency p50/p90, connected),
`POST /api/v1/bots/{id}/enabled {"enabled": false}`, `GET /api/v1/bots/{id}/decisions?limit=50`.

## Decision points

Connect to `/ws/bot?token=<key>&bot=<id>` (the key needs `bot:decide`). At every closed bar of the bot's timeframe on
its universe (or every `decisionEveryMinutes` minutes of M1 bars) the bot gets one message:

```json
{ "type": "decision_point", "botId": "…", "pointId": "2026-09-09T04:10:00Z", "clock": "2026-09-09T04:10:00Z", "mode": "SIM",
  "timeframe": "M5", "bars": [ { "instrument": "NSE:INFY", "openTime": "…", "open": 1504.2, "high": 1506.8, "low": 1504.0,
  "close": 1506.1, "volume": 25000 } ], "quotes": { "NSE:INFY": { "last": 1506.1, "ts": "…" } },
  "positions": [ { "instrument", "side", "quantity", "entry", "stop", "target", "status" } ], "workingOrders": [ … ],
  "regime": { "trend", "volatility", "opening", "breadth" }, "pulse": { "direction", "strength", "score" }, "answerWithinMs": 5000 }
```

The bars of one close time arrive together (the point waits until every instrument of the universe has its bar).
Nothing in the message is later than the clock (in SIM the look-ahead guard caps every read, docs/simulation.md).

## Decisions

Reply on the socket, or `POST /api/v1/bots/{id}/decisions` (scope `bot:decide`), with the point's id:

```json
{ "pointId": "2026-09-09T04:10:00Z", "decisions": [
  { "instrument": "NSE:INFY", "action": "ENTER_LONG", "stop": 1499.9, "target": 1518.5, "confidence": 0.6,
    "thesis": "close above the opening range high", "stage": "breakout", "scores": { "breakout_pct": 0.1 }, "candidates": [] } ] }
```

| Action | What Hejje does |
|---|---|
| `ENTER_LONG`, `ENTER_SHORT` | Needs `stop` on the losing side of the current price (and `target`, when given, on the winning side). Becomes a **signal** of the bot's deployment on the instrument (evidence: bot, decision id, thesis, confidence). SIM and PAPER execute it at once (actor STRATEGY, through risk, kill switch and gate); CONFIRM creates an approval; AUTO decides as for any strategy (autonomy 4–5 with the M5.2 eligibility, else an approval). Refused when the kill switch is on or the bot already has a position on the instrument. |
| `EXIT`, `TAKE_PROFIT` | Flattens the deployment's open position at market. |
| `MOVE_STOP` | Moves the protective stop to `stop` **only when that tightens it**. |
| `HOLD`, `NONE` | Noted. |

The server answers with the recorded outcomes (`{"type": "decisions", "pointId", "results": [...]}`): `EXECUTED`,
`APPROVAL`, `EXITING`, `MOVED`, `NOTED`, `REFUSED` (with the reason) or `SKIPPED`. Every decision is stored once per
(bot, point, instrument) in `bot_decision` with its fields, latency, outcome and the linked signal and order; answering
the same point again returns the recorded outcome and creates nothing. **Attribution**: an entry's signal carries the
decision id and thesis, so every trade traces back through its order and signal to the decision
(`BotDecisions.bySignal`).

## Lockstep in SIM

In a SIM replay (docs/simulation.md) the points are delivered when the replay settles a step, and the replay waits for
each connected bot's answer up to `hejje.sim.decision-timeout` (5 s); no answer records the point as `SKIPPED`. In
PAPER and live modes points go out on a sender thread and the market never waits. A STRATEGY bot needs no connection:
in SIM its deployment's signals execute as the bot's decisions, so strategies and bots compete on the same sessions.

Authentication runs on wall time in SIM (tokens and API keys do not expire because the replay clock jumped).

## Reference bot

`research/bots/example_bot.py` (standard library only, including a minimal WebSocket client) trades an opening-range
rule: the range is the bars closing up to 09:30; the first close above it enters long with the stop at the range low and
a 2R target, and the stop goes to break-even after 1R.

```bash
export HEJJE_URL=http://localhost:8080 HEJJE_API_KEY=hejje_…   # preset "bot"
python3 research/bots/example_bot.py <bot-id>
```

## Session reports, leaderboard and promotion (M7.5)

When a SIM session finishes (`DONE`), every bot listed in its `bots` gets a **report**: trades, wins, expectancy (R net
of costs), profit factor, max drawdown, net P&L, friction, every trade's R, the session's result hash, a SHA-256 over the
bot's decisions in it, and the harness snapshot at the end (equity curve, trades, decisions). Reports outlive the next
session (which clears the simulated ledger) and are keyed by the bot's **name and version**.

- `GET /api/v1/sim/reports?bot=&version=&limit=` and `GET /api/v1/sim/reports/{id}` (`market:read`);
  `hejje harness sessions` lists them, `hejje harness sessions <report-id>` opens one read-only in the harness screen.
- **Leaderboard**: `GET /api/v1/sim/leaderboard?from=&to=&common=` ranks bot versions (EXTERNAL, LLM and STRATEGY bots
  alike) by expectancy net of costs over their reports whose sessions fall in the range, with sessions, trades, win
  rate, profit factor, max drawdown (the worst session), net P&L and friction alongside. `common=true` keeps only the
  session day sets every ranked bot played, so all rows compare the same sessions (give every bot the same capital).
  `hejje harness leaderboard [--from --to --common]`.
- **Promotion**: a bot's backing strategy may be deployed in **PAPER** only after `hejje.bots.min-sim-sessions` (20)
  reports of that bot version with **positive expectancy over all their trades**; otherwise the deployment is refused
  with the count or the expectancy. PAPER → LIVE follows the existing paper track-record and drift rules. STRATEGY bots
  run ordinary strategies, which keep their own backtest-based lifecycle.
- SIM runs in its own database, so reports are **exported and imported** where the bot is promoted: take
  `GET /sim/reports?bot=<name>&version=<v>&limit=1000` from the SIM instance and `POST /api/v1/sim/reports/import` (admin)
  the list on the live instance (idempotent per session and bot).

