# Zerodha Kite Connect

Hejje talks to Zerodha through `ZerodhaKiteAdapter` (module `broker`, package `money.hejje.broker.zerodha`), which wraps the
official `javakiteconnect` client. Nothing outside that package knows Kite field names, enums or error strings.

## App setup

1. Create a Kite Connect app at <https://developers.kite.trade>. Note the **API key** and **API secret**.
2. Redirect URL: `https://<HEJJE_DOMAIN>/api/v1/broker/callback`.
3. Postback URL (optional but recommended): `https://<HEJJE_DOMAIN>/api/v1/broker/postback`.
4. Register the server's static egress IP with Zerodha (required for API order placement). The server refuses live
   execution unless `hejje.execution.expected-ips` matches its observed egress IP (M0.4).
5. Set on the server (environment only, never in files under version control):

   | Env | Property | Purpose |
   |---|---|---|
   | `HEJJE_BROKER_ADAPTER=zerodha` | `hejje.broker.adapter` | select the Zerodha adapter (default is `fake`) |
   | `HEJJE_KITE_API_KEY` | `hejje.broker.zerodha.api-key` | Kite app key |
   | `HEJJE_KITE_API_SECRET` | `hejje.broker.zerodha.api-secret` | Kite app secret (never logged, never returned) |
   | `HEJJE_ENCRYPTION_KEY` | `hejje.security.encryption-key` | 32 bytes (base64 or hex) for the access token at rest |
   | `HEJJE_WEB_URL` | `hejje.broker.web-url` | where the login callback redirects |

   Generate a key with `openssl rand -hex 32`.

## Daily login

Kite access tokens expire every morning (around 06:00 IST). The login itself cannot be automated (SEBI rules), so once
per trading day, before the session:

1. Open the Web client's Broker page and click **Connect**, or `GET /api/v1/broker/login-url` and open the URL.
2. Log in at Zerodha (user id, password, TOTP). Zerodha redirects the browser to `/api/v1/broker/callback?request_token=…`.
3. The server exchanges the request token (`checksum = SHA-256(api_key + request_token + api_secret)`), stores the access
   token encrypted with AES-256-GCM in `broker_session`, marks the session `CONNECTED`, audits `BROKER_CONNECTED` and
   redirects to `<web-url>/broker?connected=1`.
4. `GET /api/v1/broker/status` shows the PRD section 40 shape; `/api/v1/server/health` line `broker` and readiness check
   `brokerSession` turn green.

Alternative without a browser round trip to the server (for example when testing from a laptop): copy the `request_token`
from the redirect URL and call `POST /api/v1/broker/login?request_token=…` (admin scope).

## Token lifecycle

| Event | Effect |
|---|---|
| Login | row `CONNECTED`, token encrypted, `expires_at` = next day 06:00 IST, event `BrokerSessionChanged`, audit `BROKER_CONNECTED` |
| Server restart | stored token is decrypted and reinstalled, then validated with `GET /user/profile` |
| Every 5 minutes 08:30–15:45 IST on trading days | `getProfile()`; an AUTH error marks the session `DISCONNECTED` |
| Any broker call answered with `TokenException` | adapter flips to `DISCONNECTED` immediately, publishes `BrokerSessionChanged`, audits `BROKER_DISCONNECTED`; readiness `brokerSession` goes red and the execution gate refuses new orders |
| 06:15 IST daily | session marked `EXPIRED`, token dropped, audit `BROKER_SESSION_EXPIRED` |
| `POST /api/v1/broker/logout` | `DELETE /session/token` at Kite, row `DISCONNECTED`, audit `BROKER_LOGGED_OUT` |

Rule 1 (no broker secret leaves the server): the api secret and access token never appear in API responses, events,
audit payloads or logs. `ZerodhaKiteAdapterWireMockTest.secretsNeverAppearInLogs` captures the log stream to prove it,
and the Logback redaction converter (M0.2) masks `access_token`/`api_secret` patterns as a second line of defence.

## Error mapping

| Kite response | `BrokerException.kind` | retryable | outcome unknown |
|---|---|---|---|
| `TokenException` (403) | `AUTH` | no | no |
| `PermissionException` (403) | `AUTH` | no | no |
| `NetworkException` with HTTP 429, or any 429 | `RATE_LIMIT` | yes | no |
| `NetworkException` (5xx) or any `error_type` with HTTP >= 500 | `NETWORK` | yes | yes |
| `InputException` (400) | `INPUT` | no | no |
| `OrderException` (400) | `REJECTED` | no | no |
| `DataException`, `GeneralException`, `MarginException`, `HoldingException` (< 500) | `UNKNOWN` | no | yes |
| `SocketTimeoutException` / interrupted I/O | `TIMEOUT` | yes | yes |
| other `IOException` | `NETWORK` | yes | yes |

Outcome-unknown failures of transactional calls must be reconciled (M1.4 polls the orderbook for the order tag).

## Field mapping

- Variety is always `regular` (AMO/iceberg/CO arrive with smart orders in Phase 5).
- Products `MIS`/`CNC`/`NRML`; order types `MARKET`/`LIMIT`/`SL`/`SL-M`; validity `DAY`/`IOC`.
- Order status strings normalize to `BrokerOrderStatus`: `COMPLETE`, `OPEN`, `TRIGGER PENDING`, `MODIFY PENDING`,
  `CANCEL PENDING`, `CANCELLED`/`LAPSED`, `REJECTED`; `PUT ORDER REQ RECEIVED`, `VALIDATION PENDING`, `OPEN PENDING`,
  `AMO REQ RECEIVED` are `PENDING`; anything else is `UNKNOWN`.
- The order `tag` is at most 20 alphanumeric characters (Kite limit); the execution module derives it from the Hejje order id.
- Kite timestamps are IST wall-clock strings. `javakiteconnect` parses them in the JVM default zone; `KiteMapper.instant`
  converts them back to the intended instant, and `KiteMapper.kiteDate` does the reverse for historical-data requests.
- Kite hard-codes its base URL and HTTP timeouts. `KiteClientFactory` sets both reflectively (`Routes._rootUrl`, the
  request handler's OkHttp client) so WireMock tests and `hejje.broker.zerodha.*-timeout` work. This is the only place
  the library's internals are touched; if a library upgrade breaks it, replace it with direct REST calls per plan/README.

## Postback

`POST /api/v1/broker/postback` is public (per-IP rate limited). The body is Kite's order JSON; the server verifies
`checksum = SHA-256(order_id + order_timestamp + api_secret)`, rejects mismatches with 403 and republishes valid
updates as `BrokerOrderUpdate(source=BROKER_POSTBACK)`. Ticker `order` messages arrive through the same bus with
`source=BROKER_WS`; the execution module treats both identically and deduplicates by order state.

## Fake broker (dev/test)

`hejje.broker.adapter=fake` (default outside `prod`) selects `FakeBrokerAdapter`: instrument fixture, injected quotes,
MARKET fills at the quote, LIMIT/SL fills on crossing, in-memory positions and funds, order updates on a background
thread, scripted failures (`failNext`, `delayNext`, `dropAck`, `simulateSessionExpiry`) and seeds for reconciliation
tests. It starts `CONNECTED` (`hejje.broker.fake.connected=true`); `POST /broker/logout` and `POST /broker/login?request_token=any`
exercise the session lifecycle without Zerodha.
