# Web client (v1)

`web/` is a Vite + React + TypeScript app (React Router, TanStack Query, lightweight-charts). It talks only to the Hejje
API and holds no broker secrets.

## Run

    cd web
    cp .env.example .env      # VITE_API_URL, VITE_WS_URL
    npm ci
    npm run dev               # http://localhost:5173

## Auth

Login posts to `/auth/login`; the access token is kept in memory and the refresh token in an httpOnly cookie. A 401
triggers a silent refresh, then a redirect to `/login`. Every transactional call sends a client-generated
`Idempotency-Key` and disables its button until the response arrives.

## Screens

Active: Orders (with the manual order form + cancel), Positions (close, close-all), Trades, Risk (dashboard, kill switch
with typed `CLOSE ALL` confirmation and three buttons, re-arm), Broker (status, connect, logout), Server (health,
latency, reconciliation issues), Settings. Today / Pulse / Strategies / Lab / Hejje AI are disabled placeholders. A mode
banner (red LIVE / blue PAPER) and server/broker/market-data status dots sit across the top of every screen.

## Realtime

`useEventsSocket` (`/ws/events`) and `useMarketSocket` (`/ws/market`) reconnect with backoff and pass the access token as
`?token=`.

## Tests

- `npm test` — Vitest for the API client, idempotency keys and risk-based sizing.
- `npm run build` — type-check and production build.
- `npm run e2e` — Playwright smoke (login -> broker status -> place a paper order -> Orders -> close), which needs the
  server running with the fake broker in PAPER mode. CI runs lint/test/build; wiring the full smoke stack into CI is a
  follow-up.
