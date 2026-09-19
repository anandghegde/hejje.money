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
 * SIM (plan M7.1) runs only under the {@code sim} or {@code dev} profile, never with {@code prod}, and only with the fake
 * broker adapter: a replay instance can never hold a real broker session.
 */
@Component
class ModeProfileGuard {

    ModeProfileGuard(HejjeProperties properties, money.hejje.common.config.AutoProperties auto, Environment environment) {
        java.util.List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean prod = profiles.contains("prod");
        if (properties.mode() == ExecutionMode.SIM) {
            if (prod || !(profiles.contains("sim") || profiles.contains("dev"))) {
                throw new IllegalStateException("hejje.mode=SIM requires the 'sim' or 'dev' profile and never 'prod'; active profiles: " + profiles);
            }
            String adapter = environment.getProperty("hejje.broker.adapter", "fake");
            if (!"fake".equals(adapter)) {
                throw new IllegalStateException("hejje.mode=SIM replays history with simulated fills; hejje.broker.adapter must be fake, not " + adapter);
            }
            return;
        }
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
