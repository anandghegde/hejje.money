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
