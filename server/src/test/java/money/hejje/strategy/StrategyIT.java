package money.hejje.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.common.ExecutionMode;
import money.hejje.instruments.InstrumentService;
import money.hejje.strategy.internal.BundledStrategyLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class StrategyIT extends AbstractIntegrationTest {

    static final String YAML = """
            name: it_orb
            family: index
            universe:
              - "INDEX:NIFTY 50"
              - NSE:INFY
            timeframe: 5m
            direction: long
            entry:
              all:
                - close > opening_range_high
                - close > vwap
            stop:
              type: opening_range_low
            target:
              type: risk_multiple
              value: 2
            trade_window:
              start: "09:30"
              end: "12:00"
            """;

    @Autowired StrategyService strategies;
    @Autowired BundledStrategyLoader loader;
    @Autowired InstrumentService instruments;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE strategy_deployment, strategy_version, strategy CASCADE");
        instruments.sync();
        token = adminAccessToken();
    }

    private ResponseEntity<Map> post(String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), Map.class);
    }

    @Test
    void createVersionAndImmutability() {
        ResponseEntity<Map> created = post("/api/v1/strategies", Map.of("yaml", YAML));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> v1 = created.getBody();
        UUID strategyId = UUID.fromString((String) v1.get("strategyId"));
        assertThat(v1.get("version")).isEqualTo(1);
        assertThat(v1.get("status")).isEqualTo("DRAFT");
        assertThat(v1.get("changeNote")).isEqualTo("initial");
        assertThat(((Map<?, ?>) v1.get("definition")).get("name")).isEqualTo("it_orb");

        // same name again is a conflict
        assertThat(post("/api/v1/strategies", Map.of("yaml", YAML)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // identical definition as a new version is a conflict; a change note is required
        assertThat(post("/api/v1/strategies/" + strategyId + "/versions", Map.of("yaml", YAML, "changeNote", "same")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        String v2yaml = YAML.replace("- close > vwap", "- close > vwap\n    - relative_volume > 1.4");
        assertThat(post("/api/v1/strategies/" + strategyId + "/versions", Map.of("yaml", v2yaml)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> v2 = post("/api/v1/strategies/" + strategyId + "/versions", Map.of("yaml", v2yaml, "changeNote", "Added volume filter"));
        assertThat(v2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(v2.getBody().get("version")).isEqualTo(2);
        assertThat(v2.getBody().get("parentVersionId")).isEqualTo(v1.get("id"));
        assertThat(v2.getBody().get("definitionHash")).isNotEqualTo(v1.get("definitionHash"));

        // whitespace/comment-only changes hash identically
        assertThat(strategies.hash(strategies.parse(YAML + "\n# comment\n"))).isEqualTo(v1.get("definitionHash"));

        // renaming inside a version is rejected
        ResponseEntity<Map> renamed = post("/api/v1/strategies/" + strategyId + "/versions",
                Map.of("yaml", v2yaml.replace("name: it_orb", "name: it_orb_2"), "changeNote", "rename"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((List<?>) renamed.getBody().get("errors")).hasSize(1);

        // listing
        ResponseEntity<List> list = rest.exchange("/api/v1/strategies", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(list.getBody()).hasSize(1);
        Map<?, ?> summary = (Map<?, ?>) list.getBody().get(0);
        assertThat(summary.get("latestVersion")).isEqualTo(2);
        assertThat(summary.get("latestStatus")).isEqualTo("DRAFT");
        assertThat(summary.get("family")).isEqualTo("INDEX");

        // a version row can never be updated (except status) or deleted
        UUID v1Id = UUID.fromString((String) v1.get("id"));
        assertThatThrownBy(() -> jdbc.update("UPDATE strategy_version SET definition_yaml = 'x' WHERE id = ?", v1Id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE strategy_version SET change_note = 'x' WHERE id = ?", v1Id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM strategy_version WHERE id = ?", v1Id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThat(jdbc.update("UPDATE strategy_version SET status = 'RETIRED' WHERE id = ?", v1Id)).isEqualTo(1);

        // audit trail
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.STRATEGY_VERSION_CREATED, null, 0, 50)).content())
                .anyMatch(r -> strategyId.equals(r.strategyId()));
    }

    @Test
    void validateEndpointReportsErrors() {
        ResponseEntity<Map> ok = post("/api/v1/strategies/validate", Map.of("yaml", YAML));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("valid")).isEqualTo(true);
        ResponseEntity<Map> bad = post("/api/v1/strategies/validate", Map.of("yaml", YAML.replace("type: opening_range_low", "type: atr_multiple")));
        assertThat(bad.getBody().get("valid")).isEqualTo(false);
        assertThat((List<Map<?, ?>>) bad.getBody().get("errors")).extracting(e -> (Object) e.get("path")).contains("stop.value");
        ResponseEntity<Map> rejected = post("/api/v1/strategies", Map.of("yaml", "name: x"));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody().get("type")).isEqualTo("https://hejje.money/problems/strategy-validation");
        assertThat((List<?>) rejected.getBody().get("errors")).isNotEmpty();
    }

    @Test
    void lifecycleRejectsDraftToLiveAndNeedsEvidence() {
        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        String base = "/api/v1/strategies/" + v1.strategyId() + "/versions/1/status";
        ResponseEntity<Map> live = post(base, Map.of("status", "LIVE"));
        assertThat(live.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) live.getBody().get("detail")).contains("DRAFT -> LIVE");
        // no backtest evidence yet (M2.3 provides it)
        assertThat(post(base, Map.of("status", "BACKTESTED")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // retire is always allowed
        ResponseEntity<Map> retired = post(base, Map.of("status", "RETIRED", "note", "superseded"));
        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retired.getBody().get("status")).isEqualTo("RETIRED");
        assertThat(strategies.find(v1.strategyId()).orElseThrow().retiredAt()).isNotNull();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.STRATEGY_STATUS_CHANGED, null, 0, 50)).content())
                .anyMatch(r -> v1.strategyId().equals(r.strategyId()) && "RETIRED".equals(r.payload().get("to")));
    }

    @Test
    void cloneCreatesNewStrategyWithNewName() {
        StrategyVersion source = strategies.create(YAML, null, "admin");
        ResponseEntity<Map> cloned = post("/api/v1/strategies/" + source.strategyId() + "/clone", Map.of("name", "it_orb_copy"));
        assertThat(cloned.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        StrategyVersion copy = strategies.versionById(UUID.fromString((String) cloned.getBody().get("id"))).orElseThrow();
        assertThat(copy.strategyId()).isNotEqualTo(source.strategyId());
        assertThat(copy.definition().name()).isEqualTo("it_orb_copy");
        assertThat(copy.definition().entry()).isEqualTo(source.definition().entry());
        assertThat(copy.changeNote()).contains("cloned from it_orb v1");
        assertThat(strategies.list()).hasSize(2);
    }

    @Test
    void deploymentsFollowVersionStatus() {
        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        String deployPath = "/api/v1/strategies/" + v1.strategyId() + "/versions/1/deployments";
        // DRAFT cannot be deployed
        assertThat(post(deployPath, Map.of("mode", "PAPER")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // simulate the lifecycle having reached PAPER (evidence arrives with the backtester)
        jdbc.update("UPDATE strategy_version SET status = 'PAPER' WHERE id = ?", v1.id());
        // CONFIRM deployments need LIVE
        assertThat(post(deployPath, Map.of("mode", "CONFIRM")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> deployed = post(deployPath, Map.of("mode", "PAPER", "autonomyLevel", 1, "params", Map.of("risk_rupees", 1500)));
        assertThat(deployed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID deploymentId = UUID.fromString((String) deployed.getBody().get("id"));
        // universe resolved from the definition: NIFTY 50 index + INFY
        assertThat((List<?>) deployed.getBody().get("instrumentIds")).hasSize(2);
        assertThat(deployed.getBody().get("enabled")).isEqualTo(true);
        assertThat(((Map<?, ?>) deployed.getBody().get("params")).get("risk_rupees")).isEqualTo(1500);

        // explicit instruments
        ResponseEntity<Map> explicit = post(deployPath, Map.of("mode", "PAPER", "instruments", List.of("NSE:INFY")));
        assertThat((List<?>) explicit.getBody().get("instrumentIds")).hasSize(1);
        assertThat(post(deployPath, Map.of("mode", "PAPER", "instruments", List.of("NSE:NOPE"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // pause / enable
        ResponseEntity<Map> paused = rest.exchange("/api/v1/deployments/" + deploymentId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false, "reason", "lunch"), bearer(token)), Map.class);
        assertThat(paused.getBody().get("enabled")).isEqualTo(false);
        assertThat(paused.getBody().get("pauseReason")).isEqualTo("lunch");
        assertThat(paused.getBody().get("pausedAt")).isNotNull();
        ResponseEntity<List> enabledOnly = rest.exchange("/api/v1/deployments?enabled=true", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(enabledOnly.getBody()).hasSize(1);
        ResponseEntity<Map> reenabled = rest.exchange("/api/v1/deployments/" + deploymentId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", true), bearer(token)), Map.class);
        assertThat(reenabled.getBody().get("enabled")).isEqualTo(true);
        assertThat(reenabled.getBody().get("pausedAt")).isNull();

        // pausing the version pauses every enabled deployment
        strategies.changeStatus(v1.strategyId(), 1, VersionStatus.PAUSED, "drift", "admin");
        assertThat(strategies.deployments(v1.id(), ExecutionMode.PAPER, true)).isEmpty();
        assertThat(strategies.deployments(v1.id(), null, null)).hasSize(2).allMatch(d -> "version PAUSED".equals(d.pauseReason()));
        // and cannot be re-enabled while paused
        assertThat(rest.exchange("/api/v1/deployments/" + deploymentId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", true), bearer(token)), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.STRATEGY_PAUSED, null, 0, 50)).content())
                .anyMatch(r -> v1.strategyId().equals(r.strategyId()));
    }

    @Test
    void bundledLoaderCreatesAndVersions(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("it_orb.yaml"), YAML);
        Files.writeString(dir.resolve("broken.yaml"), "name: broken\nentry: nope\n");
        List<StrategyVersion> first = loader.loadDirectory(dir);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).changeNote()).isEqualTo("bundled");
        assertThat(first.get(0).createdBy()).isEqualTo("system");
        // unchanged file: nothing new
        assertThat(loader.loadDirectory(dir)).isEmpty();
        // formatting change only: still nothing new
        Files.writeString(dir.resolve("it_orb.yaml"), YAML + "\n# tweak\n");
        assertThat(loader.loadDirectory(dir)).isEmpty();
        // material change: new version
        Files.writeString(dir.resolve("it_orb.yaml"), YAML.replace("value: 2", "value: 3"));
        List<StrategyVersion> second = loader.loadDirectory(dir);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).version()).isEqualTo(2);
        assertThat(strategies.versions(second.get(0).strategyId())).hasSize(2);
    }

    @Test
    void readScopeCannotWrite() {
        ResponseEntity<Map> client = rest.exchange("/api/v1/auth/clients", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "reader", "scopes", List.of("strategies:read")), bearer(token)), Map.class);
        String apiKey = (String) client.getBody().get("key");
        ResponseEntity<Map> denied = rest.exchange("/api/v1/strategies", HttpMethod.POST, new HttpEntity<>(Map.of("yaml", YAML), bearer(apiKey)), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<List> allowed = rest.exchange("/api/v1/strategies", HttpMethod.GET, new HttpEntity<>(bearer(apiKey)), List.class);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
