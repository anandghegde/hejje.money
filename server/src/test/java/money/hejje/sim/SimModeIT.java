package money.hejje.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

/**
 * A SIM instance end to end (plan M7.1): the simulation clock at {@code hejje.sim.start}, no {@code @Scheduled} job on
 * wall time, and the market pipeline's candle close driven by simulation time through {@link SimTime}.
 */
class SimModeIT extends AbstractSimIT {

    static final Instant OPEN = Instant.parse("2026-09-08T03:45:00Z"); // 09:15 IST

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
        pipeline.resetForSimulation(); // other SIM ITs share the context
        time.startAt(OPEN);
        assertThat(clock.now()).isEqualTo(OPEN);
        java.util.Map<String, Integer> before = time.scheduler().firedCounts();
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(time.scheduler().firedCounts()).as("nothing runs on wall time").isEqualTo(before);
        assertThat(clock.now()).as("the clock stands still").isEqualTo(OPEN);
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

    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @Test
    void theLiveInstrumentMasterImportsWithItsIds() throws Exception {
        UUID id = UUID.fromString("01a0b8dd-0000-7000-8000-00000000ab01");
        java.nio.file.Path file = java.nio.file.Files.createTempFile("master", ".json");
        java.nio.file.Files.writeString(file, """
                [{"id":"%s","symbol":"SIMTEST","name":"Sim Test Ltd","exchange":"NSE","type":"EQ","underlying":null,"expiry":null,"strike":null,
                  "optionType":null,"lotSize":1,"tickSize":0.05,"isin":null,"active":true,"updatedAt":"2026-09-01T00:00:00Z",
                  "hejjeSymbol":{"exchange":"NSE","symbol":"SIMTEST"},"derivative":false}]
                """.formatted(id));
        assertThat(instruments.importMaster(file, "fake", json)).isEqualTo(1);
        assertThat(instruments.findById(id)).get().extracting(Instrument::symbol).isEqualTo("SIMTEST");
        assertThat(instruments.mapping(id, "fake")).get().extracting(m -> m.brokerToken()).isEqualTo(id.toString());
        assertThat(instruments.importMaster(file, "fake", json)).as("idempotent").isEqualTo(1);
    }

    static MarketTick tick(UUID id, Instant at, String price, long volume) {
        BigDecimal p = new BigDecimal(price);
        return new MarketTick(id, at, p, null, null, volume, 0, MarketTick.Mode.LTP);
    }
}
