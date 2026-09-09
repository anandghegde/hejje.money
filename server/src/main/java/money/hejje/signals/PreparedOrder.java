package money.hejje.signals;

import java.util.List;
import java.util.Map;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.risk.RiskDecision;

/**
 * The proposed order for a signal plus a dry-run risk decision; nothing has been submitted.
 *
 * @param sizing how the quantity was derived (risk money, per-unit risk, lot size, cap)
 */
public record PreparedOrder(Signal signal, OrderIntentCommand proposal, RiskDecision risk, Map<String, Object> sizing, List<String> notes) {
}
