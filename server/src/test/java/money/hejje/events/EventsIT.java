package money.hejje.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.EventEnvironmentSource;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoringService;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Calendar sources, endpoints, risk, the regime environment and the score adjuster over a session in 2026 no other test uses. */
class EventsIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 7); // Wednesday: the curated RBI MPC decision at 10:00

    @Autowired EventService events;
    @Autowired EventEnvironmentSource environment;
    @Autowired InstrumentService instruments;
    @Autowired StrategyService strategies;
    @Autowired ScoringService scoring;
    @Autowired AuditService audit;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    UUID infy;
    UUID niftyFut;
    String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE market_event, strategy_score, strategy_deployment, strategy_version, strategy CASCADE");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        niftyFut = instruments.nearestFuture("NIFTY", DAY).map(Instrument::id).orElseThrow();
        clock.setIst(DAY + "T09:15:00");
        token = adminAccessToken(); // after moving the clock: tokens live 15 minutes of application time
    }

    @AfterEach
    void resetClock() {
        clock.set(java.time.Instant.now());
    }

    @Test
    void refreshPullsComputedAndCuratedSourcesAndRisksFollowProximity() {
        ResponseEntity<Map> refreshed = rest.exchange("/api/v1/events/refresh?from=2026-09-01&to=2026-10-31", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> bySource = (Map<?, ?>) refreshed.getBody().get("bySource");
        assertThat(((Number) bySource.get("computed")).intValue()).isGreaterThan(0); // Gandhi Jayanti 2026-10-02 and the fixture's NIFTY expiries
        assertThat(((Number) bySource.get("curated")).intValue()).isGreaterThanOrEqualTo(5);
        assertThat(bySource.containsKey("nse")).isFalse(); // off by default
        int inserted = ((Number) refreshed.getBody().get("inserted")).intValue();
        // idempotent
        ResponseEntity<Map> again = rest.exchange("/api/v1/events/refresh?from=2026-09-01&to=2026-10-31", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(((Number) again.getBody().get("inserted")).intValue()).isZero();
        assertThat(inserted).isGreaterThan(5);

        ResponseEntity<List> holiday = rest.exchange("/api/v1/events?from=2026-10-02&to=2026-10-02", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(holiday.getBody()).anySatisfy(e -> assertThat(((Map<?, ?>) e).get("type")).isEqualTo("HOLIDAY"));
        ResponseEntity<List> expiries = rest.exchange("/api/v1/events?from=2026-09-01&to=2026-09-30", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(expiries.getBody()).anySatisfy(e -> {
            Map<?, ?> m = (Map<?, ?>) e;
            assertThat(m.get("type")).isEqualTo("FNO_EXPIRY");
            assertThat((String) m.get("title")).contains("NIFTY");
        });

        // 09:15 with the RBI decision at 10:00: HIGH for everyone (macro within 60 min)
        ResponseEntity<Map> market = rest.exchange("/api/v1/events/risk", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(market.getBody().get("level")).isEqualTo("HIGH");
        assertThat(((Number) market.getBody().get("minutesTo")).intValue()).isEqualTo(45);
        assertThat(((Map<?, ?>) market.getBody().get("nextEvent")).get("title")).isEqualTo("RBI MPC decision");
        // 11:00: over -> MEDIUM (macro today)
        clock.setIst(DAY + "T11:00:00");
        EventRisk later = events.risk(infy);
        assertThat(later.level()).isEqualTo(EventRiskLevel.MEDIUM);
        assertThat(later.evidence().get(0)).contains("RBI MPC decision").contains("→ MEDIUM");
        // the day before: LOW, next event is tomorrow's decision
        clock.setIst(DAY.minusDays(1) + "T11:00:00");
        EventRisk before = events.risk(infy);
        assertThat(before.level()).isEqualTo(EventRiskLevel.LOW);
        assertThat(events.nextEventLine(before)).isEqualTo("RBI MPC decision — Tomorrow 10:00");

        // regime environment from the calendar
        assertThat(environment.environment(DAY)).isEqualTo(EventEnvironment.RBI);
        assertThat(environment.environment(LocalDate.of(2026, 9, 17))).isEqualTo(EventEnvironment.FED);
        assertThat(environment.environment(LocalDate.of(2026, 9, 15))).isEqualTo(EventEnvironment.EXPIRY_SESSION); // fixture NIFTY options expire 2026-09-15
        assertThat(environment.environment(LocalDate.of(2026, 9, 21))).isEqualTo(EventEnvironment.NORMAL);
    }

    @Test
    void manualAddCsvImportAndInstrumentRisk() {
        HttpHeaders json = bearer(token);
        ResponseEntity<Map> added = rest.exchange("/api/v1/events", HttpMethod.POST, new HttpEntity<>(Map.of("type", "RESULTS", "symbol", "NSE:INFY",
                "title", "Q2 results", "date", DAY.toString(), "time", "16:00"), json), Map.class);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.getBody().get("scope")).isEqualTo("INSTRUMENT");
        assertThat(added.getBody().get("instrumentId")).isEqualTo(infy.toString());
        assertThat(rest.exchange("/api/v1/events", HttpMethod.POST, new HttpEntity<>(Map.of("type", "RESULTS", "title", "x"), json), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        HttpHeaders csvHeaders = bearer(token);
        csvHeaders.setContentType(MediaType.parseMediaType("text/csv"));
        String csv = "type,symbol,title,date,time\nEX_DIVIDEND,NSE:TCS,Interim dividend,%s,\nBOARD_MEETING,NSE:NOPE,Board meeting,%s,\n".formatted(DAY, DAY);
        ResponseEntity<Map> imported = rest.exchange("/api/v1/events/import", HttpMethod.POST, new HttpEntity<>(csv, csvHeaders), Map.class);
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imported.getBody().get("imported")).isEqualTo(1);
        assertThat((List<String>) imported.getBody().get("errors")).hasSize(1);

        // INFY: results today -> HIGH; TCS: ex-date -> MEDIUM; the NIFTY future: nothing (curated RBI not refreshed here) -> LOW
        ResponseEntity<Map> infyRisk = rest.exchange("/api/v1/events/risk?instrumentId=" + infy, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(infyRisk.getBody().get("level")).isEqualTo("HIGH");
        assertThat((List<String>) infyRisk.getBody().get("evidence")).anySatisfy(e -> assertThat(e).contains("Q2 results").contains("results today"));
        UUID tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        assertThat(events.risk(tcs).level()).isEqualTo(EventRiskLevel.MEDIUM);
        assertThat(events.risk(niftyFut).level()).isEqualTo(EventRiskLevel.LOW);
        ResponseEntity<List> forInfy = rest.exchange("/api/v1/events?from=" + DAY + "&to=" + DAY + "&instrumentId=" + infy, HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(forInfy.getBody()).hasSize(1); // TCS's ex-date is not INFY's business

        // audit trail
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.EVENT_ADDED, null, 0, 10)).total()).isGreaterThanOrEqualTo(1);
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.EVENTS_IMPORTED, null, 0, 10)).total()).isGreaterThanOrEqualTo(1);

        // the score adjuster: -8 on INFY with evidence, 0 on the future
        StrategyVersion version = strategies.create("""
                name: it_events_orb
                universe: [NSE:INFY]
                timeframe: 5m
                direction: long
                entry:
                  all:
                    - close > opening_range_high
                stop:
                  type: opening_range_low
                """, null, "admin");
        Adjustment infyAdj = scoring.compute(version.id(), infy).adjustments().stream().filter(a -> a.name().equals("Event risk")).findFirst().orElseThrow();
        assertThat(infyAdj.delta()).isEqualTo(-8);
        assertThat(infyAdj.evidence().get(0)).isEqualTo("Event risk HIGH");
        Adjustment futAdj = scoring.compute(version.id(), niftyFut).adjustments().stream().filter(a -> a.name().equals("Event risk")).findFirst().orElseThrow();
        assertThat(futAdj.delta()).isZero();
    }
}
