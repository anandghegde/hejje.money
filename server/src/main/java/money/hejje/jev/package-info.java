/**
 * Jev on market decisions (plan M9.5, docs/jev.md): the state Hejje builds from its own candles, order-book data and
 * context, the rules that read Jev's stage answers, and the signal check (a second opinion on every strategy signal,
 * which annotates it and, only with a gate that calibration allows, adds a caution or requires an approval). The Jev bot
 * itself runs in the {@code bots} module on these types.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.jev;
