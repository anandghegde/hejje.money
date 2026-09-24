package money.hejje.ratings;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import money.hejje.common.ClientNotification;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The evening digest line (plan M8.7): the market condition, the session's new buy-zone entries and the top of the
 * ranked analog list. Published once per evening as the {@code daily_context_digest} client event by whichever nightly
 * job runs last (the analogs when they are on, else the ratings).
 */
@Component
public class DailyDigest {

    private final RatingsService ratings;
    private final RegimeService regime;
    private final ApplicationEventPublisher events;

    DailyDigest(RatingsService ratings, RegimeService regime, ApplicationEventPublisher events) {
        this.ratings = ratings;
        this.regime = regime;
        this.events = events;
    }

    /** @param analogTop the top of the ranked analog list, each entry already carrying its count (empty when analogs are off) */
    public String publish(LocalDate date, List<String> analogTop) {
        String condition = regime.forDate(date).map(RegimeSnapshot::marketCondition).map(Enum::name).orElse("UNKNOWN");
        List<String> entered = new ArrayList<>();
        for (Base b : ratings.transitions(date)) {
            if (b.status() == BaseStatus.IN_BUY_ZONE && date.equals(b.triggerDate())) {
                entered.add(b.symbol() + (Boolean.TRUE.equals(b.volumeConfirmed()) ? " (volume)" : ""));
            }
        }
        String line = "Market condition " + condition + ". New in buy zone: " + (entered.isEmpty() ? "none" : entered.size() + " (" + String.join(", ",
                entered.subList(0, Math.min(5, entered.size()))) + (entered.size() > 5 ? ", ..." : "") + ")") + ". Analog leaders: "
                + (analogTop.isEmpty() ? "none" : String.join(", ", analogTop)) + ".";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", date.toString());
        data.put("marketCondition", condition);
        data.put("enteredBuyZone", entered);
        data.put("analogTop", analogTop);
        data.put("line", line);
        events.publishEvent(new ClientNotification("daily_context_digest", data));
        return line;
    }
}
