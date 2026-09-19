package money.hejje.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import money.hejje.bots.Bot;
import money.hejje.bots.BotDecision;
import money.hejje.bots.BotDecisions;
import money.hejje.bots.BotHub;
import money.hejje.bots.BotService;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Timeframe;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.sim.AbstractSimIT;
import money.hejje.sim.SimSession;
import money.hejje.sim.SimSessionService;
import money.hejje.sim.SimSessionSpec;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Session reports, the leaderboard and promotion (plan M7.5): two fixture bots on the same three sessions. {@code winner}
 * shorts NSE:INFY into the 09:35 drop and covers at 09:40; {@code loser} buys NSE:TCS into the same drop and is stopped out.
 */
class LeaderboardIT extends AbstractSimIT {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final List<LocalDate> DAYS = List.of(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 23));

    @Autowired BotService bots;
    @Autowired BotHub hub;
    @Autowired BotDecisions decisions;
    @Autowired SimSessionService sessions;
    @Autowired StrategyService strategies;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore history;
    @Autowired SimReports reports;

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

    /** The SimReplayIT day shape on any date: flat-ish to 09:34, a drop from 1502 through 1490 in the 09:35 bar, lower after. */
    static List<Candle> day(UUID id, LocalDate date) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < SimSession.STEPS_PER_DAY; i++) {
            BigDecimal open;
            BigDecimal high;
            BigDecimal low;
            BigDecimal close;
            if (i == 20) {
                open = new BigDecimal("1502.00");
                high = new BigDecimal("1502.50");
                low = new BigDecimal("1490.00");
                close = new BigDecimal("1492.00");
            } else {
                open = BigDecimal.valueOf(i < 20 ? 1500 : 1492).add(BigDecimal.valueOf(i % 10).multiply(new BigDecimal("0.5")));
                close = open.add(new BigDecimal("0.25"));
                high = close.add(new BigDecimal("0.5"));
                low = open.subtract(new BigDecimal("0.5"));
            }
            out.add(new Candle(id, Timeframe.M1, date.atTime(LocalTime.of(9, 15).plusMinutes(i)).atZone(IST).toInstant(), open.setScale(2), high.setScale(2),
                    low.setScale(2), close.setScale(2), 4000, 0, false));
        }
        return out;
    }

    static String hhmm(String pointId) {
        return java.time.Instant.parse(pointId).atZone(IST).toLocalTime().toString();
    }

    Bot bot(String name, String symbol, Map<String, BotDecision.Input> script) {
        Bot bot = bots.register(new BotService.Registration(name, "1", Bot.Kind.EXTERNAL, null, Set.of(ExecutionMode.SIM, ExecutionMode.PAPER),
                List.of(symbol), "5m", null, null), "admin");
        deployments.add(strategies.deploy(bot.strategyId(), 1, ExecutionMode.SIM, List.of(), 0, Map.of(), "admin"));
        connections.add(hub.connect(bot.id(), message -> {
            String point = (String) message.get("pointId");
            remote.submit(() -> hub.answer(bot.id(), new BotDecision.Reply(point,
                    List.of(script.getOrDefault(hhmm(point), new BotDecision.Input(symbol, "NONE", null, null, null, null, null, null, null))))));
        }));
        return bot;
    }

    static BotDecision.Input act(String symbol, String action, String stop, String thesis) {
        return new BotDecision.Input(symbol, action, stop == null ? null : new BigDecimal(stop), null, 0.6, thesis, "test", null, null);
    }

    @Test
    void twoBotsOnTheSameThreeSessionsAreRankedAndPromotionWaitsForTheRecord() {
        UUID infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        UUID tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        for (LocalDate d : DAYS) {
            history.write(infy, Timeframe.M1, day(infy, d));
            history.write(tcs, Timeframe.M1, day(tcs, d));
        }
        String suffix = Long.toString(System.nanoTime() % 100_000);
        Bot winner = bot("winner" + suffix, "NSE:INFY", Map.of("09:35", act("NSE:INFY", "ENTER_SHORT", "1515.00", "fade the opening push"),
                "09:40", act("NSE:INFY", "EXIT", null, "drop done")));
        Bot loser = bot("loser" + suffix, "NSE:TCS", Map.of("09:35", act("NSE:TCS", "ENTER_LONG", "1490.00", "chase the push")));

        for (LocalDate d : DAYS) {
            SimSession s = sessions.create(new SimSessionSpec(List.of(d), null, null, List.of("NSE:INFY", "NSE:TCS"), null, 1_000_000L, 2000L, 50_000L, 5,
                    List.of(Map.of("botId", winner.id().toString()), Map.of("botId", loser.id().toString()))), "tester");
            sessions.control(s.id(), SimSessionService.Action.PLAY, "MAX");
            SimSession done = await(s.id());
            assertThat(done.state()).as("%s", done.error()).isEqualTo(SimSession.State.DONE);
        }

        // one report per bot and session, from the harness snapshot at the end
        List<SimReport> won = reports.list(winner.name(), "1", 10);
        List<SimReport> lost = reports.list(loser.name(), "1", 10);
        assertThat(won).hasSize(3).allSatisfy(r -> {
            assertThat(r.trades()).isEqualTo(1);
            assertThat(r.expectancyR()).isPositive();
            assertThat(r.frictionPaise()).isPositive();
            assertThat(r.decisionsHash()).hasSize(64);
            assertThat(r.snapshot()).containsKeys("equity", "trades", "decisions", "tiles");
        });
        assertThat(won).extracting(r -> r.sessionDates().get(0)).containsExactlyInAnyOrderElementsOf(DAYS);
        assertThat(lost).hasSize(3).allSatisfy(r -> assertThat(r.expectancyR()).isLessThan(-0.5)); // stopped out: about −1R after costs
        // the decisions hash covers each session's own decision points (their ids are dated), so the three differ
        assertThat(won.stream().map(SimReport::decisionsHash).distinct()).hasSize(3);

        // the leaderboard over the three days, compared on the sessions both played
        List<SimReports.Row> board = reports.leaderboard(DAYS.get(0), DAYS.get(2), true);
        assertThat(board).extracting(SimReports.Row::bot).containsExactly(winner.name(), loser.name());
        assertThat(board).allSatisfy(r -> {
            assertThat(r.sessions()).isEqualTo(3);
            assertThat(r.trades()).isEqualTo(3);
        });
        assertThat(board.get(0).rank()).isEqualTo(1);
        assertThat(board.get(0).netPnlPaise()).isPositive();
        assertThat(board.get(1).winRate()).isEqualTo(0.0);
        assertThat(board.get(1).profitFactor()).isEqualTo(0.0);
        assertThat(board.get(1).maxDrawdownPaise()).isPositive();

        // promotion: 3 sessions are not the 20 PAPER needs
        assertThatThrownBy(() -> strategies.deploy(winner.strategyId(), 1, ExecutionMode.PAPER, List.of(), 0, Map.of(), "admin"))
                .isInstanceOf(StrategyException.Conflict.class).hasMessageContaining("3 of the 20 SIM sessions");
        // with the bar at 3 sessions the winner qualifies and the loser does not (negative expectancy)
        SimReports lowBar = new SimReports(null, null, null, bots, decisions, 3);
        assertThat(lowBar.refusal(winner.name(), "1", won)).isNull();
        assertThat(lowBar.refusal(loser.name(), "1", lost)).contains("PAPER needs it positive");

        // exported reports import idempotently where the bot is promoted
        assertThat(reports.importReports(won)).isZero();
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
