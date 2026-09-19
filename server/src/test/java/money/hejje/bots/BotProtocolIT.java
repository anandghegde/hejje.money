package money.hejje.bots;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.orders.OrderService;
import money.hejje.orders.Trade;
import money.hejje.risk.KillSwitchAction;
import money.hejje.risk.RiskService;
import money.hejje.signals.SignalService;
import money.hejje.sim.AbstractSimIT;
import money.hejje.sim.SimReplayIT;
import money.hejje.sim.SimSession;
import money.hejje.sim.SimSessionService;
import money.hejje.sim.SimSessionSpec;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The bot protocol in SIM (plan M7.3) on the SimReplayIT fixture day of NSE:INFY: a bot connected in-process answers the
 * 5-minute decision points from another thread (as a remote bot would) while the replay waits (lockstep).
 */
class BotProtocolIT extends AbstractSimIT {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired BotService bots;
    @Autowired BotHub hub;
    @Autowired BotDecisions decisions;
    @Autowired SimSessionService sessions;
    @Autowired StrategyService strategies;
    @Autowired SignalService signals;
    @Autowired OrderService orders;
    @Autowired RiskService risk;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore history;
    @Autowired money.hejje.harness.HarnessService harness;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    UUID infy;
    AutoCloseable connection;
    StrategyDeployment deployment;
    final ExecutorService remote = Executors.newSingleThreadExecutor();

    @BeforeEach
    void seed() {
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        history.write(infy, Timeframe.M1, SimReplayIT.fixtureDay(infy));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
        if (deployment != null) {
            strategies.updateDeployment(deployment.id(), false, "end of test", "admin");
        }
        sessions.active().ifPresent(a -> sessions.control(a.id(), SimSessionService.Action.CANCEL, null));
        if (risk.killSwitch(ExecutionMode.SIM).stopNewOrders()) {
            risk.rearm(ExecutionMode.SIM, "test");
        }
    }

    static String suffix() {
        return Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000);
    }

    static String at(int hour, int minute) {
        return SimReplayIT.DAY.atTime(hour, minute).atZone(IST).toInstant().toString();
    }

    static BotDecision.Input input(String action, String stop, String thesis) {
        return new BotDecision.Input("NSE:INFY", action, stop == null ? null : new BigDecimal(stop), null, 0.7, thesis, "test", Map.of("orb", 1.0), List.of());
    }

    SimSession play(UUID botId) {
        SimSession s = sessions.create(new SimSessionSpec(List.of(SimReplayIT.DAY), null, null, List.of("NSE:INFY"), null, 1_000_000L, 2000L, 50_000L, 5,
                List.of(Map.of("botId", botId.toString()))), "tester");
        sessions.control(s.id(), SimSessionService.Action.PLAY, "MAX");
        for (int i = 0; i < 1200; i++) {
            SimSession now = sessions.find(s.id()).orElseThrow();
            if (now.state().finished()) {
                return now;
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

    List<Trade> fills() {
        return orders.trades(ExecutionMode.SIM, SimReplayIT.DAY.atStartOfDay(IST).toInstant(), SimReplayIT.DAY.plusDays(1).atStartOfDay(IST).toInstant())
                .stream().sorted(Comparator.comparing(Trade::ts)).toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBotEntersMovesItsStopAndExitsThroughTheRiskPipelineInLockstep() {
        Bot bot = bots.register(new BotService.Registration("proto" + suffix(), "1.0", Bot.Kind.EXTERNAL, null, Set.of(ExecutionMode.SIM), List.of("NSE:INFY"),
                "5m", null, null), "admin");
        deployment = strategies.deploy(bot.strategyId(), 1, ExecutionMode.SIM, List.of(), 0, Map.of(), "admin");
        Map<String, List<BotDecision>> answered = new ConcurrentHashMap<>();
        Map<String, Function<String, BotDecision.Reply>> script = Map.of(
                at(9, 20), p -> new BotDecision.Reply(p, List.of(input("ENTER_LONG", null, "no stop"))),
                at(9, 25), p -> new BotDecision.Reply(p, List.of(new BotDecision.Input("NSE:INFY", "ENTER_LONG", new BigDecimal("1490.00"), null, 0.7,
                        "opening range holds", "breakout", Map.of("orb", 1.0), List.of(Map.of("instrument", "NSE:INFY", "side", "long", "score", 0.82),
                                Map.of("instrument", "NSE:TCS", "side", "long", "score", 0.41)))), new BotDecision.Usage(1200L, 180L, new BigDecimal("0.35"))),
                at(9, 30), p -> new BotDecision.Reply(p, List.of(input("MOVE_STOP", "1497.00", "lock in"))),
                at(9, 35), p -> new BotDecision.Reply(p, List.of(input("EXIT", null, "momentum fading"))),
                at(9, 45), p -> {
                    risk.activate(ExecutionMode.SIM, KillSwitchAction.STOP_NEW_ORDERS, null, "test");
                    return new BotDecision.Reply(p, List.of(input("ENTER_LONG", "1490.00", "after the kill switch")));
                });
        connection = hub.connect(bot.id(), message -> {
            String pointId = (String) message.get("pointId");
            if (pointId.equals(at(9, 40))) {
                return; // never answered: SKIPPED after the decision timeout
            }
            remote.submit(() -> {
                BotDecision.Reply reply = script.getOrDefault(pointId, p -> new BotDecision.Reply(p, List.of(input("NONE", null, null)))).apply(pointId);
                answered.put(pointId, hub.answer(bot.id(), reply));
            });
        });

        SimSession done = play(bot.id());
        assertThat(done.state()).as("%s", done.error()).isEqualTo(SimSession.State.DONE);

        assertThat(answered.get(at(9, 20))).singleElement().satisfies(d -> {
            assertThat(d.outcome()).isEqualTo(BotDecision.Outcome.REFUSED);
            assertThat(d.detail()).contains("needs a stop");
        });
        BotDecision entry = answered.get(at(9, 25)).get(0);
        assertThat(entry.outcome()).as(entry.detail()).isEqualTo(BotDecision.Outcome.EXECUTED);
        assertThat(entry.signalId()).isNotNull();
        assertThat(answered.get(at(9, 30)).get(0).outcome()).as(answered.get(at(9, 30)).get(0).detail()).isEqualTo(BotDecision.Outcome.MOVED);
        assertThat(answered.get(at(9, 35)).get(0).outcome()).isEqualTo(BotDecision.Outcome.EXITING);
        BotDecision killed = answered.get(at(9, 45)).get(0);
        assertThat(killed.outcome()).isEqualTo(BotDecision.Outcome.REFUSED);
        assertThat(killed.detail()).contains("kill switch");

        // the entry filled on the next tick (09:25 open 1500.00 + 5 bps), the exit on the tick after the 09:35 point (1502.00 − 5 bps)
        List<Trade> fills = fills().stream().filter(t -> bot.strategyId().equals(t.strategyId()) || t.side() == Side.SELL).toList();
        assertThat(fills).extracting(Trade::side).containsExactly(Side.BUY, Side.SELL);
        assertThat(fills.get(0).price()).isEqualByComparingTo("1500.75");
        assertThat(fills.get(0).ts().atZone(IST).toLocalTime()).isEqualTo(LocalTime.of(9, 25));
        assertThat(fills.get(1).price()).isEqualByComparingTo("1501.25");
        assertThat(fills.get(1).ts().atZone(IST).toLocalTime()).isEqualTo(LocalTime.of(9, 35));

        // attribution: the signal carries the decision and its thesis; the decision links back to the signal
        var signal = signals.find(entry.signalId()).orElseThrow();
        assertThat(signal.evidence().get(0)).containsEntry("decisionId", entry.id().toString()).containsEntry("thesis", "opening range holds");
        assertThat(decisions.bySignal(signal.id())).get().extracting(BotDecision::id).isEqualTo(entry.id());

        // the unanswered point was skipped; latency is tracked
        assertThat(decisions.recent(bot.id(), 200)).anyMatch(d -> d.pointId().equals(at(9, 40)) && d.outcome() == BotDecision.Outcome.SKIPPED);
        BotHub.Stats stats = hub.stats(bot.id());
        assertThat(stats.skipped()).isEqualTo(1);
        assertThat(stats.points()).isGreaterThan(60); // 09:20 .. 15:30 every five minutes
        assertThat(stats.latencyP50Ms()).isNotNull();
        assertThat(stats.latencyP90Ms()).isGreaterThanOrEqualTo(stats.latencyP50Ms());

        // the harness snapshot (plan M7.4) of the finished session; also the TUI's fixture (build/harness-snapshot.json)
        Map<String, Object> snap = harness.snapshot(bot.id(), done.id());
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(new java.io.File("build/harness-snapshot.json"), snap);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(snap).containsEntry("mode", "SIM");
        assertThat((Map<String, Object>) snap.get("session")).containsEntry("state", "DONE").containsEntry("step", 375);
        assertThat((Map<String, Object>) snap.get("header")).containsEntry("bot", bot.name() + " v1.0").containsEntry("fillSource", "replay · paper fills")
                .containsEntry("skipped", 1);
        Map<String, Object> tiles = (Map<String, Object>) snap.get("tiles");
        assertThat(tiles).containsEntry("trades", 1).containsEntry("llmTokens", 1380L);
        assertThat((BigDecimal) tiles.get("capital")).isEqualByComparingTo("1000000");
        assertThat((BigDecimal) tiles.get("frictionPaid")).isPositive();
        assertThat((BigDecimal) tiles.get("llmCostRupees")).isEqualByComparingTo("0.35");
        assertThat((List<Map<String, Object>>) snap.get("trades")).singleElement().satisfies(t -> {
            assertThat(t).containsEntry("symbol", "NSE:INFY").containsEntry("why", "opening range holds").containsEntry("exitReason", "MANUAL");
            assertThat((String) t.get("attribution")).startsWith(entry.id().toString().substring(0, 8));
        });
        assertThat((List<Map<String, Object>>) snap.get("positions")).isEmpty();
        assertThat((List<Map<String, Object>>) snap.get("equity")).hasSizeGreaterThanOrEqualTo(3);
        assertThat((List<Map<String, Object>>) snap.get("decisions")).isNotEmpty();
        assertThat((List<String>) snap.get("log")).anyMatch(l -> l.contains("ENTER_LONG NSE:INFY → EXECUTED"));

        // idempotent per decision point: the same answer again returns the recorded outcome and creates nothing
        List<BotDecision> again = hub.answer(bot.id(), new BotDecision.Reply(at(9, 25), List.of(input("ENTER_LONG", "1490.00", "opening range holds"))));
        assertThat(again).singleElement().extracting(BotDecision::id).isEqualTo(entry.id());
        assertThat(fills().stream().filter(t -> t.side() == Side.BUY).count()).isEqualTo(1);

        // how an entry executes per mode: SIM and PAPER at once, CONFIRM as an approval, AUTO through the AUTO policy
        assertThat(BotDecisions.route(ExecutionMode.SIM)).isEqualTo(BotDecisions.Route.EXECUTE);
        assertThat(BotDecisions.route(ExecutionMode.PAPER)).isEqualTo(BotDecisions.Route.EXECUTE);
        assertThat(BotDecisions.route(ExecutionMode.CONFIRM)).isEqualTo(BotDecisions.Route.APPROVAL);
        assertThat(BotDecisions.route(ExecutionMode.AUTO)).isEqualTo(BotDecisions.Route.AUTO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void capitalChangesOnlyBeforeTheFirstStep() {
        SimSession s = sessions.create(new SimSessionSpec(List.of(SimReplayIT.DAY), null, null, List.of("NSE:INFY"), null, 1_000_000L, null, null, null,
                List.of()), "tester");
        SimSession changed = sessions.control(s.id(), null, null, 250_000L);
        assertThat(changed.spec().capitalRupees()).isEqualTo(250_000L);
        assertThat((BigDecimal) ((Map<String, Object>) harness.snapshot(null, s.id()).get("tiles")).get("capital")).isEqualByComparingTo("250000");
        sessions.control(s.id(), SimSessionService.Action.STEP, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessions.control(s.id(), null, null, 500_000L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("before the session's first step");
    }

    @Test
    void aStrategyRunsAsABotAndItsSignalsExecuteInTheSession() {
        String name = "sim_strategy_bot_" + suffix();
        StrategyVersion v = strategies.create("""
                name: %s
                universe: [NSE:INFY]
                timeframe: 5m
                direction: long
                entry:
                  all:
                    - session_minutes >= 15
                stop:
                  type: percent
                  value: 2
                trade_window:
                  start: "09:30"
                  end: "09:35"
                force_exit_time: "15:10"
                max_trades_per_day: 1
                """.formatted(name), "strategy bot", "admin");
        strategies.changeStatus(v.strategyId(), 1, VersionStatus.PAPER, "strategy bot", "admin", true);
        deployment = strategies.deploy(v.strategyId(), 1, ExecutionMode.SIM, List.of(), 0, Map.of(), "admin");
        Bot bot = bots.register(new BotService.Registration(name.replace("sim_strategy_bot_", "sb"), "1", Bot.Kind.STRATEGY, null, Set.of(ExecutionMode.SIM),
                null, null, null, v.strategyId()), "admin");

        SimSession done = play(bot.id());
        assertThat(done.state()).isEqualTo(SimSession.State.DONE);
        List<BotDecision> made = decisions.recent(bot.id(), 20);
        assertThat(made).singleElement().satisfies(d -> {
            assertThat(d.action()).isEqualTo(BotDecision.Action.ENTER_LONG);
            assertThat(d.outcome()).as(d.detail()).isEqualTo(BotDecision.Outcome.EXECUTED);
            assertThat(d.thesis()).contains("session_minutes >= 15");
        });
        List<Trade> strategyFills = fills().stream().filter(t -> v.strategyId().equals(t.strategyId())).toList();
        assertThat(strategyFills).extracting(Trade::side).containsExactly(Side.BUY, Side.SELL); // entered without a confirmation, forced out at 15:10
    }
}
