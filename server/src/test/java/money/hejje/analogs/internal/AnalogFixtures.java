package money.hejje.analogs.internal;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import money.hejje.analogs.AnalogsProperties;

/** A seeded random-walk universe on consecutive weekdays, and the default settings. */
final class AnalogFixtures {

    static final AnalogsProperties PROPS = props(10);

    private AnalogFixtures() {
    }

    static AnalogsProperties props(int minEvidence) {
        return new AnalogsProperties(true, "test", "1", List.of(5, 10, 15, 20, 25, 30, 40, 50), List.of(3, 5, 10, 15), 50, 1.5, minEvidence, 5, 2,
                Duration.ofMinutes(30), 30, new AnalogsProperties.Weights(0.30, 0.20, 0.12, 0.12, 0.10, 0.08, 0.08),
                new AnalogsProperties.Prefilter(1.0, 1.0, 1.0), new AnalogsProperties.Tags(0.65, 0.55, 0.8, 1.6, 1.0, 0.5, 0.3, 30, 15, 4, 15, 8, 2),
                new AnalogsProperties.Session("test", List.of(), List.of("09:45", "10:15", "11:15", "13:00"), "15:10", 0.10, 1.0, 20));
    }

    static List<LocalDate> weekdays(LocalDate from, int n) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; out.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        return out;
    }

    /** Mutable arrays of one symbol so a test can plant a pattern before building the series. */
    static final class Walk {
        final UUID id;
        final String symbol;
        final long[] day;
        final double[] close;
        final double[] volume;

        Walk(int k, List<LocalDate> days, long seed) {
            this.id = new UUID(0, k);
            this.symbol = "NSE:W" + k;
            Random random = new Random(seed + k);
            int n = days.size();
            day = days.stream().mapToLong(LocalDate::toEpochDay).toArray();
            close = new double[n];
            volume = new double[n];
            double price = 100 + 10 * k;
            for (int i = 0; i < n; i++) {
                price *= 1 + random.nextGaussian() * 0.012;
                close[i] = price;
                volume[i] = 100_000 * (1 + 0.3 * Math.abs(random.nextGaussian()));
            }
        }

        DailyAnalogEngine.Series series() {
            return DailyAnalogEngine.Series.of(id, symbol, day, close.clone(), volume.clone());
        }

        DailyAnalogEngine.Series series(int sessions) {
            return DailyAnalogEngine.Series.of(id, symbol, java.util.Arrays.copyOf(day, sessions), java.util.Arrays.copyOf(close, sessions),
                    java.util.Arrays.copyOf(volume, sessions));
        }
    }

    static List<Walk> walks(int symbols, int sessions, long seed) {
        List<LocalDate> days = weekdays(LocalDate.of(2022, 1, 3), sessions);
        List<Walk> out = new ArrayList<>();
        for (int k = 0; k < symbols; k++) {
            out.add(new Walk(k, days, seed));
        }
        return out;
    }
}
