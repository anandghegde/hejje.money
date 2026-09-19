package money.hejje.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Timeframe;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.SimClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.internal.MarketPipeline;
import money.hejje.risk.RiskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A SIM instance end to end (plan M7.1): its own database and data directory (never the shared integration-test context),
 * the simulation clock at {@code hejje.sim.start}, no {@code @Scheduled} job on wall time, and the market pipeline's
 * candle close driven by simulation time through {@link SimTime}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "hejje.mode=SIM",
        "hejje.sim.start=2026-09-08T03:45:00Z",
        "hejje.auth.admin-password=sim-admin-password",
        "hejje.auth.jwt-secret=sim-secret-sim-secret-sim-secret-sim-secret",
        "hejje.security.encryption-key=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
})
@ActiveProfiles("sim")
class SimModeIT {

    static final Instant OPEN = Instant.parse("2026-09-08T03:45:00Z"); // 09:15 IST

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("hejje-sim-it");
        registry.add("hejje.data-dir", dir::toString);
    }

    @Autowired SimTime time;
    @Autowired HejjeClock clock;
    @Autowired HejjeProperties properties;
    @Autowired MarketPipeline pipeline;
    @Autowired InstrumentService instruments;
    @Autowired RiskService risk;
    @Autowired money.hejje.market.MarketService market;

    @Test
    void simulationTimeDrivesTheScheduledJobs() {
        assertThat(properties.mode()).isEqualTo(ExecutionMode.SIM);
        assertThat(time.clock()).isInstanceOf(SimClock.class);
        time.startAt(OPEN);
        assertThat(clock.now()).isEqualTo(OPEN);
        assertThat(time.scheduler().firedCounts()).as("nothing runs on wall time").isEmpty();
        assertThat(time.scheduler().runningJobs()).first().isEqualTo("MarketPipeline#tick");
        assertThat(time.scheduler().runningJobs()).doesNotContain("NewsPoller#poll", "EgressIpVerifier#scheduledCheck", "InstrumentSyncJob#scheduled");
        assertThat(risk.limits(ExecutionMode.SIM)).as("SIM keeps its own risk limits (V34)").isNotNull();
        assertThat(risk.killSwitch(ExecutionMode.SIM).stopNewOrders()).isFalse();

        // ticks during the 09:15 minute, then one simulated minute: the pipeline's job closes the M1 candle at 09:16
        UUID infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        pipeline.onTick(tick(infy, OPEN.plusSeconds(5), "1500.00", 100));
        pipeline.onTick(tick(infy, OPEN.plusSeconds(40), "1504.50", 200));
        pipeline.onTick(tick(infy, OPEN.plusSeconds(55), "1502.00", 300));
        assertThat(market.candles(infy, Timeframe.M1, OPEN, OPEN.plusSeconds(60)).stream().filter(c -> !c.synthetic()).toList())
                .as("still open at 09:15:59").isEmpty();
        List<String> ran = time.advance(Duration.ofMinutes(1));
        assertThat(ran).contains("MarketPipeline#tick");
        List<Candle> closed = market.candles(infy, Timeframe.M1, OPEN, OPEN.plusSeconds(60));
        assertThat(closed).singleElement().satisfies(c -> {
            assertThat(c.openTime()).isEqualTo(OPEN);
            assertThat(c.open()).isEqualByComparingTo("1500.00");
            assertThat(c.high()).isEqualByComparingTo("1504.50");
            assertThat(c.close()).isEqualByComparingTo("1502.00");
        });

        // the same steps from the same start fire the same jobs in the same order
        time.startAt(OPEN);
        List<String> first = time.advance(Duration.ofMinutes(5));
        time.startAt(OPEN);
        assertThat(time.advance(Duration.ofMinutes(5))).isEqualTo(first);
    }

    static MarketTick tick(UUID id, Instant at, String price, long volume) {
        BigDecimal p = new BigDecimal(price);
        return new MarketTick(id, at, p, null, null, volume, 0, MarketTick.Mode.LTP);
    }
}
