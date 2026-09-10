package money.hejje.system;

import java.util.Arrays;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup when a live execution mode (CONFIRM or AUTO) is configured outside the {@code prod} profile, and when
 * AUTO is configured without {@code hejje.auto.acknowledged=true} (plan M5.2). Live trading must never be possible from
 * a developer machine or a test run by accident, and automatic execution never without an explicit acknowledgement.
 */
@Component
class ModeProfileGuard {

    ModeProfileGuard(HejjeProperties properties, money.hejje.common.config.AutoProperties auto, Environment environment) {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (properties.mode() != ExecutionMode.PAPER && !prod) {
            throw new IllegalStateException("hejje.mode=" + properties.mode()
                    + " requires the 'prod' profile; active profiles: "
                    + Arrays.toString(environment.getActiveProfiles()));
        }
        if (properties.mode() == ExecutionMode.AUTO && !auto.acknowledged()) {
            throw new IllegalStateException("hejje.mode=AUTO executes policy-eligible strategy signals without confirmation; "
                    + "set hejje.auto.acknowledged=true (HEJJE_AUTO_ACKNOWLEDGED) to confirm");
        }
    }
}
