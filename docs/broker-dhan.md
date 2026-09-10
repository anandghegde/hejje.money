# Dhan (DhanHQ v2) adapter (Phase 5, M5.6)

The second `BrokerAdapter` (`money.hejje.broker.dhan`), over Dhan's documented REST API
(https://dhanhq.co/docs/v2/). It passes the same adapter contract as the fake, paper and Zerodha adapters
(`BrokerAdapterContractTest`). Tests run only against WireMock; nothing here has been run against a live Dhan account.

## Setup

| Setting | Env | Notes |
|---|---|---|
| `hejje.broker.adapter=dhan` | `HEJJE_BROKER_ADAPTER` | Selects this adapter (in PAPER mode it is wrapped by the paper simulator like Zerodha). |
| `hejje.broker.dhan.client-id` | `HEJJE_DHAN_CLIENT_ID` | Required. |
| `hejje.broker.dhan.app-id` / `app-secret` | `HEJJE_DHAN_APP_ID` / `HEJJE_DHAN_APP_SECRET` | Optional: the API key and secret (valid 12 months) for the consent login. Env only. |

**Login with an API key** (web.dhan.co → My Profile → Access DhanHQ APIs → API key): set the redirect URL to
`https://<host>/api/v1/broker/callback`. `GET /broker/login-url` generates a consent (`/app/generate-consent`) and
returns Dhan's login page; after the login Dhan redirects with `tokenId`, which the callback exchanges
(`/app/consumeApp-consent`) for the access token.

**Login without an API key**: generate an access token (24 h) on web.dhan.co and post it:
`POST /api/v1/broker/login?request_token=<token>` (admin). A JWT is recognised and checked with `GET /profile`.

Either way the token is stored encrypted in `broker_session` (as for Zerodha), sent only in the `access-token` header,
never logged or returned. Tokens last 24 hours and cannot be refreshed; Hejje's session daemon marks sessions expired
at 06:15 IST (the Zerodha schedule), which is before the Dhan expiry for any login after 06:15 — log in again each
trading day.

**Static IP**: Dhan's order APIs (place, modify, cancel) require a whitelisted IP; each account has a PRIMARY and a
SECONDARY IP (web.dhan.co or `POST /v2/ip/setIP`; an IP cannot be changed for 7 days). Register the active VM's IP as
PRIMARY and the standby's as SECONDARY (RUNBOOK §9). An order from another IP fails with DH-905 "Invalid IP" (INPUT).

## Mapping

| Hejje | Dhan |
|---|---|
| Product MIS / CNC / NRML | `INTRADAY` / `CNC` / `MARGIN` (MTF read back as CNC, CO/BO as MIS) |
| Order type MARKET / LIMIT / SL / SL_M | `MARKET` / `LIMIT` / `STOP_LOSS` / `STOP_LOSS_MARKET` |
| Order tag | `correlationId` |
| Status | `TRANSIT` → PENDING; `PENDING` → OPEN (TRIGGER_PENDING for stop orders); `PART_TRADED`, `TRIGGERED` → OPEN; `TRADED`, `CLOSED` → COMPLETE; `CANCELLED`, `EXPIRED` → CANCELLED; `REJECTED` → REJECTED (`omsErrorDescription` as the message) |
| Broker instrument token | `<exchangeSegment>:<securityId>` (e.g. `NSE_EQ:1594`): a security id is unique only within its segment |
| Times | `yyyy-MM-dd HH:mm:ss` in IST; quote `last_trade_time` `dd/MM/yyyy HH:mm:ss` |
| Funds | `availabelBalance` (Dhan's spelling) → available and net; `utilizedAmount` → used; `sodLimit` → opening balance |
| Positions | average = `buyAvg` (long) or `sellAvg` (short); `realizedProfit`, `unrealizedProfit` |

A modify sends the full order (Dhan requires order type, quantity, price and validity): unchanged fields come from the
current order.

**Instruments** come from the public compact security master (`api-scrip-master.csv`): NSE equities of series EQ,
BSE equities, index/stock futures and options on NSE (NFO) and BSE (BFO), and indices under Hejje's (Kite) names
(`NIFTY` → `NIFTY 50`, `BANKNIFTY` → `NIFTY BANK`, `FINNIFTY` → `NIFTY FIN SERVICE`). `SEM_TICK_SIZE` is in paise and is
divided by 100. Currency and commodity rows are skipped.

## Errors

| Dhan | Kind |
|---|---|
| DH-901 (invalid/expired token), 807–810 | AUTH — the session is dropped (`BrokerAuthRejected`) |
| DH-902 (no API/data access), 806 | AUTH |
| DH-904, 805, HTTP 429 | RATE_LIMIT |
| DH-905, DH-907, 804, 811–814, HTTP 400/422 | INPUT |
| DH-903 (account), DH-906 (order error) | REJECTED |
| DH-908, DH-909, 800, HTTP 5xx | NETWORK (outcome unknown for orders) |
| timeout | TIMEOUT |

## Limits and what is not built

- Rate limits (`hejje.broker.rate-limits.dhan`): orders 10/s, 250/min, 7000/day; quotes 1/s; data 5/s; other 20/s.
  Dhan's 1000 orders/hour limit is not modelled separately.
- **Market data** is polled from the REST quote API every `quote-poll-interval` (2 s; Dhan allows one quote request
  per second, up to 1000 instruments): LTP subscriptions get the last price and volume, FULL ones also depth and OI.
  Dhan's binary websocket feed is not implemented.
- **Order updates** come from Hejje's order poller (5 s) and reconciliation; Dhan's postback and order-update
  websocket are not consumed.
- **Margins**: one `/margincalculator` call per order (the multi-order endpoint's documentation contradicts itself), so
  a basket's margin is the sum without hedge benefit.
- **History**: daily candles (`/charts/historical`, `toDate` exclusive) and 1/5/15/25/60-minute candles
  (`/charts/intraday`, in 90-day windows); Dhan has no 3-minute candles (INPUT).
- Unverified against the live API (documentation is ambiguous): the consume-consent call's method (GET is used), the
  cancel response (202 with or without a body — both handled), candle timestamps in epoch seconds.
