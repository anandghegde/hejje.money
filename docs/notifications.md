# Notifications (Phase 5, M5.5)

`money.hejje.notify` turns what happens in Hejje into notifications and delivers them through channels, per explicit
rules, with rate limiting and a delivery log. Off the trading core: a channel failing never affects execution.

## Events (PRD 58)

| Type | Severity | Source |
|---|---|---|
| `SIGNAL_GENERATED` | INFO | a strategy signal |
| `HIGH_SCORE_SETUP` | INFO | a signal whose latest Hejje Score is at or above `hejje.notify.high-score` (80) |
| `ORDER_REJECTED` | WARNING | an order rejected by the broker or the risk engine |
| `STOP_TRIGGERED` | WARNING | a protective stop order filled |
| `POSITION_CLOSED` | INFO | a position went flat (realized P&L) |
| `DAILY_RISK_THRESHOLD` | CRITICAL | the kill switch tripped on the daily loss limit |
| `KILL_SWITCH` | CRITICAL | the kill switch was activated |
| `BROKER_DISCONNECTED` | CRITICAL | the broker session is disconnected, expired or in error |
| `SERVER_UNHEALTHY` | CRITICAL | execution readiness turned off (checked every minute) |
| `STATIC_IP_MISMATCH` | CRITICAL | the egress IP check failed |
| `STRATEGY_DRIFT` | WARNING | a drift status change (docs/analytics.md) |
| `MAJOR_EVENT_APPROACHING` | WARNING | a HIGH-risk market event within `hejje.notify.event-lead` (60 min), once per event |
| `NEWS_CONTEXT_CHANGED` | INFO | the news bias label of a deployed instrument changed |
| `APPROVAL_REQUESTED` | INFO | a new approval is waiting in the inbox |
| `LLM_BUDGET_EXCEEDED` | WARNING | the LLM daily cost cap was reached |
| `TEST` | INFO | `POST /notifications/test` |

## Channels

- **In-app** (always on): stored in the notification inbox and pushed on `/ws/events` as `{"type": "notification", …}`;
  the web client lists them and raises a browser notification when the user allowed it. (Background web push through a
  service worker is not built.)
- **Email** (SMTP, `spring.mail.*`): enabled by `hejje.notify.email.enabled` with `hejje.notify.email.to`/`from`.
- **Telegram**: enabled by `hejje.notify.telegram.enabled` with the bot token in `HEJJE_TELEGRAM_BOT_TOKEN` (never in a
  config file or the API) and `hejje.notify.telegram.chat-id`.

## Rules, rate limits and the delivery log

`notification_rule(event_type, channel, min_severity, enabled)`: a notification goes to a channel when an enabled rule
for its type and that channel exists and its severity is at least the rule's minimum. Seeded: every type in-app; by email
and Telegram everything except `SIGNAL_GENERATED`, `POSITION_CLOSED` and `NEWS_CONTEXT_CHANGED` (the frequent ones). Rules are listed and edited through
`GET /notifications/rules` and `PUT /notifications/rules/{id}` (`admin`).

Each external channel has a rate limit (`hejje.notify.<channel>.per-minute`); above it, notifications are held and sent
as one digest message on the next `hejje.notify.digest-interval`. Repeats with the same key within
`hejje.notify.dedupe-window` are skipped. Every attempt is recorded in `notification_delivery` (SENT, FAILED with the
error, SKIPPED with what the channel is missing, DIGESTED then DIGEST_SENT). Email and Telegram are sent on a background
thread; the in-app push is immediate. SERVER_UNHEALTHY, MAJOR_EVENT_APPROACHING and NEWS_CONTEXT_CHANGED come from a
one-minute watcher that reports transitions only (the first observation after a restart is the baseline). `hejje notify test` (TUI) and the web settings send a TEST notification.
