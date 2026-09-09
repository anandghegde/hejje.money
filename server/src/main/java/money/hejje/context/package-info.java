/**
 * Context engine (PRD section 19, plan M3.5): composes the regime, pulse, event and news adjusters into the Strategy
 * Context Card ({@link money.hejje.context.StrategyContext}) with one GREEN / AMBER / RED / UNKNOWN row per indicator
 * and the evidence behind it. Every input is optional: a missing service yields an UNKNOWN row, never an error.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.context;
