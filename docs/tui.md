# TUI (hejje)

`tui/` is a Go module (`hejje.money/tui`) building the `hejje` terminal client. It uses the same authenticated Hejje API
as the web client.

## Config

`~/.config/hejje/config.yaml` holds `server_url`. The API key comes from `HEJJE_API_KEY` (a client credential with the
needed scopes) and is never stored in the file.

## Commands

`hejje status | server | broker | positions | orders | order <id> | cancel <id> | close <instrument> | close-all |
risk | logs | kill [--cancel-all|--close-all] | place --instrument --side --qty|--risk --type --price --stop --target`.

`--json` prints machine-readable output for scripting. Transactional commands send a client-generated `Idempotency-Key`.
`kill --close-all` and `close-all` require typing `CLOSE ALL`; other destructive commands ask `[y/N]`.

## Dashboard

`hejje` with no arguments opens the Bubble Tea dashboard (PRD 5.4): mode banner, Best-Hejje card ("No strategies
deployed" until Phase 2), positions with live P&L, open orders, daily P&L vs limit, and server/broker/market dots. Keys:
`c` cancel selected order, `x` close selected position, `K` kill switch, `r` refresh, `q` quit.

## Build and test

    cd tui
    go vet ./... && go test ./... && go build ./cmd/hejje
    ./build.sh v0.1.0   # cross-compiles linux/mac binaries into dist/

## Phase 2 commands (M2.8)

- `hejje best` — the Today screen: Best Hejje card (score, direction, signal validity, backtest metrics, score
  adjustments, entry/stop/target, risk, expected reward) and the ranked opportunities table with the decision and the
  first reasons (`--json` for the full PRD 29 objects).
- `hejje strategies` — strategies with family, latest version, status and deployments.
- `hejje strategy <id>` — versions, the Hejje Score breakdown (components, base, adjustments, final), backtests of the
  latest version and deployments.
- `hejje signals [--status ACTIVE|PREPARED|EXECUTED|EXPIRED|SKIPPED]` — signals (default: the actionable ones).
- `hejje execute <signal-id> [-y]` — prepares the order (sizing on the live quote, dry-run risk checks), prints it and
  asks `Execute? [y/N]`; the confirmation submits it with a fresh `Idempotency-Key` (scope `orders:execute`).
- `hejje skip <signal-id> [--reason ...]`.

Dashboard: the Best Hejje card replaces the placeholder; `[E]` prepares the best signal's order and shows the risk
checks, then `y` executes (anything else cancels), `[D]` toggles the evidence and risks, `[S]` skips the signal.

## Phase 4 commands

- `hejje ai "question" [--flow why_ranked_first|compare|working_today] [--json]` — one-shot Hejje AI answer, the grounding
  note (numbers not found in this turn's tool results are marked `[unverified]`) and the tool trace (tool, scope, status,
  latency).
- `hejje ai` — interactive: each answer continues the conversation; `/new` starts another, `/quit` exits.

## Options (M5.4)

`hejje chain <underlying> [expiry]` prints the option chain: calls on the left, puts on the right, OI, IV, delta and LTP,
the ATM strike marked `*`, with the forward, put/call ratio (OI) and max pain (`--json` for the raw chain).

## Harness (Phase 7, M7.4)

`hejje harness [session-id] [--bot <id>]` watches a bot trade (docs/bots.md). It takes one snapshot over REST
(`GET /api/v1/harness/snapshot?bot=&session=`, or `/sim/sessions/{id}/snapshot`) and then follows `/ws/harness`, which
pushes a new snapshot whenever something changes (at most four a second); after a drop it reconnects with a fresh
snapshot. The screen redraws at most ten times a second whatever the replay speed. Without `--bot` it shows the
session's first bot, else the only enabled bot, else every strategy position.

| Area | Content |
|---|---|
| Header | bot, mode badge (SIM / PAPER / LIVE), fill source, data health (`HIST OK · N names · M quotes`), bot latency p50/p90, skipped points, clock (IST); capital, bot paused / not connected, kill switch |
| Replay bar (SIM only) | session date and day, state, progress bar and `step/375`, speed (1× 10× 60× 300× MAX), knowledge-cutoff warnings |
| Context line | regime, Pulse, time to the next decision point |
| Stat tiles | day P&L, open P&L, total, capital, in use, free, risk per trade, trades, hit rate, expectancy (R), profit factor, max drawdown, average win/loss, average hold, friction paid, loss halt, LLM tokens and cost (as the bot reports them with its answers, `usage`) |
| Equity | braille line chart with last, peak and trough |
| Positions | symbol, side, qty, entry, LTP, stop, stop location (exch / sim / soft), notional, open P&L, R, MFE, MAE and the thesis under each; working orders below |
| Candidates | the bot's `candidates[]` at its last decision point, ranked per side, the one it sent marked ▶ |
| Trades | time, leg, symbol, side, qty, entry, exit, P&L (net of costs), hold, exit reason, why (thesis), the deciding decision |
| Log, Decisions | recent actions and closes; the decisions table (time, stage, symbol, action, scores, confidence, latency, outcome) |

Keys: `space` play/pause, `1`–`5` speed, `s` step, `c` set capital (SIM, before the first step), `p` pause/resume the
bot, `K` kill switch (asks `y` to confirm), `tab` next panel, `q` quit. The replay keys only act in SIM; in PAPER and
LIVE the replay bar is hidden. From 160 columns the panels sit side by side (equity | positions, candidates | trades,
log | decisions); narrower terminals stack them, the focused panel (`tab`) in full and the others as a one-line summary.

Tests: `internal/ui/harness_test.go` renders a fixture stream through teatest at 200×50 and 120×40 against the goldens
in `testdata/` (`go test ./internal/ui -update` rewrites them), decodes a snapshot the server produced
(`harness_server.json`, written by `BotProtocolIT` to `server/build/harness-snapshot.json`), and checks the throttle, the
SIM-only replay keys and the kill confirmation.

`hejje harness sessions [--bot <name>]` lists SIM session reports; `hejje harness sessions <report-id>` opens one in the
same screen read-only (no stream, no controls). `hejje harness leaderboard [--from --to --common]` prints the leaderboard
(docs/bots.md) with each bot's pooled confidence Brier score and its count (M9.2). Both accept `--json`.


## Daily context (Phase 8, M8.7)

```bash
hejje screen                                   # the combined setups list: in buy zone, triggered, near pivot
hejje screen --list leaders|buyzone|nearpivot|movers|groups
hejje screen --filter rsRating:gte:80 --filter baseStatus:in:IN_BUY_ZONE,NEAR_PIVOT --sort -techComposite --limit 30
hejje stock NSE:INFY                           # ratings, braille D1 chart, base and plan, past setups, daily analogs (15 sessions)
hejje analogs NSE:INFY --lookback 30           # outcome table per forward window, seasonality, the templated read
hejje analogs NSE:SBIN --session [--checkpoint 10:15]   # today's session so far against past sessions, to 15:10
hejje watch add NSE:INFY --note "results next week" | hejje watch rm NSE:INFY | hejje watch ls
```

A filter is `field:op:value` (`gte lte gt lt eq ne in`; `in` takes a comma list); the fields are those of
`GET /ratings/screen/fields`. Every rate is printed with its count (`27 of 40 (68%)`). The commands print once and exit
(`--json` for the raw response); with a module switched off the server's 503 message is shown. Golden renders:
`TestStockPageGolden`, `TestRatingsListGolden`, `TestSessionAnalogsGolden` (`go test ./internal/ui -update` rewrites them).

## Jev (Phase 9, M9.1)

When Jev is enabled, `hejje status` adds one line: the model (or `fixture`), the circuit, today's calls by outcome, p50/p90
latency of answered calls, and the estimated cost against the daily cap (`docs/jev.md`).

## Calibration (Phase 9, M9.2)

```bash
hejje calibration                              # purposes and versions with prediction and labelled counts
hejje calibration signal-check [--version 1] [--from --to]   # bucket table: count, hits, mean p, rate, Wilson 95 %
hejje calibration --bot momo                   # a bot's entry confidence (purpose bot:momo)
```

Buckets under `min-bucket-count` show their count and no rate; the last lines give Brier, ECE and whether the
pre-registered bar passes, with the reasons when it does not (`docs/calibration.md`).

## Reviews (Phase 9, M9.6)

`hejje reviews [--limit 20]` lists recent post-trade reviews: close time, side, quantity, net, R, close reason, the
trade's **cause** and **entry timing** (a `*` marks a provisional cause, completed 35 minutes after the close), MFE/MAE
in R, and Jev's reading when there is one (docs/analytics.md "Trade cause"). `--json` prints the raw reviews.

## Analytics (Phase 9, M9.7)

`hejje analytics pace [--from --to --mode --strategy]` prints the pace report: expectancy (R with its count, and
rupees net of costs), win rate and net P&L by trades that day, by sequence in the day and by entry hour
(docs/analytics.md). `hejje risk` adds the loss-streak line (the allowance used on an ALLOWANCE day).
