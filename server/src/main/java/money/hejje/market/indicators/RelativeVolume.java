package money.hejje.market.indicators;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Bar volume divided by the mean volume of the same time-of-day slot over the previous {@code sessions} sessions
 * (fewer when history is shorter; not ready with no prior session for the slot, or when that mean is zero).
 * The current bar never counts toward its own baseline.
 */
public final class RelativeVolume extends AbstractIndicator {

    private final int sessions;
    private final Map<LocalTime, Deque<Double>> slots = new HashMap<>();

    public RelativeVolume(int sessions) {
        this.sessions = sessions;
    }

    @Override
    protected double compute(Bar bar) {
        Deque<Double> history = slots.computeIfAbsent(bar.openTimeOfDay(), k -> new ArrayDeque<>());
        double value = Double.NaN;
        if (!history.isEmpty()) {
            double mean = history.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            value = mean > 0 ? bar.volume() / mean : Double.NaN;
        }
        history.addLast(bar.volume());
        while (history.size() > sessions) {
            history.removeFirst();
        }
        return value;
    }
}
