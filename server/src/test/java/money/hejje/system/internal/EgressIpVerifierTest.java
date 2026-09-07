package money.hejje.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditService;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.system.EgressIpStatus;
import money.hejje.system.EgressIpStatusChanged;
import money.hejje.system.ReadinessCheck;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class EgressIpVerifierTest {

    static final class FakeResolver implements PublicIpResolver {
        private final String name;
        volatile String ip;

        FakeResolver(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Optional<String> resolve() {
            return Optional.ofNullable(ip);
        }
    }

    final List<Object> published = new ArrayList<>();
    final ApplicationEventPublisher events = published::add;
    final AuditService audit = mock(AuditService.class);
    final HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);

    EgressIpVerifier verifier(boolean enabled, List<String> expected, PublicIpResolver... resolvers) {
        return new EgressIpVerifier(enabled, List.of(resolvers), expected, events, audit, clock, new SimpleMeterRegistry());
    }

    @Test
    void verifiedWhenBothResolversAgreeWithExpected() {
        EgressIpVerifier v = verifier(true, List.of("203.0.113.10"), new FakeResolver("a", "203.0.113.10"), new FakeResolver("b", "203.0.113.10"));
        assertThat(v.status()).isEqualTo(EgressIpStatus.UNKNOWN);
        assertThat(v.check().status()).isEqualTo(EgressIpStatus.VERIFIED);
        assertThat(v.result().status()).isEqualTo(ReadinessCheck.CheckResult.Status.OK);
        assertThat(published).hasSize(1);
        EgressIpStatusChanged event = (EgressIpStatusChanged) published.get(0);
        assertThat(event.previous()).isEqualTo(EgressIpStatus.UNKNOWN);
        assertThat(event.current()).isEqualTo(EgressIpStatus.VERIFIED);
        assertThat(event.observedIps()).containsExactly("203.0.113.10");
        verify(audit, times(1)).record(any(AuditEvent.class));
    }

    @Test
    void mismatchWhenIpIsNotExpected() {
        EgressIpVerifier v = verifier(true, List.of("203.0.113.10"), new FakeResolver("a", "198.51.100.7"), new FakeResolver("b", "198.51.100.7"));
        assertThat(v.check().status()).isEqualTo(EgressIpStatus.MISMATCH);
        assertThat(v.result().allowsExecution()).isFalse();
        assertThat(v.result().detail()).contains("198.51.100.7");
    }

    @Test
    void mismatchWhenResolversDisagreeOrNothingIsExpected() {
        EgressIpVerifier disagree = verifier(true, List.of("203.0.113.10"), new FakeResolver("a", "203.0.113.10"), new FakeResolver("b", "198.51.100.7"));
        assertThat(disagree.check().status()).isEqualTo(EgressIpStatus.MISMATCH);
        assertThat(disagree.lastResult().detail()).contains("disagree");

        EgressIpVerifier unconfigured = verifier(true, List.of(), new FakeResolver("a", "203.0.113.10"));
        assertThat(unconfigured.check().status()).isEqualTo(EgressIpStatus.MISMATCH);
    }

    @Test
    void oneFailingResolverIsToleratedBothFailingIsUnknown() {
        FakeResolver a = new FakeResolver("a", null);
        FakeResolver b = new FakeResolver("b", "203.0.113.10");
        EgressIpVerifier v = verifier(true, List.of("203.0.113.10"), a, b);
        assertThat(v.check().status()).isEqualTo(EgressIpStatus.VERIFIED);
        b.ip = null;
        assertThat(v.check().status()).isEqualTo(EgressIpStatus.UNKNOWN);
        assertThat(v.result().allowsExecution()).isFalse();
    }

    @Test
    void publishesOnlyOnChange() {
        FakeResolver a = new FakeResolver("a", "203.0.113.10");
        EgressIpVerifier v = verifier(true, List.of("203.0.113.10"), a);
        v.check();
        v.check();
        v.check();
        assertThat(published).hasSize(1);
        a.ip = "198.51.100.7";
        v.check();
        v.check();
        assertThat(published).hasSize(2);
        assertThat(((EgressIpStatusChanged) published.get(1)).current()).isEqualTo(EgressIpStatus.MISMATCH);
        a.ip = "203.0.113.10";
        v.check();
        assertThat(published).hasSize(3);
        verify(audit, times(3)).record(any(AuditEvent.class));
    }

    @Test
    void disabledIsSkippedAndNeverResolves() {
        FakeResolver a = new FakeResolver("a", "1.1.1.1");
        EgressIpVerifier v = verifier(false, List.of("203.0.113.10"), a);
        assertThat(v.status()).isEqualTo(EgressIpStatus.SKIPPED);
        assertThat(v.check().status()).isEqualTo(EgressIpStatus.SKIPPED);
        assertThat(v.result().status()).isEqualTo(ReadinessCheck.CheckResult.Status.SKIPPED);
        assertThat(v.result().allowsExecution()).isTrue();
        assertThat(published).isEmpty();
        verifyNoInteractions(audit);
    }
}
