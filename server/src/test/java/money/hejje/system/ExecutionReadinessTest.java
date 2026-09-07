package money.hejje.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionReadinessTest {

    static ReadinessCheck check(String name, ReadinessCheck.CheckResult result) {
        return new ReadinessCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public CheckResult result() {
                return result;
            }
        };
    }

    @Test
    void enabledWhenNothingBlocks() {
        ExecutionReadiness readiness = new ExecutionReadiness(List.of(
                check("staticIp", ReadinessCheck.CheckResult.ok("203.0.113.10")),
                check("clockSync", ReadinessCheck.CheckResult.skipped("disabled"))), new SimpleMeterRegistry());
        assertThat(readiness.isExecutionEnabled()).isTrue();
        assertThat(readiness.reasons()).isEmpty();
        assertThat(readiness.results()).containsKeys("staticIp", "clockSync");
    }

    @Test
    void anyBlockingCheckDisablesExecutionWithReason() {
        ExecutionReadiness readiness = new ExecutionReadiness(List.of(
                check("staticIp", ReadinessCheck.CheckResult.blocking("MISMATCH: egress 1.2.3.4")),
                check("clockSync", ReadinessCheck.CheckResult.ok("drift 10 ms"))), new SimpleMeterRegistry());
        assertThat(readiness.isExecutionEnabled()).isFalse();
        assertThat(readiness.reasons()).containsExactly("staticIp: MISMATCH: egress 1.2.3.4");
    }
}
