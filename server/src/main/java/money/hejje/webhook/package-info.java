/**
 * External signals and webhooks (PRD section 59, plan M5.5): authenticated endpoints that turn a TradingView/n8n/script
 * alert into a signal intent on a mapped strategy (or a manual external order proposal) — never a raw broker order —
 * which then goes through validation, the strategy's rules, risk and the approval policy. Rules: docs/webhooks.md.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.webhook;
