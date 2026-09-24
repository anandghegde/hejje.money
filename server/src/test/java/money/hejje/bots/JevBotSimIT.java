package money.hejje.bots;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Timeframe;
import money.hejje.harness.LeaderboardIT;
import money.hejje.harness.SimReport;
import money.hejje.harness.SimReports;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.llm.FixtureJev;
import money.hejje.llm.JevTransport;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.sim.AbstractSimIT;
import money.hejje.sim.SimSession;
import money.hejje.sim.SimSessionService;
import money.hejje.sim.SimSessionSpec;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The in-process Jev bot on the fixture provider (plan M9.5): the same day played twice gives the same decisions and
 * report hashes, the second time from the SIM cache; with Jev down the bot answers NONE / HOLD and no point is skipped.
 * Sessions on 2026-09-29 and 2026-09-30. The outage runs last: its failures open Jev's circuit for a minute of wall time.
 */
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class JevBotSimIT extends AbstractSimIT {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired BotService bots;
    @Autowired BotHub hub;
    @Autowired BotDecisions decisions;
    @Autowired SimSessionService sessions;
    @Autowired StrategyService strategies;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore history;
    @Autowired SimReports reports;
    @Autowired JevTransport transport;

    final List<StrategyDeployment> deployments = new ArrayList<>();
    final List<Bot> registered = new ArrayList<>();

    FixtureJev fixture() {
        return (FixtureJev) transport;
    }

    @AfterEach
    void tearDown() {
        for (StrategyDeployment d : deployments) {
            strategies.updateDeployment(d.id(), false, "end of test", "admin");
        }
        registered.forEach(b -> bots.setEnabled(b.id(), false));
        sessions.active().ifPresent(a -> sessions.control(a.id(), SimSessionService.Action.CANCEL, null));
        fixture().reset();
    }

    void script() {
        FixtureJev fx = fixture();
        fx.noul("long_0", 0.8);  // NSE:INFY
        fx.noul("long_1", 0.2);  // NSE:TCS
        fx.noul("short_0", 0.1);
        fx.noul("short_1", 0.1);
        fx.noul("risk_off", 0.1);
        fx.script("regime", (q, s) -> JSON.createObjectNode().put("type", "choice").put("choice", "range").put("confidence", 0.8)
                .set("probabilities", JSON.createObjectNode().put("range", 0.8).put("chop", 0.2)));
        fx.script("setup", (q, s) -> JSON.createObjectNode().put("type", "choice").put("choice", "long_continuation").put("confidence", 0.7)
                .set("probabilities", JSON.createObjectNode().put("long_continuation", 0.8).put("chop", 0.2)));
        score(fx, "trend_quality", 2);
        score(fx, "index_alignment", 1);
        score(fx, "liquidity", 2);
        score(fx, "thesis", 2);
        fx.noul("exit_now", 0.1);
        fx.noul("take_profit", 0.1);
    }

    static void score(FixtureJev fx, String key, double level) {
        fx.script(key, (q, s) -> JSON.createObjectNode().put("type", "score").put("score", level).put("confidence", 0.8));
    }

    Bot register(String prefix) {
        UUID infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        UUID tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        for (LocalDate d : List.of(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30))) {
            history.write(infy, Timeframe.M1, LeaderboardIT.day(infy, d));
            history.write(tcs, Timeframe.M1, LeaderboardIT.day(tcs, d));
        }
        Bot bot = bots.register(new BotService.Registration(prefix + System.nanoTime() % 100_000, "1", Bot.Kind.JEV, LocalDate.of(2026, 1, 1),
                Set.of(ExecutionMode.SIM, ExecutionMode.PAPER), List.of("NSE:INFY", "NSE:TCS"), "5m", null, null), "admin");
        registered.add(bot);
        deployments.add(strategies.deploy(bot.strategyId(), 1, ExecutionMode.SIM, List.of(), 0, Map.of(), "admin"));
        return bot;
    }

    SimSession play(Bot bot, LocalDate day) {
        SimSession s = sessions.create(new SimSessionSpec(List.of(day), null, null, List.of("NSE:INFY", "NSE:TCS"), null, 1_000_000L, 2000L, 50_000L, 5,
                List.of(Map.of("botId", bot.id().toString()))), "tester");
        sessions.control(s.id(), SimSessionService.Action.PLAY, "MAX");
        SimSession done = await(s.id());
        assertThat(done.state()).as("%s", done.error()).isEqualTo(SimSession.State.DONE);
        for (int i = 0; i < 200 && reports.list(bot.name(), "1", 10).stream().noneMatch(r -> r.sessionId().equals(s.id())); i++) {
            sleep(50);
        }
        return done;
    }

    /**
     * Decisions are recorded once per (bot, point, instrument), and point ids are dated, so a day is replayed with a second
     * bot of the same configuration: identical states come from the SIM cache, and decisions and results hash alike.
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    void theSameDayTwiceGivesTheSameDecisionsAndTheSecondRunIsCached() {
        script();
        LocalDate day = LocalDate.of(2026, 9, 29);
        Bot first = register("jeva");
        assertThat(first.kind()).isEqualTo(Bot.Kind.JEV);
        assertThat(first.exitConfirmVotes()).isEqualTo(2);
        assertThat(first.questionSet()).isEqualTo("bot");
        play(first, day);
        int calls = fixture().calls();
        assertThat(calls).isPositive();
        List<BotDecision> made = decisions.recent(first.id(), 500);
        assertThat(made).anySatisfy(d -> {
            assertThat(d.action()).isEqualTo(BotDecision.Action.ENTER_LONG);
            assertThat(d.instrument()).isEqualTo("NSE:INFY");
            assertThat(d.confidence()).isEqualTo(0.8);
            assertThat(d.stage()).isEqualTo("stage2");
            assertThat(d.outcome()).isEqualTo(BotDecision.Outcome.EXECUTED);
        });
        assertThat(made).noneMatch(d -> d.action() == BotDecision.Action.SKIPPED);
        // the exit rules act through the two-vote hysteresis
        assertThat(made).anySatisfy(d -> assertThat(d.detail()).startsWith("awaiting_confirmation"));
        bots.setEnabled(first.id(), false);
        strategies.updateDeployment(deployments.get(0).id(), false, "first run done", "admin");

        Bot second = register("jevb");
        play(second, day);
        assertThat(fixture().calls()).as("the second run is answered from the SIM cache").isEqualTo(calls);
        SimReport a = reports.list(first.name(), "1", 10).get(0);
        SimReport b = reports.list(second.name(), "1", 10).get(0);
        assertThat(b.decisionsHash()).isEqualTo(a.decisionsHash());
        assertThat(b.resultHash()).isEqualTo(a.resultHash());
        assertThat(b.trades()).isEqualTo(a.trades()).isPositive();
        assertThat(b.confidenceCalibration()).isEqualTo(a.confidenceCalibration());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void withJevDownTheBotAnswersNoneAndHoldAndNothingIsSkipped() {
        Bot bot = register("jevdown");
        fixture().failWith(new IllegalStateException("upstream down"));
        play(bot, LocalDate.of(2026, 9, 30));
        List<BotDecision> all = decisions.recent(bot.id(), 500);
        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(d -> assertThat(d.action()).isIn(BotDecision.Action.NONE, BotDecision.Action.HOLD));
        assertThat(hub.stats(bot.id()).skipped()).isZero();
    }

    SimSession await(UUID id) {
        for (int i = 0; i < 1200; i++) {
            SimSession s = sessions.find(id).orElseThrow();
            if (s.state().finished()) {
                return s;
            }
            sleep(100);
        }
        throw new AssertionError("session did not finish");
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
