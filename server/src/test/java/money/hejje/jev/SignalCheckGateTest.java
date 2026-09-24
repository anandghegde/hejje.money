package money.hejje.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import money.hejje.calibration.CalibrationService;
import money.hejje.llm.JevQuestion;
import money.hejje.llm.JevQuestionSet;
import money.hejje.llm.JevQuestionSets;
import money.hejje.llm.JevService;
import org.junit.jupiter.api.Test;

/** The signal-check gate is refused, at startup and at runtime, until calibration passes (plan M9.5). */
class SignalCheckGateTest {

    SignalCheck check(boolean enabled, String gate, boolean passes) {
        JevQuestionSets sets = mock(JevQuestionSets.class);
        when(sets.get(any())).thenReturn(new JevQuestionSet("bot-stage2", "1", Map.of("setup", JevQuestion.noul("x"))));
        CalibrationService calibration = mock(CalibrationService.class);
        when(calibration.passes(eq(SignalCheck.PURPOSE), eq("1"))).thenReturn(passes);
        JevService jev = mock(JevService.class);
        when(jev.enabled()).thenReturn(true);
        return new SignalCheck(new SignalCheckProperties(enabled, gate, "bot-stage2"), jev, sets, null, null, null, calibration, null);
    }

    @Test
    void aGateAboveOffIsRefusedAtStartupUntilCalibrationPasses() {
        check(false, "off", false).afterSingletonsInstantiated();
        assertThatThrownBy(() -> check(true, "caution", false).afterSingletonsInstantiated()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("calibration of signal-check v1 does not pass");
        assertThatThrownBy(() -> check(false, "approval", true).afterSingletonsInstantiated()).hasMessageContaining("needs hejje.jev.signal-check.enabled=true");
        SignalCheck ok = check(true, "approval", true);
        ok.afterSingletonsInstantiated();
        assertThat(ok.gate()).isEqualTo(SignalCheck.Gate.APPROVAL);
    }

    @Test
    void aRuntimeChangeFollowsTheSameRule() {
        SignalCheck c = check(true, "off", false);
        assertThat(c.gate(SignalCheck.Gate.CAUTION)).contains("does not pass");
        assertThat(c.gate()).isEqualTo(SignalCheck.Gate.OFF);
        SignalCheck passing = check(true, "off", true);
        assertThat(passing.gate(SignalCheck.Gate.CAUTION)).isNull();
        assertThat(passing.gate()).isEqualTo(SignalCheck.Gate.CAUTION);
        passing.enabled(false);
        assertThat(passing.gate()).isEqualTo(SignalCheck.Gate.OFF);
    }
}
