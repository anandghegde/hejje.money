package money.hejje.pulse;

import java.time.Instant;
import java.time.LocalDate;

/** The Technical and Market Pulse of one moment. */
public record PulseSnapshot(LocalDate date, Instant asOf, TechnicalPulse technical, MarketPulse market) {
}
