/**
 * Options (PRD section 9.1 "Options", plan M5.4): Black-76 pricing, implied volatility and greeks on the futures price;
 * the option chain with IV, greeks, PCR and max pain; strike selection for strategy {@code legs}; execution of an
 * options signal as a hedge-first basket and management of the resulting multi-leg position (per-leg and combined
 * exits); options risk controls (lots, premium at risk, defined risk, expiry day). Options strategies are PAPER-only
 * until they have paper history: the historical store has no option candles. Rules: docs/options.md.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.options;
