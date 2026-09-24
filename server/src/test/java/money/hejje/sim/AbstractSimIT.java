package money.hejje.sim;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A SIM instance for integration tests (plan M7.1/M7.2): its own Postgres and data directory, never the shared
 * integration-test context and database. Every SIM IT extends this so they share one Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "hejje.mode=SIM",
        "hejje.sim.start=2026-09-08T03:45:00Z",
        "hejje.instruments.sync-on-startup=true",          // the fake broker's fixture master (no exported live master here)
        "hejje.strategy.allow-forced-status=true",
        "hejje.analogs.enabled=true",                        // SessionAnalogSimIT; every SIM IT shares this one context
        "hejje.analogs.session.universe=ratings-test",      // five fixture equities (test resources)
        "hejje.sim.decision-timeout=PT0.5S",                // a bot's unanswered decision point is SKIPPED quickly
        "hejje.jev.enabled=true",                            // FixtureJev for the Jev bot (JevBotSimIT)
        "hejje.jev.base-url=fixture",
        "hejje.auth.admin-password=sim-admin-password",
        "hejje.auth.jwt-secret=sim-secret-sim-secret-sim-secret-sim-secret",
        "hejje.security.encryption-key=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
})
@ActiveProfiles("sim")
public abstract class AbstractSimIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    public static final Path DATA_DIR;

    static {
        POSTGRES.start();
        try {
            DATA_DIR = Files.createTempDirectory("hejje-sim-it");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("hejje.data-dir", DATA_DIR::toString);
    }
}
