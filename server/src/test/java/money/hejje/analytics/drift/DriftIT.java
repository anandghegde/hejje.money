package money.hejje.analytics.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.analytics.TradeReview;
import money.hejje.analytics.internal.ReviewStore;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditRecord;
import money.hejje.audit.AuditService;
import money.hejje.backtest.experiments.ExperimentITYaml;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Side;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoringService;
import money.hejje.signals.SignalEngine;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drift actions end to end (plan M5.1) on synthetic reviewed trades against a stored backtest whose out-of-sample slice
 * has a 60 % win rate, +0.30R expectancy and a 4R drawdown.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class DriftIT extends AbstractIntegrationTest {

    @Autowired StrategyService strategies;
    @Autowired InstrumentService instruments;
    @Autowired DriftService drift;
    @Autowired ReviewStore reviews;
    @Autowired ScoringService scoring;
    @Autowired SignalEngine engine;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    UUID tcs;
    StrategyVersion version;
    StrategyDeployment deployment;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE drift_assessment, drift_state, trade_review, strategy_position, signal, strategy_score, backtest_trade, backtest, "
                + "strategy_deployment, strategy_version, strategy CASCADE");
        clock.setIst("2026-11-20T16:00:00");
        instruments.sync();
        tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        version = strategies.create(ExperimentITYaml.yaml("drift_orb"), "drift test", "test");
        strategies.changeStatus(version.strategyId(), 1, VersionStatus.PAPER, "test", "test", true);
        deployment = strategies.deploy(version.strategyId(), 1, ExecutionMode.PAPER, List.of("NSE:TCS"), 0, Map.of("risk_rupees", 2000), "test");
        String spec = "{\"versionId\":\"" + version.id() + "\",\"from\":\"2025-01-01\",\"to\":\"2025-12-31\",\"splits\":{\"type\":\"FIXED\"}}";
        jdbc.update("""
                INSERT INTO backtest (id, version_id, spec, status, progress_pct, created_at, finished_at, metrics, by_split, windows, warnings, engine, created_by)
                VALUES (?, ?, CAST(? AS jsonb), 'DONE', 100, now(), now(), CAST(? AS jsonb), CAST(? AS jsonb), '[]'::jsonb, '[]'::jsonb, 'JAVA', 'test')
                """, UUID.randomUUID(), version.id(), spec, metrics(200, 0.55, 0.25, 1.6, 5.0), "{\"OUT_OF_SAMPLE\":" + metrics(60, 0.60, 0.30, 1.8, 4.0) + "}");
    }

    @AfterEach
    void tearDown() {
        clock.set(Instant.now());
    }

    static String metrics(int trades, double winRate, double expectancyR, double pf, double ddR) {
        return String.format(Locale.ROOT, "{\"totalTrades\":%d,\"winRate\":%s,\"expectancyR\":%s,\"profitFactor\":%s,\"maxDrawdownR\":%s,\"monthly\":{}}", trades, winRate,
                expectancyR, pf, ddR);
    }

    /** Closed strategy trades of {@code d} two hours apart up to now, each with a review (10 shares, ₹1 risk per share: ₹10 per R). */
    void trades(StrategyDeployment d, List<Double> rs) {
        Instant now = clock.instant();
        String mode = d.mode().name();
        for (int i = 0; i < rs.size(); i++) {
            Instant closed = now.minus(Duration.ofHours(2L * (rs.size() - i)));
            Instant opened = closed.minus(Duration.ofMinutes(30));
            UUID signalId = UUID.randomUUID();
            UUID positionId = UUID.randomUUID();
            UUID entryOrder = UUID.randomUUID();
            double r = rs.get(i);
            BigDecimal exit = BigDecimal.valueOf(100 + r).setScale(2, RoundingMode.HALF_UP);
            String reason = r > 0 ? "TARGET" : "STOP";
            jdbc.update("""
                    INSERT INTO signal (id, version_id, strategy_id, deployment_id, instrument_id, mode, side, reference_price, stop, target, risk_per_unit,
                        bar_time, valid_until, evidence, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'BUY', 100, 99, 102, 1, ?, ?, '[]'::jsonb, 'EXECUTED', ?, ?)
                    """, signalId, d.versionId(), d.strategyId(), d.id(), tcs, mode, ts(opened), ts(opened), ts(opened), ts(opened));
            jdbc.update("""
                    INSERT INTO strategy_position (id, signal_id, deployment_id, version_id, strategy_id, instrument_id, mode, side, quantity, entry_price,
                        initial_stop, stop, target, entry_order_id, status, close_reason, exit_price, opened_at, closed_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'BUY', 10, 100, 99, 99, 102, ?, 'CLOSED', ?, ?, ?, ?, ?)
                    """, positionId, signalId, d.id(), d.versionId(), d.strategyId(), tcs, mode, entryOrder, reason, exit, ts(opened), ts(closed), ts(closed));
            Money net = Money.ofPaise(Math.round(r * 1000));
            reviews.insert(new TradeReview(UUID.randomUUID(), d.mode(), null, positionId, d.strategyId(), d.versionId(), signalId, tcs, entryOrder, Side.BUY, 10,
                    new BigDecimal("100.00"), exit, opened, closed, net, Money.ZERO, net, r, true, 0.0, 0.0, 100, reason, Map.of(), "strategy trade", closed));
        }
    }

    static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    List<AuditRecord> auditOf(AuditEventType type) {
        return audit.query(new AuditQuery(null, null, type, null, 0, 500)).content().stream().filter(a -> version.strategyId().equals(a.strategyId())).toList();
    }

    Adjustment driftAdjustment() {
        return scoring.adjustments(version, tcs).stream().filter(a -> a.name().equals("Live-vs-backtest drift")).findFirst().orElseThrow();
    }

    StrategyDeployment reload(StrategyDeployment d) {
        return strategies.deployment(d.id()).orElseThrow();
    }

    int assessments(StrategyDeployment d) {
        return jdbc.queryForObject("SELECT count(*) FROM drift_assessment WHERE deployment_id = ?", Integer.class, d.id());
    }

    @Test
    void degradingReducesSizeOnceLowersTheScoreAndSizingFollows() {
        trades(deployment, DriftMathTest.stream(11, 1.8, 19, -0.5));
        DriftReport r = drift.evaluate(deployment.id());
        assertThat(r.status()).isEqualTo(DriftStatus.DEGRADING);
        assertThat(r.live().trades()).isEqualTo(30);
        assertThat(r.backtest().split()).isEqualTo("OUT_OF_SAMPLE");
        assertThat(r.backtest().winRate()).isEqualTo(0.60);
        StrategyDeployment reduced = reload(deployment);
        assertThat(reduced.enabled()).isTrue();
        assertThat(reduced.sizeMultiplier()).isEqualByComparingTo("0.50");
        assertThat(engine.riskPerTrade(reduced, version)).isEqualTo(Money.of("1000.00"));

        drift.evaluate(deployment.id());
        drift.evaluate(deployment.id());
        assertThat(assessments(deployment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actions::text FROM drift_assessment WHERE deployment_id = ?", String.class, deployment.id()))
                .contains("ALERT", "LOWER_SCORE -10", "REDUCE_SIZE 1.00 -> 0.50").doesNotContain("failed");
        assertThat(auditOf(AuditEventType.STRATEGY_DRIFT_CHANGED)).singleElement().satisfies(a -> {
            assertThat(a.actorType()).isEqualTo(ActorType.SYSTEM);
            assertThat(a.payload()).containsEntry("status", "DEGRADING").containsEntry("from", "NONE");
        });
        assertThat(auditOf(AuditEventType.STRATEGY_DEPLOYMENT_UPDATED)).filteredOn(a -> "0.50".equals(a.payload().get("sizeMultiplier"))).hasSize(1);
        Adjustment adjustment = driftAdjustment();
        assertThat(adjustment.delta()).isEqualTo(-10);
        assertThat(adjustment.evidence()).anyMatch(e -> e.contains("unlikely by chance"));
    }

    @Test
    void failedPausesOnceWithTheStatistics() {
        trades(deployment, DriftMathTest.stream(9, 1.0, 21, -1.0));
        assertThat(drift.evaluate(deployment.id()).status()).isEqualTo(DriftStatus.FAILED);
        StrategyDeployment paused = reload(deployment);
        assertThat(paused.enabled()).isFalse();
        assertThat(paused.pauseReason()).startsWith("drift FAILED: ");
        List<AuditRecord> pauses = auditOf(AuditEventType.STRATEGY_PAUSED);
        assertThat(pauses).singleElement().satisfies(a -> {
            assertThat(a.actorType()).isEqualTo(ActorType.SYSTEM);
            assertThat(a.payload()).containsEntry("trades", 30).containsEntry("status", "FAILED")
                    .containsKeys("winRate", "backtestWinRate", "winRatePValue", "expectancyInterval", "backtestExpectancyR", "triggered", "reason");
        });

        drift.evaluate(deployment.id());
        // re-enabled by hand without an override: FAILED was already acted on, so the pause does not fire again
        strategies.updateDeployment(deployment.id(), true, null, "test");
        drift.evaluate(deployment.id());
        drift.evaluate(deployment.id());
        assertThat(auditOf(AuditEventType.STRATEGY_PAUSED)).hasSize(1);
        assertThat(reload(deployment).enabled()).isTrue();
        assertThat(driftAdjustment().delta()).isEqualTo(-15);
    }

    @Test
    void overrideSuppressesActionsRestoresSizeAndAWorseStatusActsAgain() {
        trades(deployment, DriftMathTest.stream(11, 1.8, 19, -0.5));
        drift.evaluate(deployment.id());
        assertThat(reload(deployment).sizeMultiplier()).isEqualByComparingTo("0.50");
        String token = adminAccessToken();
        String url = "/api/v1/deployments/" + deployment.id() + "/drift/override";
        ResponseEntity<Map> blank = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(Map.of("reason", " "), bearer(token)), Map.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> ok = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(Map.of("reason", "reviewed: regime shift, keep full size"), bearer(token)), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("overrideStatus", "DEGRADING").containsEntry("overrideBy", "admin");
        assertThat(reload(deployment).sizeMultiplier()).isEqualByComparingTo("1.00");
        assertThat(auditOf(AuditEventType.STRATEGY_DRIFT_OVERRIDDEN)).hasSize(1);
        assertThat(driftAdjustment().delta()).isZero();
        assertThat(driftAdjustment().evidence()).singleElement().asString().contains("overridden by admin");

        drift.evaluate(deployment.id());
        assertThat(reload(deployment).sizeMultiplier()).isEqualByComparingTo("1.00");

        // a losing run on top turns it FAILED: worse than what was overridden, so the actions run and the override ends
        clock.setIst("2026-11-23T16:00:00");
        trades(deployment, DriftMathTest.stream(9, 1.0, 21, -1.0));
        assertThat(drift.evaluate(deployment.id()).status()).isEqualTo(DriftStatus.FAILED);
        assertThat(reload(deployment).enabled()).isFalse();
        assertThat(drift.state(deployment.id()).orElseThrow().overrideStatus()).isNull();
    }

    @Test
    void fewTradesAreInsufficientAndTheViewShowsBothSides() {
        trades(deployment, List.of(1.0, -1.0, 1.0, 1.0, -1.0));
        assertThat(drift.evaluate(deployment.id()).status()).isEqualTo(DriftStatus.INSUFFICIENT_DATA);
        assertThat(reload(deployment).enabled()).isTrue();
        assertThat(driftAdjustment().delta()).isZero();

        String token = adminAccessToken();
        ResponseEntity<Map> view = rest.exchange("/api/v1/strategies/" + version.strategyId() + "/drift", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map dep = (Map) ((List) view.getBody().get("deployments")).get(0);
        Map report = (Map) dep.get("report");
        assertThat(report.get("status")).isEqualTo("INSUFFICIENT_DATA");
        assertThat(((Map) report.get("live")).get("trades")).isEqualTo(5);
        assertThat(((Map) report.get("backtest")).get("split")).isEqualTo("OUT_OF_SAMPLE");
        assertThat(((Map) report.get("window")).get("sessions")).isEqualTo(60);
        assertThat((List<String>) report.get("evidence")).anyMatch(e -> e.contains("assessed from 10"));
        assertThat(((Map) dep.get("state")).get("status")).isEqualTo("INSUFFICIENT_DATA");
        assertThat((List) dep.get("history")).hasSize(1);

        ResponseEntity<Map> nothing = rest.exchange("/api/v1/deployments/" + deployment.id() + "/drift/override", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "why not"), bearer(token)), Map.class);
        assertThat(nothing.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rest.exchange("/api/v1/strategies/" + UUID.randomUUID() + "/drift", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void moveToPaperPausesALiveDeploymentAndRedeploysItInPaper() {
        strategies.changeStatus(version.strategyId(), 1, VersionStatus.LIVE, "test", "test", true);
        StrategyDeployment live = strategies.deploy(version.strategyId(), 1, ExecutionMode.CONFIRM, List.of("NSE:TCS"), 0, Map.of("risk_rupees", 2000), "test");
        trades(live, DriftMathTest.stream(9, 1.0, 21, -1.0));
        DriftReport r = drift.evaluate(live.id(), s -> s == DriftStatus.FAILED ? List.of(DriftAction.MOVE_TO_PAPER) : List.of());
        assertThat(r.status()).isEqualTo(DriftStatus.FAILED);
        assertThat(r.mode()).isEqualTo("CONFIRM");
        assertThat(reload(live).enabled()).isFalse();
        assertThat(strategies.deployments(version.id(), ExecutionMode.PAPER, true)).filteredOn(d -> !d.id().equals(deployment.id())).singleElement()
                .satisfies(p -> {
                    assertThat(p.instrumentIds()).containsExactly(tcs);
                    assertThat(p.autonomyLevel()).isZero();
                    assertThat(p.params()).containsEntry("risk_rupees", 2000);
                });
        assertThat(auditOf(AuditEventType.STRATEGY_PAUSED)).singleElement().satisfies(a -> assertThat(a.payload()).containsEntry("mode", "CONFIRM"));
        // the PAPER deployment's own trades are separate (none yet), and MOVE_TO_PAPER on a PAPER deployment is skipped
        assertThat(drift.report(reload(deployment)).live().trades()).isZero();
        trades(deployment, DriftMathTest.stream(9, 1.0, 21, -1.0));
        drift.evaluate(deployment.id(), s -> List.of(DriftAction.MOVE_TO_PAPER));
        assertThat(reload(deployment).enabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT actions::text FROM drift_assessment WHERE deployment_id = ?", String.class, deployment.id()))
                .contains("MOVE_TO_PAPER skipped: already PAPER");
    }

    @Test
    void autonomyFivePausesItselfOnceDriftIsDegrading() {
        StrategyDeployment five = strategies.deploy(version.strategyId(), 1, ExecutionMode.PAPER, List.of("NSE:INFY"), 5, Map.of("risk_rupees", 2000), "test");
        trades(five, DriftMathTest.stream(11, 1.8, 19, -0.5));
        assertThat(drift.evaluate(five.id()).status()).isEqualTo(DriftStatus.DEGRADING);
        assertThat(reload(five).enabled()).isFalse();
        assertThat(reload(five).sizeMultiplier()).isEqualByComparingTo("0.50");
        assertThat(jdbc.queryForObject("SELECT actions::text FROM drift_assessment WHERE deployment_id = ?", String.class, five.id())).contains("REDUCE_SIZE", "PAUSE");
        assertThat(reload(deployment).enabled()).isTrue(); // the autonomy-0 deployment is untouched
    }
}
