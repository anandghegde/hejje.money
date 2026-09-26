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
  / blue PAPER / purple SIM) with the kill-switch state and server/broker/market-data status sits across the top of
  every screen at every width (see "Design system" below).

## Realtime

`useEventsSocket` (`/ws/events`) and `useMarketSocket` (`/ws/market`) reconnect with backoff and pass the access token as
`?token=`.

## Tests

- `npm test` — Vitest for the API client, idempotency keys and risk-based sizing.
- `npm run build` — type-check and production build.
- `npm run e2e` — Playwright against a running dev-profile server (fake broker, PAPER, dev seeding on) and the Vite
  dev server: the smoke (login -> broker -> manual paper order -> Orders -> Positions) and the paper flow
  (`paper-flow.spec.ts`: seed a scripted session -> signal -> Today -> execute -> fill -> stop -> review -> attribution).
  Both passed locally on 2026-09-09 (`docs/analytics.md`, "Development seeding").
- `ui-shell.spec.ts` (Phase 10) checks the shell and every page at 360, 768 and 1440 px in both themes (banner and
  kill state visible, no sideways page scroll), the phone tab bar and menu, the theme switch and the phone login, and
  writes screenshots to `test-results/`. It sorts last on purpose: its page loads use up the admin's request burst (40),
  which the trading specs need.
- CI (`.github/workflows/ci.yml`, job `e2e`) runs all the specs (smoke, paper-flow, approvals, agent-chat, context, ui-shell) on
  every push and PR: Postgres 16 as a service container (db/user/password `hejje`), `./gradlew bootJar`, the jar started
  from the repo root with `HEJJE_DB_URL=jdbc:postgresql://localhost:5432/hejje HEJJE_DB_USER=hejje HEJJE_DB_PASSWORD=hejje
  HEJJE_ADMIN_PASSWORD=admin-password HEJJE_DATA_DIR=<tmp> HEJJE_MARKET_STREAM=false HEJJE_RECOMMEND_MIN_SCORE=0
  HEJJE_LLM_ENABLED=true HEJJE_RATINGS_ENABLED=true HEJJE_ANALOGS_ENABLED=true HEJJE_RATINGS_UNIVERSE=nifty50
  HEJJE_ANALOGS_UNIVERSE=nifty50 --spring.profiles.active=dev --hejje.llm.providers.fixture.type=fixture
  --hejje.llm.profiles.reasoning.provider=fixture --hejje.llm.profiles.fast.provider=fixture` (one config for all specs),
  a poll of `GET /api/v1/server/ping`, then `npx playwright test` from `web/` (Chromium only; `playwright.config.ts`
  starts the Vite dev server, which proxies `/api` and `/ws` to :8080). On failure the HTML report, traces
  (`--trace=retain-on-failure`) and the server log are uploaded as the `playwright-report` artifact. The same stack run
  locally (Postgres on another port) passed the suite three times in a row on 2026-09-26 (5 of 5, 13-23 s).

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

## Daily context (Phase 8, M8.7)

Two pages over the ratings and analogs modules (`docs/ratings.md`, `docs/analogs.md`). Both say so when a module is
switched off (the API answers 503) and nothing else changes. Everything on them is labelled **not validated** until the
pre-registered validation passes (`docs/strategies/context-validation.md`): it is context, it changes no score or decision.
Every rate is shown with its count (`27 of 40 (68 %)`).

- **Screener** (`/screener`): the market condition banner with its evidence sentence; list tabs (Setups, the default and
  the combined ordering: in buy zone → triggered → near pivot; In buy zone; Near pivot; Leaders; On the move; Top groups);
  a **Custom screen** tab with a filter builder over the documented field list (`GET /ratings/screen/fields`), sort, run,
  save and delete; saved and seeded screens load into the builder. Tables are TanStack Table, sortable client-side; a
  symbol is one click from its stock page. The **Surv.** column shows NSE's ASM/GSM badge (`ASM LT 2`, `GSM 0`; nothing
  for a stock on no list), display only.
- **Stock** (`/stocks/:symbol`): the NSE surveillance badge next to the symbol when the stock is on an ASM/GSM list
  (dimmed and marked stale when the lists were not refreshed for the session; NSE's code in the tooltip); the ratings block; a D1 `lightweight-charts` candlestick chart with the 20/50/200-DMA
  and, for the open base, the pivot, buy zone top, stop and goal as price lines and the base start, detection and trigger
  as markers; the trade plan (informational); the RS/composite and session-analog line; the **analog panel** per lookback
  (average forward path with the 25–75 band, the outcome table per forward window with counts and tags, the templated
  read, seasonality, and the match table with quality, similarity, forward returns and a sparkline per match, sorted
  client-side because the API returns matches unordered); past setups with their outcome in % and R; a watchlist toggle.
- **Pulse** gains the Market condition row (the evidence sentence is its tooltip). **Today** shows, per candidate, the
  stock's RS and technical composite and the latest session-analog line with a link to the stock page: display only.

Pure helpers live in `src/lib/context.ts` (`tests/context.test.ts`); the Playwright smoke is `tests-e2e/context.spec.ts`
(server with `HEJJE_RATINGS_ENABLED=true HEJJE_ANALOGS_ENABLED=true HEJJE_RATINGS_UNIVERSE=nifty50 HEJJE_ANALOGS_UNIVERSE=nifty50`).

## API keys and Jev status

**Settings → API keys** (admin) creates client credentials: "Terminal (TUI)" gets every scope except `admin`, `sim:run`
and `bot:decide`; the agent presets `research` and `execution` and the `bot` preset come from the server
(`AgentPresets`); a bot key is bound to the bot picked from `GET /bots` (it decides for that bot only). The key is shown
once as `export HEJJE_API_KEY=…` with a copy button; the table lists prefix, scopes (with the bound bot), last use and
expiry (default 90 days) and revokes. Helpers in `src/lib/apiKeys.ts` (`tests/apiKeys.test.ts`).
**Server** shows the Jev connection from `GET /jev/status`: enabled, key, model, circuit, today's calls by outcome,
p50/p90 and cost, and the last error.

## Design system (Phase 10)

The web client has a small design system of its own: plain CSS on custom properties and a handful of React components.
There is no CSS framework or component library.

- **Tokens** (`src/styles/tokens.css`) are the one source of colour, type (6-step scale, a UI stack, tabular figures and
  a monospace stack), 4-px spacing, radii and two elevation shadows. Each colour token has a light and a dark value.
  The theme follows the OS (`prefers-color-scheme`), and Settings → Appearance overrides it per browser
  (`data-theme` on `<html>`, `lib/theme.ts`, localStorage). `tests/tokens.test.ts` fails when a token lacks either
  value, the two dark copies disagree, or a text token falls below WCAG AA (4.5:1) on its surfaces.
- **Stylesheets**, one per concern: `base.css` (elements, focus ring, `.num`, `.profit`/`.loss`, reduced motion),
  `shell.css` (the layout), `ui/ui.css` (components and shared helpers such as `.stack`, `.cluster`, `.kv`,
  `.table-scroll`), `trading.css`, `research.css` and `system.css` (page-specific rules), `design-page.css`.
- **Components** (`src/ui`, import from `../ui`): `Page`, `Card`, `Stat`, `DataTable` (TanStack Table: sticky header,
  `meta: { numeric: true }` columns right-aligned and sorted by value, empty and loading states, scroll inside the
  table), `Badge`, `Button`, `Field`, `Tabs`, `Dialog`, `Toast`/`ToastStack`, `EmptyState`, `Skeleton`, plus
  `useMediaQuery(PHONE)` and `useChartTheme()`.
- **Shell** (`components/Layout.tsx`): a sticky mode banner (LIVE is red with a white bottom edge and the word LIVE),
  the kill-switch state (links to Risk) and health, a grouped side navigation (Trade, Research, Automation, System) that
  becomes a bottom tab bar (Today, Positions, Approvals, Risk) and a Menu sheet below 768 px, and content up to 1280 px.
- **`/design`** (behind login, linked from Settings) shows the colour tokens, the type scale and every component in the
  light and dark theme side by side.

Rules for new pages and components:

1. **No inline styles.** `npm run lint` rejects any JSX `style` prop. Use tokens through classes. Charts get token
   colours from `useChartTheme()` and redraw when the theme changes. SVG geometry uses attributes, colours use classes.
2. **Numbers are data.** Put prices, quantities, P&L and percentages in `.num` (tabular, right-aligned in tables) or in
   `numeric` DataTable columns. Money is signed with `signed()` and coloured with `tone()` (`tone-profit`, `tone-loss`,
   `tone-neutral`). Colour is never the only signal: a sign, a word or a mark (✓ ✗ ⚠) goes with it.
3. **Status is a Badge** with a tone. Map domain states to tones in `lib/` (for example `decisionTone`, `DRIFT_TONE`,
   `BASKET_TONE`, `conditionTone`), never to hex colours.
4. **Destructive actions confirm through `Dialog`**, never `window.confirm`/`prompt`/`alert`. This covers close all
   positions, cancel all open orders, fail over, revoke a key, rotate a webhook secret, retire a version and a LIVE
   manual order. The safe button comes first so it takes the focus.
5. **Phone layouts** for the away-from-desk pages (Today, Positions, Approvals, Risk, Broker, Login): wide tables become
   cards below 640 px (`useMediaQuery(PHONE)`). Other wide tables scroll inside `.table-scroll` or the DataTable, never
   the page.
6. Keep the existing `data-testid`s and accessible names. Specs depend on them. Add new ones rather than renaming.
