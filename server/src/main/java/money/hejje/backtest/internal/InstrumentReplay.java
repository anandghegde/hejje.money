package money.hejje.backtest.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import money.hejje.backtest.BacktestSpec;
import money.hejje.backtest.BacktestTrade;
import money.hejje.backtest.ExitReason;
import money.hejje.backtest.FillModel;
import money.hejje.backtest.InstrumentMeta;
import money.hejje.common.Ids;
import money.hejje.common.Money;
import money.hejje.common.Price;
import money.hejje.common.Side;
import money.hejje.common.costs.CostFill;
import money.hejje.common.costs.CostModel;
import money.hejje.market.Candle;
import money.hejje.market.indicators.Bar;
import money.hejje.market.indicators.IndicatorContext;
import money.hejje.risk.PositionSizer;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDefinition.Direction;
import money.hejje.strategy.StrategyDefinition.RuleMode;
import money.hejje.strategy.StrategyDefinition.RuleSet;
import money.hejje.strategy.StrategyDefinition.TargetType;
import money.hejje.strategy.dsl.ConditionEvaluator;
import money.hejje.strategy.dsl.EvalResult;
import money.hejje.strategy.dsl.EvalStatus;

/**
 * Replays one instrument bar by bar (docs/backtesting.md, "Replay rules"). Per bar, in order: fill a pending entry at
 * the open, check stop/target against the bar's high and low (both touched = stop), feed the indicator context, then at
 * the close apply trailing stops, rule exits, max holding, force exit and finally evaluate the entry rules when flat.
 */
public final class InstrumentReplay {

    static final int SWING_LOOKBACK = 10;
    static final int ATR_PERIOD = 14;

    private final StrategyDefinition def;
    private final BacktestSpec spec;
    private final InstrumentMeta meta;
    private final CostModel costs;
    private final Money riskPerTrade;
    private final ZoneId zone;
    private final SessionSplitter splitter;
    private final IndicatorContext ctx;
    private final Side side;
    private final List<BacktestTrade> trades = new ArrayList<>();
    private int skipped;

    private LocalDate session;
    private int tradesToday;
    private Signal pending;
    private OpenTrade open;
    private Bar lastBar;

    public InstrumentReplay(StrategyDefinition def, BacktestSpec spec, InstrumentMeta meta, CostModel costs, Money riskPerTrade, ZoneId zone,
            SessionSplitter splitter) {
        this.def = def;
        this.spec = spec;
        this.meta = meta;
        this.costs = costs;
        this.riskPerTrade = riskPerTrade;
        this.zone = zone;
        this.splitter = splitter;
        this.side = def.direction() == Direction.SHORT ? Side.SELL : Side.BUY;
        this.ctx = new IndicatorContext(spec.timeframe() == null ? def.timeframe() : spec.timeframe(), zone);
        ctx.registerDefinition(def);
        Levels.registerStopIndicators(ctx, def);
    }

    public List<BacktestTrade> trades() {
        return trades;
    }

    public int skippedSignals() {
        return skipped;
    }

    public void onCandle(Candle candle) {
        Bar bar = Bar.of(candle, zone);
        if (!bar.session().equals(session)) {
            rollSession(bar);
        }
        boolean inRange = !bar.session().isBefore(spec.from()) && !bar.session().isAfter(spec.to());

        // 1. pending entry fills at this bar's open
        if (pending != null) {
            fill(pending, bar.open(), bar, inRange);
            pending = null;
        }
        // 2. intrabar stop / target on the open trade
        if (open != null) {
            checkIntrabar(bar);
        }
        // 3. the bar is closed: indicators see it
        ctx.onCandleClosed(candle);
        lastBar = bar;
        // 4. close-of-bar management
        if (open != null) {
            manageAtClose(bar);
        }
        // 5. entry evaluation when flat
        if (open == null && pending == null && inRange) {
            evaluateEntry(bar);
        }
    }

    /** Closes anything still open at the end of the data. */
    public void finish() {
        if (open != null && lastBar != null) {
            exit(lastBar.close(), lastBar.closeTime(), ExitReason.END_OF_DATA, true);
        }
        pending = null;
    }

    private void rollSession(Bar bar) {
        if (open != null && lastBar != null) {
            exit(lastBar.close(), lastBar.closeTime(), ExitReason.END_OF_DATA, true);
        }
        pending = null;
        session = bar.session();
        tradesToday = 0;
    }

    private void evaluateEntry(Bar bar) {
        LocalTime close = bar.closeTimeOfDay();
        if (close.isBefore(def.tradeWindow().start()) || close.isAfter(def.tradeWindow().end())) {
            return;
        }
        if (tradesToday >= def.maxTradesPerDay()) {
            return;
        }
        List<EvalResult> results = ConditionEvaluator.evaluateAll(def.entry().conditions(), ctx);
        if (!passes(def.entry(), results)) {
            return;
        }
        Double stop = Levels.stop(def, side, bar.close(), ctx);
        if (stop == null) {
            skipped++;
            return;
        }
        Signal signal = new Signal(bar.closeTime(), bar.close(), stop, results);
        if (spec.fillModel() == FillModel.BAR_CLOSE) {
            fill(signal, bar.close(), bar, true);
        } else {
            pending = signal;
        }
    }

    private static boolean passes(RuleSet rules, List<EvalResult> results) {
        if (results.isEmpty()) {
            return false;
        }
        return rules.mode() == RuleMode.ALL
                ? results.stream().allMatch(EvalResult::passed)
                : results.stream().anyMatch(EvalResult::passed);
    }

    private void fill(Signal signal, double rawPrice, Bar bar, boolean inRange) {
        if (!inRange) {
            return;
        }
        BigDecimal entry = slip(rawPrice, side == Side.BUY);
        BigDecimal stop = Levels.roundStop(signal.stop(), side, meta.tickSize());
        boolean stopValid = side == Side.BUY ? stop.compareTo(entry) < 0 : stop.compareTo(entry) > 0;
        if (!stopValid) {
            skipped++;
            return;
        }
        int maxQty = def.riskOverrides().maxQuantity() == null ? 0 : def.riskOverrides().maxQuantity();
        int qty = PositionSizer.size(Price.of(entry), Price.of(stop), riskPerTrade, meta.lotSize(), maxQty);
        if (qty <= 0) {
            skipped++;
            return;
        }
        BigDecimal riskPerUnit = entry.subtract(stop).abs();
        BigDecimal target = Levels.target(def, side, entry, riskPerUnit, ctx, meta.tickSize());
        Money entryCost = costs.compute(new CostFill(meta.type(), def.product(), side, qty, entry)).total();
        open = new OpenTrade(signal, bar.openTime(), entry, stop, stop, target, qty, riskPerUnit, entryCost, bar.session());
        tradesToday++;
    }

    private void checkIntrabar(Bar bar) {
        boolean stopHit = side == Side.BUY ? bar.low() <= open.stop.doubleValue() : bar.high() >= open.stop.doubleValue();
        boolean targetHit = open.target != null
                && (side == Side.BUY ? bar.high() >= open.target.doubleValue() : bar.low() <= open.target.doubleValue());
        if (stopHit) {
            // a gap through the stop fills at the open, otherwise at the stop, minus slippage (stop-market)
            double raw = side == Side.BUY ? Math.min(bar.open(), open.stop.doubleValue()) : Math.max(bar.open(), open.stop.doubleValue());
            exit(raw, bar.openTime(), open.stopTrailed ? ExitReason.TRAILING_STOP : ExitReason.STOP, true);
        } else if (targetHit) {
            exit(open.target.doubleValue(), bar.openTime(), ExitReason.TARGET, false);
        }
    }

    private void manageAtClose(Bar bar) {
        // trailing stop moves only in the trade's favour
        if (def.trailingStop() != null) {
            Double trailed = Levels.trail(def, side, open.entry, open.riskPerUnit, bar.close(), ctx);
            if (trailed != null) {
                BigDecimal candidate = Levels.roundStop(trailed, side, meta.tickSize());
                boolean better = side == Side.BUY ? candidate.compareTo(open.stop) > 0 : candidate.compareTo(open.stop) < 0;
                if (better) {
                    open.stop = candidate;
                    open.stopTrailed = true;
                }
            }
        }
        if (def.target().type() == TargetType.VWAP) {
            OptionalDouble vwap = ctx.indicator("vwap", List.of(), 0);
            open.target = vwap.isPresent() ? Levels.roundTarget(vwap.getAsDouble(), meta.tickSize()) : null;
        }
        if (def.exit() != null && bar.openTime().isAfter(open.entryBarOpen)) {
            List<EvalResult> results = ConditionEvaluator.evaluateAll(def.exit().conditions(), ctx);
            if (passes(def.exit(), results)) {
                exit(bar.close(), bar.closeTime(), ExitReason.RULE_EXIT, true);
                return;
            }
        }
        if (def.maxHoldingMinutes() != null
                && Duration.between(open.entryBarOpen, bar.closeTime()).toMinutes() >= def.maxHoldingMinutes()) {
            exit(bar.close(), bar.closeTime(), ExitReason.MAX_HOLDING, true);
            return;
        }
        if (!bar.closeTimeOfDay().isBefore(def.forceExitTime())) {
            exit(bar.close(), bar.closeTime(), ExitReason.FORCE_EXIT, true);
        }
    }

    private void exit(double rawPrice, Instant time, ExitReason reason, boolean marketFill) {
        Side exitSide = side == Side.BUY ? Side.SELL : Side.BUY;
        BigDecimal exitPrice = marketFill ? slip(rawPrice, exitSide == Side.BUY) : Levels.roundTarget(rawPrice, meta.tickSize());
        Money exitCost = costs.compute(new CostFill(meta.type(), def.product(), exitSide, open.qty, exitPrice)).total();
        BigDecimal move = side == Side.BUY ? exitPrice.subtract(open.entry) : open.entry.subtract(exitPrice);
        Money gross = Money.of(move.multiply(BigDecimal.valueOf(open.qty)).setScale(2, RoundingMode.HALF_UP));
        Money cost = open.entryCost.plus(exitCost);
        Money net = gross.minus(cost);
        double riskMoney = open.riskPerUnit.doubleValue() * open.qty;
        double r = riskMoney <= 0 ? 0 : net.toRupees().doubleValue() / riskMoney;
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (EvalResult e : open.signal.evidence()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("condition", e.condition());
            item.put("status", e.status().name());
            if (e.status() != EvalStatus.NOT_READY) {
                item.put("lhs", e.observedLhs());
                item.put("rhs", e.observedRhs());
            }
            evidence.add(item);
        }
        Instant entryTime = spec.fillModel() == FillModel.BAR_CLOSE ? open.signal.time() : open.entryBarOpen;
        trades.add(new BacktestTrade(Ids.newId(), null, meta.id(), splitter.splitOf(open.session), entryTime, time, side, open.qty,
                open.entry, exitPrice, open.initialStop, open.target, gross, cost, net, r, reason, evidence));
        open = null;
    }

    /** Applies slippage against the taker and rounds to the tick (buys round up, sells round down). */
    private BigDecimal slip(double rawPrice, boolean buying) {
        BigDecimal price = BigDecimal.valueOf(rawPrice);
        BigDecimal factor = BigDecimal.valueOf(spec.slippageBps()).movePointLeft(4);
        BigDecimal slipped = buying ? price.multiply(BigDecimal.ONE.add(factor)) : price.multiply(BigDecimal.ONE.subtract(factor));
        return Levels.roundToTick(slipped, meta.tickSize(), buying ? RoundingMode.CEILING : RoundingMode.FLOOR);
    }

    /** An entry signal waiting for its fill. */
    record Signal(Instant time, double referencePrice, double stop, List<EvalResult> evidence) {}

    /** Mutable state of the open trade. */
    static final class OpenTrade {
        final Signal signal;
        final Instant entryBarOpen;
        final BigDecimal entry;
        final BigDecimal initialStop;
        BigDecimal stop;
        boolean stopTrailed;
        BigDecimal target;
        final int qty;
        final BigDecimal riskPerUnit;
        final Money entryCost;
        final LocalDate session;

        OpenTrade(Signal signal, Instant entryBarOpen, BigDecimal entry, BigDecimal initialStop, BigDecimal stop, BigDecimal target, int qty,
                BigDecimal riskPerUnit, Money entryCost, LocalDate session) {
            this.signal = signal;
            this.entryBarOpen = entryBarOpen;
            this.entry = entry;
            this.initialStop = initialStop;
            this.stop = stop;
            this.target = target;
            this.qty = qty;
            this.riskPerUnit = riskPerUnit;
            this.entryCost = entryCost;
            this.session = session;
        }
    }
}
