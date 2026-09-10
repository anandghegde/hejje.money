package money.hejje.analytics;

import java.time.LocalDate;

/** Entry and exit slippage for a period (plan M4.5). */
public record SlippageReport(String mode, LocalDate from, LocalDate to, PerformanceMath.SlippageStats slippage) {
}
