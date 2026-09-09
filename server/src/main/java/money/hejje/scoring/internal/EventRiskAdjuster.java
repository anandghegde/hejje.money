package money.hejje.scoring.internal;

import java.util.ArrayList;
import java.util.List;
import money.hejje.events.EventRisk;
import money.hejje.events.EventService;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoreAdjuster;
import money.hejje.scoring.ScoreContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** PRD 14 "event risk" (plan M3.3): HIGH −8, MEDIUM −3, LOW 0; 0 with the reason when the calendar is unavailable. Bounded −8..0. */
@Component
@Order(30)
public class EventRiskAdjuster implements ScoreAdjuster {

    private final EventService events;

    EventRiskAdjuster(EventService events) {
        this.events = events;
    }

    @Override
    public String name() {
        return "Event risk";
    }

    @Override
    public int min() {
        return -8;
    }

    @Override
    public int max() {
        return 0;
    }

    @Override
    public Adjustment adjust(ScoreContext ctx) {
        EventRisk risk = events.risk(ctx.instrumentId());
        if (!risk.available()) {
            return Adjustment.none(name(), min(), max(), risk.evidence().isEmpty() ? "event service unavailable" : risk.evidence().get(0));
        }
        int delta = switch (risk.level()) {
            case HIGH -> -8;
            case MEDIUM -> -3;
            case LOW -> 0;
        };
        List<String> evidence = new ArrayList<>();
        evidence.add("Event risk " + risk.level() + (ctx.instrumentId() == null ? " (market)" : ""));
        evidence.addAll(risk.evidence());
        return new Adjustment(name(), delta, min(), max(), evidence);
    }
}
