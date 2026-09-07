package money.hejje.system;

import java.util.Arrays;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup when a live execution mode (CONFIRM or AUTO) is configured outside the {@code prod} profile.
 * Live trading must never be possible from a developer machine or a test run by accident.
 */
@Component
class ModeProfileGuard {

    ModeProfileGuard(HejjeProperties properties, Environment environment) {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (properties.mode() != ExecutionMode.PAPER && !prod) {
            throw new IllegalStateException("hejje.mode=" + properties.mode()
                    + " requires the 'prod' profile; active profiles: "
                    + Arrays.toString(environment.getActiveProfiles()));
        }
    }
}
