package money.hejje.bots;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Timeframe;
import money.hejje.harness.LeaderboardIT;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
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
 * Exit-vote hysteresis (plan M9.5): with {@code exitConfirmVotes=2} a single EXIT vote does not flatten, a vote after a
 * point without one starts again, and two consecutive votes close the position. Session on 2026-09-28.
 */
class ExitConfirmIT extends AbstractSimIT {

    static final LocalDate DAY = LocalDate.of(2026, 9, 28);

    @Autowired BotService bots;
    @Autowired BotHub hub;
    @Autowired BotDecisions decisions;
    @Autowired SimSessionService sessions;
    @Autowired StrategyService strategies;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore history;

    final List<AutoCloseable> connections = new ArrayList<>();
    final List<StrategyDeployment> deployments = new ArrayList<>();
    final ExecutorService remote = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : connections) {
            c.close();
        }
        for (StrategyDeployment d : deployments) {
            strategies.updateDeployment(d.id(), false, "end of test", "admin");
        }
        sessions.active().ifPresent(a -> sessions.control(a.id(), SimSessionService.Action.CANCEL, null));
    }

    @Test
    void anExitNeedsTwoConsecutiveVotes() {
        UUID infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        history.write(infy, Timeframe.M1, LeaderboardIT.day(infy, DAY));
        Bot bot = bots.register(new BotService.Registration("confirm" + System.nanoTime() % 100_000, "1", Bot.Kind.EXTERNAL, null,
                Set.of(ExecutionMode.SIM, ExecutionMode.PAPER), List.of("NSE:INFY"), "5m", null, null, 2, null), "admin");
        assertThat(bot.exitConfirmVotes()).isEqualTo(2);
        deployments.add(strategies.deploy(bot.strategyId(), 1, ExecutionMode.SIM, List.of(), 0, Map.of(), "admin"));
        Map<String, BotDecision.Input> script = Map.of(
                "09:35", LeaderboardIT.act("NSE:INFY", "ENTER_SHORT", "1515.00", "fade"),
                "09:40", LeaderboardIT.act("NSE:INFY", "EXIT", null, "first vote"),
                "09:50", LeaderboardIT.act("NSE:INFY", "EXIT", null, "a vote after a point without one"),
                "09:55", LeaderboardIT.act("NSE:INFY", "EXIT", null, "the second in a row"));
        connections.add(hub.connect(bot.id(), message -> {
            String point = (String) message.get("pointId");
            remote.submit(() -> hub.answer(bot.id(), new BotDecision.Reply(point, List.of(script.getOrDefault(LeaderboardIT.hhmm(point),
                    new BotDecision.Input("NSE:INFY", "HOLD", null, null, null, null, null, null, null))))));
        }));
        SimSession s = sessions.create(new SimSessionSpec(List.of(DAY), null, null, List.of("NSE:INFY"), null, 1_000_000L, 2000L, 50_000L, 5,
                List.of(Map.of("botId", bot.id().toString()))), "tester");
        sessions.control(s.id(), SimSessionService.Action.PLAY, "MAX");
        assertThat(await(s.id()).state()).isEqualTo(SimSession.State.DONE);

        Map<String, BotDecision> byTime = new java.util.HashMap<>();
        decisions.recent(bot.id(), 500).forEach(d -> byTime.put(LeaderboardIT.hhmm(d.pointId()), d));
        assertThat(byTime.get("09:35").outcome()).isEqualTo(BotDecision.Outcome.EXECUTED);
        assertThat(byTime.get("09:40").outcome()).isEqualTo(BotDecision.Outcome.NOTED);
        assertThat(byTime.get("09:40").detail()).startsWith("awaiting_confirmation: exit vote 1 of 2");
        assertThat(byTime.get("09:50").outcome()).isEqualTo(BotDecision.Outcome.NOTED); // 09:45 held: the count starts again
        assertThat(byTime.get("09:50").detail()).contains("vote 1 of 2");
        assertThat(byTime.get("09:55").outcome()).isEqualTo(BotDecision.Outcome.EXITING);
    }

    SimSession await(UUID id) {
        for (int i = 0; i < 1200; i++) {
            SimSession s = sessions.find(id).orElseThrow();
            if (s.state().finished()) {
                return s;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("session did not finish");
    }
}
