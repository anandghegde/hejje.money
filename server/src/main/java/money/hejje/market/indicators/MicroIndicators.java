package money.hejje.market.indicators;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;
import money.hejje.market.BarMicro;

/**
 * Order-book and trade-flow indicators (plan M9.4, docs/indicators.md). Each is not ready on a bar without micro data,
 * which is every bar replayed from candle history, so a rule using one can pass only on live or recorded ticks.
 */
final class MicroIndicators {

    private MicroIndicators() {
    }

    /** (bid5 − ask5) / (bid5 + ask5) at the bar's close. */
    static Indicator bookImbalance() {
        return field(BarMicro::imbalanceClose);
    }

    /** The bar's tick-weighted mean imbalance. */
    static Indicator bookImbalanceMean() {
        return field(BarMicro::imbalanceMean);
    }

    /** The exchange's total buy / total sell quantity at the bar's close. */
    static Indicator buySellQtyRatio() {
        return field(BarMicro::buySellRatio);
    }

    /** Up volume / (up + down volume) over the last {@code n} bars; every one of them needs micro data. */
    static Indicator flowUpShare(int n) {
        Deque<BarMicro> window = new ArrayDeque<>();
        return new AbstractIndicator() {
            @Override
            protected double compute(Bar bar) {
                window.addLast(bar.micro() == null ? EMPTY : bar.micro());
                while (window.size() > n) {
                    window.removeFirst();
                }
                if (window.size() < n || window.stream().anyMatch(m -> m == EMPTY)) {
                    return Double.NaN;
                }
                long up = window.stream().mapToLong(BarMicro::upVolume).sum();
                long down = window.stream().mapToLong(BarMicro::downVolume).sum();
                return up + down == 0 ? Double.NaN : (double) up / (up + down);
            }
        };
    }

    private static final BarMicro EMPTY = new BarMicro(null, null, null, null, null, null, null, 0, 0, 0, 0);

    private static Indicator field(Function<BarMicro, Double> f) {
        return new AbstractIndicator() {
            @Override
            protected double compute(Bar bar) {
                Double v = bar.micro() == null ? null : f.apply(bar.micro());
                return v == null ? Double.NaN : v;
            }
        };
    }
}
