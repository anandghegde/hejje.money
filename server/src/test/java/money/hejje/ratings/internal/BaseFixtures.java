package money.hejje.ratings.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import money.hejje.ratings.RatingsProperties;

/** Golden D1 series under {@code src/test/resources/bases} (research/tools/gen_base_fixtures.py), on consecutive weekdays from 2024-01-01. */
final class BaseFixtures {

    static final RatingsProperties.Bases CFG = new RatingsProperties.Bases(25, 120, 25, 65, 15, 35, 325, 12, 35, 90, 5, 30, 12, true, 35, 150, 3, false,
            10, 8, 35, 10, 0.5, 0.6, 5, 7, 20, 8, 5, 1.4, 60, 120);

    private BaseFixtures() {
    }

    static DailySeries series(String name) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(BaseFixtures.class.getResourceAsStream("/bases/" + name + ".csv"),
                StandardCharsets.UTF_8))) {
            List<String[]> rows = in.lines().skip(1).map(l -> l.split(",")).toList();
            int n = rows.size();
            List<LocalDate> days = RatingsEngineTest.weekdays(LocalDate.of(2024, 1, 1), n);
            DailySeries s = new DailySeries(new long[n], new double[n], new double[n], new double[n], new double[n], new double[n]);
            for (int i = 0; i < n; i++) {
                s.day()[i] = days.get(i).toEpochDay();
                s.open()[i] = Double.parseDouble(rows.get(i)[0]);
                s.high()[i] = Double.parseDouble(rows.get(i)[1]);
                s.low()[i] = Double.parseDouble(rows.get(i)[2]);
                s.close()[i] = Double.parseDouble(rows.get(i)[3]);
                s.volume()[i] = Double.parseDouble(rows.get(i)[4]);
            }
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static RatingsEngine.Member member(String name) {
        return new RatingsEngine.Member(new UUID(0, 1), "NSE:FIXTURE", "Test", series(name));
    }
}
