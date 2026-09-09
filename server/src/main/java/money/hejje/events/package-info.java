/**
 * Event calendar and event risk (PRD section 18, plan M3.3): structured events separate from news, from pluggable
 * sources (computed holidays/expiries/rebalances, a curated macro YAML, CSV import, an optional NSE fetcher),
 * proximity-based {@code EventRisk}, strategy {@code event_rules} enforced in the recommendation and as a risk check, and
 * the regime's event environment. Optional at runtime: when disabled or down, risk is LOW with an "unavailable" evidence
 * line and nothing else changes. Rules: docs/events.md, thresholds in config/events.yaml.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.events;
