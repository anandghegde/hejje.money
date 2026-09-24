/**
 * Daily market context over a wide D1 universe (plan Phase 8): the evening candle refresh, price/volume ratings,
 * industry groups, bases with their trade plan, lists, screens and the watchlist. Context and evidence only: nothing
 * here places an order. Off by default ({@code hejje.ratings.enabled}); the trading core runs unchanged without it.
 * Formulas and thresholds: docs/ratings.md, config/ratings.yaml.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.ratings;
