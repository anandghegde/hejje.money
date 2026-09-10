# Web client (v1)

`web/` is a Vite + React + TypeScript app (React Router, TanStack Query, lightweight-charts). It talks only to the Hejje
API and holds no broker secrets.

## Run

    cd web
    npm ci
    npm run dev               # http://localhost:5173; /api and /ws are proxied to the server on :8080 (vite.config.ts)

`VITE_API_URL` / `VITE_WS_URL` are optional overrides; by default the app uses relative URLs so the Vite dev proxy (or
nginx/Caddy in production) forwards them and no CORS is involved. A page reload restores the session from the
refresh cookie before rendering protected routes.

## Auth

Login posts to `/auth/login`; the access token is kept in memory and the refresh token in an httpOnly cookie. A 401
triggers a silent refresh, then a redirect to `/login`. Every transactional call sends a client-generated
`Idempotency-Key` and disables its button until the response arrives.

## Screens

- **Today** (`/today`, default route): market header, Best Hejje card (score, direction, signal validity, backtest
  metrics, score adjustments, entry/stop/target, risk and expected reward) with EXECUTE → prepare modal (proposed order,
  risk checks) → CONFIRM, DETAILS (evidence and risks) and SKIP; the ranked opportunities table; the No-Trade state.
- **Strategies** (`/strategies`) and the strategy page (`/strategies/:id`): versions with change notes, rules rendered as
  a list, lifecycle buttons, Hejje Score breakdown, deployments (deploy on paper, pause/enable), backtests with metrics,
  warnings, equity chart and trade list, version comparison with the templated verdict.
- **Lab** (`/lab`): YAML editor with live validation (`POST /strategies/validate`), create / new version with change
  note / clone, run a backtest with split options and follow its progress.
- **Trades** with the attribution column and review links; **Reviews** (`/reviews`, `/reviews/:id`) with the PRD 55
  postmortem; **Analytics** (`/analytics`) P&L breakdown by strategy / version / instrument / weekday / hour / regime.
- Orders (manual order form with the stop prefilled from `GET /risk/stop-suggestion` + cancel), Positions (close, close-all), Risk (dashboard, kill switch with typed `CLOSE
  ALL` confirmation, re-arm), Broker, Server, Settings. Pulse remains a placeholder in the nav (the `/pulse` page exists). A mode banner (red LIVE
  / blue PAPER) and server/broker/market-data status dots sit across the top of every screen.

## Realtime

`useEventsSocket` (`/ws/events`) and `useMarketSocket` (`/ws/market`) reconnect with backoff and pass the access token as
`?token=`.

## Tests

- `npm test` — Vitest for the API client, idempotency keys and risk-based sizing.
- `npm run build` — type-check and production build.
- `npm run e2e` — Playwright against a running dev-profile server (fake broker, PAPER, dev seeding on) and the Vite
  dev server: the smoke (login -> broker -> manual paper order -> Orders -> Positions) and the paper flow
  (`paper-flow.spec.ts`: seed a scripted session -> signal -> Today -> execute -> fill -> stop -> review -> attribution).
  Both passed locally on 2026-09-09 (`docs/analytics.md`, "Development seeding"); CI runs lint/test/build and the e2e
  stack in CI is still a follow-up.

## Phase 3 additions

- `/pulse` (Technical/Market Pulse, sector bars, VIX sparkline), the Today header (regime, breadth, event risk, next
  event), the Best Hejje card (decision incl. TRADE WITH CAUTION, cautions, adjustment rows, news bias panel, Context
  Card), the strategy page (regime breakdown of the shown backtest, next event, news bias, Context Card).
- `npm run e2e` expects the dev stack started with `HEJJE_RECOMMEND_MIN_SCORE=0` (in addition to the Phase 2 recipe in
  `plan/PROGRESS.md`) so the paper flow's Today card carries a decision; the spec injects a results event and asserts
  `TRADE WITH CAUTION`.

## Phase 4 additions

- **Hejje AI** (`/agent`, M4.3): chat with streamed answers (`POST /agents/ai/ask` as server-sent events, parsed by
  `lib/agent.ts`), quick prompts for the canned flows, a tool-call trace panel (tool, required scope, status, latency),
  numbers not traced to a tool result highlighted as "unverified", and a disabled state when `hejje.llm.enabled=false`.
  e2e: `tests-e2e/agent-chat.spec.ts` (fixture LLM on the stack, or the disabled state when it is off).

## Options (M5.4)

`/options` ("Options" in the nav): underlying and expiry pickers, the chain (call OI / IV / delta / LTP, strike with
ITM/ATM/OTM, put LTP / delta / IV / OI; the ATM row highlighted; `*` marks a stale quote), the forward and its source,
put/call ratios and max pain, and the options positions (legs, status, exit reason, realized P&L). The Lab's
"Option legs…" builder composes a `legs:` block (action, option side, strike by ATM / offset / delta, expiry, lots,
premium stop/target, hedge first) and writes it into the definition being edited.
