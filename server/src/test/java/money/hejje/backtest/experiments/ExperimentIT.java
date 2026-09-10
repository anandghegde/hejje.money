package money.hejje.backtest.experiments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.backtest.SyntheticSessions;
import money.hejje.common.Timeframe;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** An experiment end to end on seeded TCS sessions (plan M4.7): variants run, rank, invalid deltas are reported, promotion creates a DRAFT version. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ExperimentIT extends AbstractIntegrationTest {

    static final LocalDate FIRST = LocalDate.of(2025, 12, 1); // Monday; no other suite seeds TCS here

    @Autowired StrategyService strategies;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;

    UUID tcs;
    String token;

    @BeforeEach
    void setUp() {
        instruments.sync();
        tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        token = adminAccessToken();
        List<Candle> candles = new ArrayList<>();
        candles.addAll(SyntheticSessions.flat(tcs, FIRST, "3500"));
        candles.addAll(SyntheticSessions.flat(tcs, FIRST.plusDays(1), "3500"));
        candles.addAll(SyntheticSessions.session(tcs, FIRST.plusDays(2), List.of(
                SyntheticSessions.bar("3500", "3505", "3495", "3500"), SyntheticSessions.bar("3500", "3503", "3497", "3501"),
                SyntheticSessions.bar("3501", "3504", "3498", "3502"), SyntheticSessions.bar("3502", "3508", "3501", "3507"),
                SyntheticSessions.bar("3508", "3510", "3506", "3509"), SyntheticSessions.bar("3509", "3560", "3508", "3555")), 100_000));
        candles.addAll(SyntheticSessions.session(tcs, FIRST.plusDays(3), List.of(
                SyntheticSessions.bar("3500", "3505", "3495", "3500"), SyntheticSessions.bar("3500", "3503", "3497", "3501"),
                SyntheticSessions.bar("3501", "3504", "3498", "3502"), SyntheticSessions.bar("3502", "3508", "3501", "3507"),
                SyntheticSessions.bar("3508", "3510", "3506", "3509"), SyntheticSessions.bar("3509", "3510", "3480", "3482")), 100_000));
        candles.addAll(SyntheticSessions.session(tcs, FIRST.plusDays(4), List.of(
                SyntheticSessions.bar("3500", "3505", "3495", "3500"), SyntheticSessions.bar("3500", "3503", "3497", "3501"),
                SyntheticSessions.bar("3501", "3504", "3498", "3502"), SyntheticSessions.bar("3502", "3508", "3501", "3507"),
                SyntheticSessions.bar("3508", "3510", "3506", "3509"), SyntheticSessions.bar("3509", "3560", "3508", "3555")), 100_000));
        historical.write(tcs, Timeframe.M5, candles);
    }

    static String yaml(String name) {
        return "name: " + name + "\nuniverse: [NSE:TCS]\ntimeframe: 5m\ndirection: long\nentry:\n  all:\n    - close > opening_range_high(15m)\nstop:\n"
                + "  type: opening_range_low\ntarget:\n  type: risk_multiple\n  value: 2\ntrade_window:\n  start: \"09:30\"\n  end: \"15:00\"\nmax_trades_per_day: 1\n";
    }

    Map<String, Object> experiment(String id) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Map<String, Object> e = rest.exchange("/api/v1/experiments/" + id, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class).getBody();
            if ("DONE".equals(e.get("status")) || "FAILED".equals(e.get("status"))) {
                return e;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("experiment did not finish");
    }

    @Test
    void variantsRunRankAndPromoteWithoutTouchingTheBase() throws Exception {
        StrategyVersion base = strategies.create(yaml("exp_orb_" + UUID.randomUUID().toString().substring(0, 6)), null, "admin");
        Map<String, Object> preview = rest.exchange("/api/v1/experiments/preview", HttpMethod.POST,
                new HttpEntity<>(Map.of("versionId", base.id(), "delta", Map.of("entry_add", List.of("close > vwap"))), bearer(token)), Map.class).getBody();
        assertThat(preview).containsEntry("valid", true);
        assertThat((List<String>) preview.get("entryConditions")).containsExactly("close > opening_range_high(15m)", "close > vwap");

        ResponseEntity<Map> started = rest.exchange("/api/v1/experiments", HttpMethod.POST, new HttpEntity<>(Map.of("versionId", base.id(), "goal", "fewer false breakouts",
                "from", FIRST.toString(), "to", FIRST.plusDays(4).toString(), "splits", Map.of("type", "NONE"),
                "variants", List.of(Map.of("name", "vwap_filter", "description", "only above VWAP", "delta", Map.of("entry_add", List.of("close > vwap"))),
                        Map.of("name", "target_3r", "delta", Map.of("target", Map.of("value", 3))),
                        Map.of("name", "broken", "delta", Map.of("stop", Map.of("type", "atr_multiple"))))), bearer(token)), Map.class);
        assertThat(started.getStatusCode()).as("%s", started.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> e = experiment((String) started.getBody().get("id"));
        assertThat(e).containsEntry("status", "DONE");
        List<Map<String, Object>> variants = (List<Map<String, Object>>) e.get("variants");
        assertThat(variants).extracting(v -> v.get("name")).contains("baseline", "vwap_filter", "target_3r", "broken");
        Map<String, Object> broken = variants.stream().filter(v -> v.get("name").equals("broken")).findFirst().orElseThrow();
        assertThat(broken).containsEntry("status", "INVALID");
        assertThat((String) broken.get("error")).contains("stop.value");
        List<Map<String, Object>> ranked = variants.stream().filter(v -> v.get("rank") != null).toList();
        assertThat(ranked).extracting(v -> v.get("rank")).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(ranked).allSatisfy(v -> {
            assertThat(v.get("status")).isEqualTo("DONE");
            assertThat((String) v.get("verdict")).isNotBlank();
            assertThat((Map<String, Object>) v.get("metrics")).containsKey("overall");
            assertThat((List<String>) v.get("warnings")).anyMatch(w -> w.startsWith("LOW_TRADES"));
        });
        assertThat((List<String>) e.get("notes")).anyMatch(n -> n.startsWith("MULTIPLE_COMPARISONS: 3 variants")).anyMatch(n -> n.startsWith("NO_OUT_OF_SAMPLE"));
        assertThat(variants.stream().filter(v -> v.get("name").equals("baseline")).findFirst().orElseThrow()).containsEntry("verdict", "BASELINE");

        String variantId = (String) variants.stream().filter(v -> v.get("name").equals("target_3r")).findFirst().orElseThrow().get("id");
        ResponseEntity<Map> promoted = rest.exchange("/api/v1/experiments/" + e.get("id") + "/variants/" + variantId + "/promote", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), Map.class);
        assertThat(promoted.getStatusCode()).as("%s", promoted.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(promoted.getBody()).containsEntry("version", 2).containsEntry("status", "DRAFT");
        assertThat((String) promoted.getBody().get("changeNote")).startsWith("promoted from experiment");
        assertThat(strategies.versionById(base.id()).orElseThrow().status().name()).isEqualTo("DRAFT");
        assertThat(rest.exchange("/api/v1/experiments/" + e.get("id") + "/variants/" + variantId + "/promote", HttpMethod.POST, new HttpEntity<>(bearer(token)),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((List<?>) rest.exchange("/api/v1/experiments?versionId=" + base.id(), HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class).getBody())
                .hasSize(1);
    }
}
