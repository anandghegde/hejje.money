package money.hejje.analytics;

import java.time.LocalDate;

/** Rule adherence for a period (plan M4.5). */
public record AdherenceReport(String mode, LocalDate from, LocalDate to, PerformanceMath.AdherenceStats adherence) {
}
