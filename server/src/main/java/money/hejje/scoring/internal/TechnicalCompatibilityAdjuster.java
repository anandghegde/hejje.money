package money.hejje.scoring.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.market.indicators.IndicatorContext;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoreAdjuster;
import money.hejje.scoring.ScoreContext;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.dsl.ConditionEvaluator;
import money.hejje.strategy.dsl.EvalResult;
import money.hejje.strategy.dsl.EvalStatus;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Is the setup active or forming on the instrument right now? Warms an indicator context from the last few sessions
 * and evaluates the entry conditions at the last closed bar: every condition passing = +8, none = -10, linear in
 * between ({@code round(-10 + 18 × passedShare)}); NOT_READY counts as not passing. No candles = 0.
 */
@Component
@Order(10)
public class TechnicalCompatibilityAdjuster implements ScoreAdjuster {

    static final int LOOKBACK_DAYS = 5;

    private final MarketService market;
    private final HejjeClock clock;

    TechnicalCompatibilityAdjuster(MarketService market, HejjeClock clock) {
        this.market = market;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "Technical compatibility";
    }

    @Override
    public int min() {
        return -10;
    }

    @Override
    public int max() {
        return 8;
    }

    @Override
    public Adjustment adjust(ScoreContext ctx) {
        if (ctx.instrumentId() == null) {
            return Adjustment.none(name(), min(), max(), "no instrument to evaluate against");
        }
        StrategyDefinition def = ctx.version().definition();
        Instant now = clock.now();
        List<Candle> candles = market.candles(ctx.instrumentId(), def.timeframe(), now.minus(java.time.Duration.ofDays(LOOKBACK_DAYS)), now);
        if (candles.isEmpty()) {
            return Adjustment.none(name(), min(), max(), "no recent candles for the instrument");
        }
        IndicatorContext context = new IndicatorContext(def.timeframe(), clock.zone());
        context.registerDefinition(def);
        context.warmUp(candles);
        List<EvalResult> results = ConditionEvaluator.evaluateAll(def.entry().conditions(), context);
        long passed = results.stream().filter(EvalResult::passed).count();
        double share = results.isEmpty() ? 0 : (double) passed / results.size();
        int delta = (int) Math.round(-10 + 18 * share);
        List<String> evidence = new ArrayList<>();
        evidence.add(passed + " of " + results.size() + " entry conditions pass on the last closed bar (" + context.lastBar().closeTime() + ")");
        for (EvalResult r : results) {
            if (r.status() == EvalStatus.NOT_READY) {
                evidence.add(r.condition() + ": not ready");
            } else {
                evidence.add(r.condition() + ": " + r.status().name().toLowerCase() + " (" + fmt(r.observedLhs()) + " vs " + fmt(r.observedRhs()) + ")");
            }
        }
        return new Adjustment(name(), delta, min(), max(), evidence);
    }

    private static String fmt(Double v) {
        return v == null ? "n/a" : String.format("%.2f", v);
    }
}
