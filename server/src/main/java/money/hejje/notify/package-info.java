/**
 * Notifications (PRD section 58, plan M5.5): what happens in Hejje (signals, rejections, stops, closes, risk
 * thresholds, broker and server health, drift, events, news, approvals) becomes a notification, stored in the in-app
 * inbox and delivered through channels (in-app, email, Telegram) according to explicit rules, with per-channel rate
 * limits folded into digests and a delivery log. Never on the trading core's path: a channel failing changes nothing
 * else. Rules: docs/notifications.md.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.notify;
