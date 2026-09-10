package money.hejje.analytics;

import java.time.LocalDate;

/** Loss attribution over closed round trips for a period (plan M4.5). */
public record LossReport(String mode, LocalDate from, LocalDate to, PerformanceMath.LossAttribution attribution) {
}
