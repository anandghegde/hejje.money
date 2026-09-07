package money.hejje.system;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Aggregates every {@link ReadinessCheck}. Live execution is enabled only when no check is BLOCKING.
 * Public API of the system module; the execution gate (Phase 1) consults it before every broker call.
 */
@Component
public class ExecutionReadiness {

    private final List<ReadinessCheck> checks;

    ExecutionReadiness(List<ReadinessCheck> checks, MeterRegistry meters) {
        this.checks = List.copyOf(checks);
        Gauge.builder("hejje_execution_enabled", this, r -> r.isExecutionEnabled() ? 1 : 0)
                .description("1 when every readiness check allows live execution").register(meters);
    }

    public boolean isExecutionEnabled() {
        return checks.stream().allMatch(c -> c.result().allowsExecution());
    }

    /** Names and details of the checks currently blocking execution. */
    public List<String> reasons() {
        return checks.stream()
                .filter(c -> !c.result().allowsExecution())
                .map(c -> c.name() + ": " + c.result().detail())
                .toList();
    }

    /** Every check's current result, keyed by name, in registration order. */
    public Map<String, ReadinessCheck.CheckResult> results() {
        Map<String, ReadinessCheck.CheckResult> out = new LinkedHashMap<>();
        checks.forEach(c -> out.put(c.name(), c.result()));
        return out;
    }
}
