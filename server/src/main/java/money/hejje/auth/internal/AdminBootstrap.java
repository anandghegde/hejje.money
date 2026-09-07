package money.hejje.auth.internal;

import java.util.Arrays;
import money.hejje.auth.AuthProperties;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * On first start with no user, creates {@code admin} from {@code HEJJE_ADMIN_PASSWORD} (Argon2id).
 * In prod, refuses to start when no user exists and the variable is missing.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    static final String ADMIN_USERNAME = "admin";

    private final UserStore users;
    private final AuthProperties properties;
    private final PasswordEncoder encoder;
    private final HejjeClock clock;
    private final Environment environment;

    AdminBootstrap(UserStore users, AuthProperties properties, PasswordEncoder encoder, HejjeClock clock, Environment environment) {
        this.users = users;
        this.properties = properties;
        this.encoder = encoder;
        this.clock = clock;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String password = properties.adminPassword();
        if (password == null || password.isBlank()) {
            if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException("No user exists and HEJJE_ADMIN_PASSWORD is not set; refusing to start in prod");
            }
            log.warn("No user exists and HEJJE_ADMIN_PASSWORD is not set; login is impossible until it is provided");
            return;
        }
        users.insert(new UserStore.User(Ids.newId(), ADMIN_USERNAME, encoder.encode(password), clock.now()));
        log.info("Created bootstrap user '{}'", ADMIN_USERNAME);
    }
}
