package money.hejje.backtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import money.hejje.backtest.internal.InstrumentReplay;
import money.hejje.backtest.internal.MetricsCalculator;
import money.hejje.backtest.internal.QualityChecker;
import money.hejje.backtest.internal.SessionSplitter;
import money.hejje.common.costs.CostModel;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.strategy.StrategyDefinition;
import org.springframework.stereotype.Component;

/**
 * Deterministic bar-replay backtester (plan M2.3). Pure with respect to time and persistence: the same input always
 * yields the same trades and the same {@code resultHash}. Each instrument is replayed independently (one open trade
 * per instrument, {@code max_trades_per_day} per instrument); trades are then merged for the metrics.
 */
@Component
public class BacktestEngine {

    private final CostModel costModel;
    private final HejjeClock clock;

    public BacktestEngine(CostModel costModel, HejjeClock clock) {
        this.costModel = costModel;
        this.clock = clock;
    }

    public BacktestResult run(BacktestInput input) {
        return run(input, () -> false, pct -> { });
    }

    public BacktestResult run(BacktestInput input, BooleanSupplier cancelled, IntConsumer progress) {
        StrategyDefinition def = input.definition();
        BacktestSpec spec = input.spec();
        if (def.family() == money.hejje.strategy.StrategyFamily.BOT) {
            throw new BacktestException("a bot's strategy has no entry rules to replay; judge a bot on SIM sessions (plan M7.3)");
        }
        if (def.family() == money.hejje.strategy.StrategyFamily.SWING) {
            throw new BacktestException("a swing strategy trades the M8.4 trade plans on daily bars: use the SWING backtest (POST /swing/backtest, plan M11.5)");
        }
        if (def.direction() == StrategyDefinition.Direction.BOTH) {
            throw new BacktestException("direction: both is not supported by the backtester yet (one entry block cannot pick a side)");
        }
        if (def.direction() == StrategyDefinition.Direction.NEUTRAL) {
            throw new BacktestException("direction: neutral trades option legs, which the backtester does not replay");
        }
        ZoneId zone = clock.zone();
        SessionSplitter splitter = SessionSplitter.of(spec, sessionsInRange(input, zone), zone);

        List<BacktestTrade> trades = new ArrayList<>();
        int skipped = 0;
        int passiveFilled = 0;
        int passiveNotFilled = 0;
        TreeSet<LocalDate> sessionsWithData = new TreeSet<>();
        int total = input.candles().values().stream().mapToInt(List::size).sum();
        int done = 0;
        int lastPct = -1;
        for (Map.Entry<UUID, List<Candle>> entry : input.candles().entrySet()) {
            InstrumentMeta meta = input.instruments().get(entry.getKey());
            if (meta == null) {
                throw new BacktestException("No instrument facts for " + entry.getKey());
            }
            InstrumentReplay replay = new InstrumentReplay(def, spec, meta, costModel, input.riskPerTrade(), zone, splitter, input.sizeFactors());
            for (Candle candle : entry.getValue()) {
                if (cancelled.getAsBoolean()) {
                    throw new CancelledException();
                }
                LocalDate session = candle.openTime().atZone(zone).toLocalDate();
                if (!session.isBefore(spec.from()) && !session.isAfter(spec.to())) {
                    sessionsWithData.add(session);
                }
                replay.onCandle(candle);
                done++;
                int pct = (int) (done * 100L / Math.max(1, total));
                if (pct != lastPct) {
                    progress.accept(pct);
                    lastPct = pct;
                }
            }
            replay.finish();
            trades.addAll(replay.trades());
            skipped += replay.skippedSignals();
            passiveFilled += replay.passiveFilled();
            passiveNotFilled += replay.passiveNotFilled();
        }
        trades.sort((a, b) -> {
            int c = a.entryTime().compareTo(b.entryTime());
            return c != 0 ? c : a.instrumentId().compareTo(b.instrumentId());
        });

        List<LocalDate> sessions = new ArrayList<>(sessionsWithData);
        MetricsCalculator calculator = new MetricsCalculator(zone);
        BacktestMetrics overall = calculator.compute(trades, spec.initialCapital(), sessions);
        Map<Split, BacktestMetrics> bySplit = new EnumMap<>(Split.class);
        for (Split split : Split.values()) {
            List<BacktestTrade> slice = trades.stream().filter(t -> t.split() == split).toList();
            List<LocalDate> splitSessions = sessions.stream().filter(s -> splitter.splitOf(s) == split).toList();
            if (!slice.isEmpty() || !splitSessions.isEmpty()) {
                bySplit.put(split, calculator.compute(slice, spec.initialCapital(), splitSessions));
            }
        }
        List<WalkForwardWindow> windows = splitter.windows(trades);
        int expected = expectedSessions(spec);
        List<QualityWarning> warnings = new ArrayList<>(new QualityChecker().check(def, spec, trades, bySplit, expected, sessions.size(),
                input.instruments(), input.candles()));
        if (def.entryOrderOrMarket().passive()) { // plan M9.8: passive entries that never filled are not trades
            warnings.add(new QualityWarning("PASSIVE_ENTRY_NOT_FILLED", QualityWarning.Severity.WARN, passiveNotFilled + " of "
                    + (passiveFilled + passiveNotFilled) + " passive entries did not fill before their cancel time (limit at the signal close, filled only when a bar "
                    + "traded strictly through it; no re-quotes on candles)", java.util.Map.of("filled", passiveFilled, "notFilled", passiveNotFilled)));
        }
        return new BacktestResult(overall, bySplit, windows, warnings, trades, expected, sessions.size(), skipped, hash(trades));
    }

    private TreeSet<LocalDate> sessionsInRange(BacktestInput input, ZoneId zone) {
        TreeSet<LocalDate> sessions = new TreeSet<>();
        for (List<Candle> candles : input.candles().values()) {
            for (Candle candle : candles) {
                LocalDate session = candle.openTime().atZone(zone).toLocalDate();
                if (!session.isBefore(input.spec().from()) && !session.isAfter(input.spec().to())) {
                    sessions.add(session);
                }
            }
        }
        return sessions;
    }

    private int expectedSessions(BacktestSpec spec) {
        int n = 0;
        for (LocalDate d = spec.from(); !d.isAfter(spec.to()); d = d.plusDays(1)) {
            if (clock.isTradingDay(d)) {
                n++;
            }
        }
        return n;
    }

    /** SHA-256 over the trade list: the determinism check compares this across runs. */
    public static String hash(List<BacktestTrade> trades) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (BacktestTrade t : trades) {
                String line = t.instrumentId() + "|" + t.entryTime() + "|" + t.exitTime() + "|" + t.side() + "|" + t.qty() + "|"
                        + t.entryPrice().toPlainString() + "|" + t.exitPrice().toPlainString() + "|" + t.netPnl().paise() + "|" + t.exitReason() + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Thrown when the caller's cancel flag was raised mid-run. */
    public static class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Backtest cancelled");
        }
    }
}
