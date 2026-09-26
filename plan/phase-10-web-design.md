# Phase 10 — Web UI design pass

Goal of the phase: make the web client look and read like one designed product. Today it is 22 pages and 14 shared
components built with about 255 inline `style={{…}}` objects, hard-coded colours, no type scale and no phone layout
(the user called it "looks so bad", 2026-09-11). The phase adds a small design system and restyles every page onto
it. Page structure, routes and flows stay as they are.

Decisions taken with the user (2026-09-26):

| Question | Decision |
|---|---|
| Depth | Design system + restyle: tokens, a handful of shared components, every page moved onto them, worst layouts fixed. No information-architecture redesign |
| UI framework | None added. Plain CSS (custom properties + one stylesheet per concern) and React components; no Tailwind, no component library |
| Devices | Desktop first, plus a phone layout for the pages used away from the desk: dashboard (Today/Positions), Approvals, Risk and the kill switch, Broker login |
| Theme | Light and dark, following the OS setting, with a manual override |

Binding rules for this phase (on top of plan/README §4):

- **No behaviour changes.** Same routes, same API calls, same data shown. A restyle PR that changes what a page
  fetches or does is split out. Existing Playwright specs and `data-testid`s keep passing untouched (add, don't rename).
- **Tokens only.** After a page is migrated it has no hard-coded colours, font sizes or spacing: everything comes from
  the tokens (`web/src/styles/tokens.css`). `npm run lint` gains a check that fails on `style={{` in migrated files
  (an allow-list shrinks milestone by milestone and is empty at the exit).
- **Numbers are data.** Prices, P&L, quantities and percentages use tabular figures, right-aligned in tables, with one
  sign and colour convention (profit, loss, neutral) everywhere, colour never the only signal (sign and text too).
- **The mode is always visible.** The PAPER / LIVE / SIM banner and the kill-switch state stay on every page at every
  width; LIVE stays unmistakable (colour + text), in both themes.
- **Accessible by default.** WCAG AA contrast for text in both themes, visible focus, keyboard reachable controls,
  `prefers-reduced-motion` respected.

Order: M10.1 → M10.2 → (M10.3, M10.4, M10.5 in any order or in parallel) → M10.6.

---

## M10.1 Tokens, theme and base styles  (size: M)

**Goal.** One source of truth for colour, type, spacing, radius and elevation, in light and dark.

**Tasks.**
1. `web/src/styles/tokens.css`: CSS custom properties on `:root` for surface/background layers, text (primary,
   secondary, muted), border, accent, and the semantic set `profit`, `loss`, `warning`, `info`, `mode-live`,
   `mode-paper`, `mode-sim`; a type scale (6 steps) with a UI font stack and a monospace/tabular stack for numbers;
   a 4-px spacing scale; radii; two elevation shadows. Dark values under `@media (prefers-color-scheme: dark)` and
   `[data-theme="dark"]`, light under `[data-theme="light"]`.
2. `web/src/styles/base.css`: reset, body, headings, links, `table`, `input`/`select`/`button` defaults, focus ring,
   `.num` (tabular, right-aligned), `.profit`/`.loss`.
3. Theme switch (system / light / dark) in Settings, stored per browser (localStorage, wrapped in try/catch).
4. Lint rule for inline styles with the allow-list described above.

**Acceptance.** Every token has a light and a dark value; a contrast check script (unit test over the token values)
passes AA for text tokens on their surfaces in both themes; switching theme needs no reload.

**Verification.** `npm run lint && npm test && npm run build`; screenshots of Settings in both themes.

---

## M10.2 Shared components and the app shell  (size: L)

**Goal.** The pieces every page is built from, and a layout shell that works from 360 px to wide desktop.

**Tasks.**
1. Components in `web/src/ui/`: `Page` (title, actions, body), `Card`, `Stat` (label, value, delta), `DataTable`
   (on the existing `@tanstack/react-table`: sticky header, numeric columns, sorting, empty and loading states,
   horizontal scroll inside the table only), `Badge` (neutral/profit/loss/warning/mode), `Button` (primary,
   secondary, danger, sizes), `Field` (label, input/select, hint, error), `Tabs`, `Dialog` (replaces
   `window.confirm` for destructive actions, focus-trapped), `Toast`, `EmptyState`, `Skeleton`.
2. App shell (`Layout.tsx`): top bar with the mode banner, kill-switch state and health dots; side navigation grouped
   (Trade, Research, Automation, System) that collapses to a bottom tab bar + menu on phones; content max-width.
3. A living style page at `/design` (dev and admin only) rendering every component in both themes.
4. Unit tests for component logic (sorting, number formatting, dialog focus return); Playwright screenshot of `/design`.

**Acceptance.** The shell works at 360, 768 and 1440 px without horizontal page scroll; the mode banner and kill
state are visible at every width; destructive actions use `Dialog`.

**Verification.** `npm test`, Playwright at three viewport sizes.

---

## M10.3 Trading pages  (size: L)

**Goal.** The pages used during the session look finished, on desktop and phone.

**Tasks.** Migrate Today, Positions, Orders, Trades, ManualOrder, Approvals, Risk (with the kill switch), Broker onto
the components; phone layouts for Today/Positions (cards instead of wide tables below 640 px), Approvals, Risk and
Broker. Live P&L cells use the profit/loss convention; the order ticket gets clear validation and a confirmation step.

**Acceptance.** No inline styles left in these pages; `paper-flow` and `approvals` specs pass unchanged; phone
screenshots show no clipped or scrolling layout.

**Verification.** Playwright specs + screenshots at 390 × 844 and 1440 × 900 in both themes.

---

## M10.4 Research pages  (size: L)

**Goal.** Strategy, context and analysis pages are readable at a glance.

**Tasks.** Migrate Strategies, StrategyDetail, Lab (backtests), Screener, Stock, Pulse, Analytics, Reviews, Options,
and the components they use (ContextCard, DriftPanel, EquityChart, Experiments, LegBuilder, LossInvestigation,
NewsBiasPanel, CalibrationPanel, Baskets, DescribeStrategy). Charts (`lightweight-charts`) read the theme tokens and
redraw on theme change.

**Acceptance.** No inline styles left in these files; `context` spec passes; charts are legible in both themes.

**Verification.** Playwright + screenshots of Stock, StrategyDetail and Analytics in both themes.

---

## M10.5 System and settings pages  (size: M)

**Goal.** Admin pages are consistent with the rest.

**Tasks.** Migrate System (server, executor, Jev), Settings (API keys, notifications, webhooks, theme), Policies,
Agent (Hejje AI chat), Login.

**Acceptance.** No inline styles left; `agent-chat` and `smoke` specs pass; the Login page works on a phone.

**Verification.** Playwright + screenshots.

---

## M10.6 Consistency sweep  (size: S)

**Goal.** Close the phase with nothing left behind.

**Tasks.** Empty the inline-style allow-list; remove unused CSS; check every page at the three widths and both
themes; fix contrast and focus issues found; update `docs/web.md` (design system section: tokens, components, the
`/design` page, rules for new pages).

**Acceptance / exit checklist.**
- [ ] No `style={{` in `web/src` outside the documented exceptions (chart containers sized at runtime).
- [ ] Every page renders at 360, 768 and 1440 px with no page-level horizontal scroll.
- [ ] Light and dark themes pass the AA contrast test; the theme follows the OS and can be overridden.
- [ ] Mode banner and kill-switch state visible on every page at every width.
- [ ] All Playwright specs pass unchanged in CI.

**Out of scope for the phase.** New pages, navigation or flows; a component library; a native or PWA app;
redesigning the TUI.
