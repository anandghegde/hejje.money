package money.hejje.analytics;

import java.time.LocalDate;

/** A SIMULATED counterfactual with the actual figures alongside for a period (plan M4.5). */
public record CounterfactualReport(String mode, LocalDate from, LocalDate to, PerformanceMath.Counterfactual counterfactual) {
}
