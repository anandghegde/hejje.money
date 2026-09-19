package money.hejje.system;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.ZoneId;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ModeProfileGuardTest {

    static HejjeProperties props(ExecutionMode mode) {
        return new HejjeProperties(mode, ZoneId.of("Asia/Kolkata"), Path.of("./data"));
    }

    static money.hejje.common.config.AutoProperties auto(boolean acknowledged) {
        return new money.hejje.common.config.AutoProperties(acknowledged, 30, 3, 5000, "DEGRADING");
    }

    @Test
    void paperIsAllowedInAnyProfile() {
        new ModeProfileGuard(props(ExecutionMode.PAPER), auto(false), new MockEnvironment().withProperty("x", "y"));
    }

    @Test
    void liveModesRequireProdProfile() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatThrownBy(() -> new ModeProfileGuard(props(ExecutionMode.CONFIRM), auto(true), dev))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRM");
        assertThatThrownBy(() -> new ModeProfileGuard(props(ExecutionMode.AUTO), auto(true), dev))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveModesAllowedInProdAndAutoNeedsTheAcknowledgement() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        new ModeProfileGuard(props(ExecutionMode.CONFIRM), auto(false), prod);
        assertThatThrownBy(() -> new ModeProfileGuard(props(ExecutionMode.AUTO), auto(false), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hejje.auto.acknowledged=true");
        new ModeProfileGuard(props(ExecutionMode.AUTO), auto(true), prod);
    }

    @Test
    void simRunsOnlyUnderSimOrDevWithTheFakeBroker() {
        MockEnvironment sim = new MockEnvironment();
        sim.setActiveProfiles("sim");
        new ModeProfileGuard(props(ExecutionMode.SIM), auto(false), sim);
        MockEnvironment dev = new MockEnvironment().withProperty("hejje.broker.adapter", "fake");
        dev.setActiveProfiles("dev");
        new ModeProfileGuard(props(ExecutionMode.SIM), auto(false), dev);

        MockEnvironment none = new MockEnvironment();
        assertThatThrownBy(() -> new ModeProfileGuard(props(ExecutionMode.SIM), auto(false), none)).hasMessageContaining("'sim' or 'dev'");
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("sim", "prod");
        assertThatThrownBy(() -> new ModeProfileGuard(props(ExecutionMode.SIM), auto(false), prod)).hasMessageContaining("never 'prod'");
        MockEnvironment realBroker = new MockEnvironment().withProperty("hejje.broker.adapter", "zerodha");
        realBroker.setActiveProfiles("sim");
        assertThatThrownBy(() -> new ModeProfileGuard(props(ExecutionMode.SIM), auto(false), realBroker)).hasMessageContaining("must be fake");
    }
}
