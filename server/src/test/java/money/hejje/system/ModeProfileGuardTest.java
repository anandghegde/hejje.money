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

    @Test
    void paperIsAllowedInAnyProfile() {
        new ModeProfileGuard(props(ExecutionMode.PAPER), new MockEnvironment().withProperty("x", "y"));
    }

    @Test
    void liveModesRequireProdProfile() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatThrownBy(() -> new ModeProfileGuard(props(ExecutionMode.CONFIRM), dev))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRM");
        assertThatThrownBy(() -> new ModeProfileGuard(props(ExecutionMode.AUTO), dev))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveModesAllowedInProd() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        new ModeProfileGuard(props(ExecutionMode.AUTO), prod);
    }
}
