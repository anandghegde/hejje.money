/**
 * Deterministic, incremental indicators (plan M2.2) shared by the backtester and the live signal engine. Every
 * indicator is fed one closed bar at a time and only exposes values for closed bars, so look-ahead is impossible by
 * construction. Conventions follow TA-Lib (SMA-seeded EMA, Wilder smoothing for RSI/ATR/ADX, population standard
 * deviation for Bollinger bands); see docs/indicators.md.
 */
@org.springframework.modulith.NamedInterface("indicators")
package money.hejje.market.indicators;
