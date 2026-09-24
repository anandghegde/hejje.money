package money.hejje.analogs.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import money.hejje.analogs.AnalogKind;
import money.hejje.analogs.AnalogSummary;
import money.hejje.analogs.AnalogsProperties;
import money.hejje.ratings.ScreenFieldSource;
import org.springframework.stereotype.Component;

/**
 * The daily-analog evidence as screener fields, for the 15-session lookback (the first configured one when 15 is not
 * configured): direction and reliability of the narrative forward window, and win rate, median and count per forward
 * window. A win rate never travels without its count.
 */
@Component
class AnalogScreenFields implements ScreenFieldSource {

    private final AnalogsProperties props;
    private final AnalogReads reads;

    AnalogScreenFields(AnalogsProperties props, AnalogReads reads) {
        this.props = props;
        this.reads = reads;
    }

    private int lookback() {
        return props.lookbacks().contains(15) ? 15 : props.lookbacks().get(0);
    }

    @Override
    public List<String> fields() {
        List<String> out = new ArrayList<>(List.of("analogDirection", "analogReliability", "analogQuality", "analogMatches"));
        for (int f : props.forwards()) {
            out.add("analogWinRate" + f);
            out.add("analogMedian" + f);
            out.add("analogCount" + f);
        }
        return out;
    }

    @Override
    public Map<String, Map<String, Object>> values(LocalDate date) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (!props.enabled()) {
            return out;
        }
        int lookback = lookback();
        reads.session(AnalogKind.DAILY, date).ifPresent(session -> {
            for (AnalogSummary s : reads.forDate(session, AnalogKind.DAILY)) {
                if (s.lookback() != lookback) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("analogQuality", s.qualityTag());
                row.put("analogMatches", s.matches());
                for (AnalogSummary.Outcome o : s.outcomes()) {
                    row.put("analogWinRate" + o.forward(), o.winRate());
                    row.put("analogMedian" + o.forward(), o.median());
                    row.put("analogCount" + o.forward(), o.count());
                    if (o.forward().equals(String.valueOf(props.narrativeForward()))) {
                        row.put("analogDirection", o.direction());
                        row.put("analogReliability", o.reliability());
                    }
                }
                out.put(s.symbol(), row);
            }
        });
        return out;
    }
}
