/**
 * Signals module (plan M2.6): the live signal engine. One {@code StrategyRunner} per enabled deployment × instrument
 * evaluates the same rules as the backtester on every closed candle, produces signals with validity windows, and
 * manages the resulting positions deterministically (broker-side protective stop, software target and backup stop,
 * trailing, rule exits, force exit). Runs without any LLM or context service.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.signals;
