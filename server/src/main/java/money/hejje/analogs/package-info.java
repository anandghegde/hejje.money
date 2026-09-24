/**
 * Historical analogs (plan M8.5, M8.6): for a symbol and a lookback, the past windows anywhere in the universe that
 * looked like now, and the distribution of what followed them. Evidence only: every rate carries its count, thin
 * evidence is tagged {@code INSUFFICIENT}, prose is templated from the fields. Off by default
 * ({@code hejje.analogs.enabled}); nothing in the trading core depends on it. Methodology: docs/analogs.md,
 * config/analogs.yaml.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.analogs;
