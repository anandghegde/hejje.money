/**
 * Calibration (plan M9.2, docs/calibration.md): whether a probability means anything. Callers that make a probabilistic
 * prediction (Jev answers, bot confidence) record it with a pre-declared outcome rule; a nightly job (and the end of a
 * SIM session) labels it from stored M1 candles; reports give the hit rate per probability bucket with counts, the Brier
 * score and the expected calibration error, and {@link money.hejje.calibration.CalibrationService#passes} is the bar a
 * gate on a Jev number must clear.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.calibration;
