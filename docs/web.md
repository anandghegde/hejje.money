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
- Orders (manual order form + cancel), Positions (close, close-all), Risk (dashboard, kill switch with typed `CLOSE
  ALL` confirmation, re-arm), Broker, Server, Settings. Pulse and Hejje AI remain placeholders. A mode banner (red LIVE
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
