package money.hejje.backtest.internal;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import money.hejje.backtest.BacktestSpec;
import money.hejje.backtest.BacktestTrade;
import money.hejje.backtest.Split;
import money.hejje.backtest.Splits;
import money.hejje.backtest.WalkForwardWindow;

/** Assigns sessions to splits (PRD section 12.4) and, for walk-forward, records the test windows. */
public final class SessionSplitter {

    private final Map<LocalDate, Split> bySession = new HashMap<>();
    private final List<WalkForwardWindow> windows = new ArrayList<>();
    private final ZoneId zone;

    private SessionSplitter(ZoneId zone) {
        this.zone = zone;
    }

    public static SessionSplitter of(BacktestSpec spec, TreeSet<LocalDate> sessions, ZoneId zone) {
        SessionSplitter splitter = new SessionSplitter(zone);
        Splits splits = spec.splits();
        List<LocalDate> ordered = new ArrayList<>(sessions);
        switch (splits.type()) {
            case NONE -> ordered.forEach(s -> splitter.bySession.put(s, Split.IN_SAMPLE));
            case FIXED -> {
                int n = ordered.size();
                int isEnd = (int) Math.round(n * splits.inSamplePct() / 100.0);
                int valEnd = isEnd + (int) Math.round(n * splits.validationPct() / 100.0);
                for (int i = 0; i < n; i++) {
                    splitter.bySession.put(ordered.get(i), i < isEnd ? Split.IN_SAMPLE : i < valEnd ? Split.VALIDATION : Split.OUT_OF_SAMPLE);
                }
            }
            case WALK_FORWARD -> {
                ordered.forEach(s -> splitter.bySession.put(s, Split.IN_SAMPLE));
                LocalDate anchor = spec.from();
                LocalDate trainStart = anchor;
                LocalDate trainEnd = anchor.plusMonths(splits.trainMonths()); // exclusive
                int index = 0;
                while (!trainEnd.isAfter(spec.to())) {
                    LocalDate testEndExclusive = trainEnd.plusMonths(splits.testMonths());
                    LocalDate testTo = testEndExclusive.minusDays(1).isAfter(spec.to()) ? spec.to() : testEndExclusive.minusDays(1);
                    for (LocalDate s : ordered) {
                        if (!s.isBefore(trainEnd) && !s.isAfter(testTo)) {
                            splitter.bySession.put(s, Split.OUT_OF_SAMPLE);
                        }
                    }
                    splitter.windows.add(new WalkForwardWindow(index++, trainStart, trainEnd.minusDays(1), trainEnd, testTo, 0, 0));
                    if (testEndExclusive.isAfter(spec.to())) {
                        break;
                    }
                    trainEnd = testEndExclusive;
                    trainStart = splits.anchored() ? anchor : trainEnd.minusMonths(splits.trainMonths());
                }
            }
        }
        return splitter;
    }

    public Split splitOf(LocalDate session) {
        return bySession.getOrDefault(session, Split.IN_SAMPLE);
    }

    /** Walk-forward windows with their out-of-sample trade counts and expectancies filled in. */
    public List<WalkForwardWindow> windows(List<BacktestTrade> trades) {
        List<WalkForwardWindow> out = new ArrayList<>();
        for (WalkForwardWindow w : windows) {
            List<BacktestTrade> inWindow = trades.stream().filter(t -> {
                LocalDate s = t.entryTime().atZone(zone).toLocalDate();
                return !s.isBefore(w.testFrom()) && !s.isAfter(w.testTo());
            }).toList();
            double expectancy = inWindow.isEmpty() ? 0 : inWindow.stream().mapToDouble(BacktestTrade::rMultiple).average().orElse(0);
            out.add(new WalkForwardWindow(w.index(), w.trainFrom(), w.trainTo(), w.testFrom(), w.testTo(), inWindow.size(), expectancy));
        }
        return out;
    }
}
