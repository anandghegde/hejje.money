package money.hejje.events;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import money.hejje.instruments.Instrument;

/**
 * PRD 18.3 proximity rules (docs/events.md, thresholds in config/events.yaml). Pure: the caller supplies the candidate
 * events (market events plus the instrument's) and the instrument.
 *
 * <ul>
 * <li>results / earnings call today for the instrument → HIGH</li>
 * <li>macro event starting within {@code macroHighWithinMinutes} (or in progress) → HIGH; any other macro event today → MEDIUM</li>
 * <li>F&amp;O expiry day when the instrument is an index derivative of that underlying → MEDIUM</li>
 * <li>ex-date (dividend / bonus / split) or board meeting today for the instrument → MEDIUM</li>
 * <li>otherwise LOW</li>
 * </ul>
 * The next event is today's most imminent relevant event, else the earliest upcoming one within the horizon.
 */
public final class EventRiskEvaluator {

    private EventRiskEvaluator() {
    }

    public static EventRisk evaluate(List<MarketEvent> candidates, Instrument instrument, Instant now, ZoneId zone, EventProperties.Risk rules) {
        LocalDate today = now.atZone(zone).toLocalDate();
        List<String> evidence = new ArrayList<>();
        EventRiskLevel level = EventRiskLevel.LOW;
        MarketEvent trigger = null;
        long triggerMinutes = 0;
        List<MarketEvent> relevant = new ArrayList<>();
        for (MarketEvent e : candidates) {
            if (!relevantTo(e, instrument)) {
                continue;
            }
            relevant.add(e);
            LocalDate date = e.startsAt().atZone(zone).toLocalDate();
            boolean isToday = date.equals(today) || (e.endsAt() != null && !e.startsAt().isAfter(now) && !e.endsAt().isBefore(now));
            if (!isToday) {
                continue;
            }
            long minutes = Duration.between(now, e.startsAt()).toMinutes();
            boolean inProgress = e.allDay() || (!e.startsAt().isAfter(now) && Duration.between(e.startsAt(), now).toMinutes() <= rules.macroInProgressMinutes());
            EventRiskLevel contributed = null;
            if (e.type().isResults() && e.scope() == EventScope.INSTRUMENT) {
                contributed = EventRiskLevel.HIGH;
                evidence.add(line(e, zone, "results today") + " → HIGH");
            } else if (e.type().isMacro()) {
                if (inProgress || (minutes >= 0 && minutes <= rules.macroHighWithinMinutes())) {
                    contributed = EventRiskLevel.HIGH;
                    evidence.add(line(e, zone, inProgress ? "in progress" : "in " + minutes + " min") + " → HIGH");
                } else {
                    contributed = EventRiskLevel.MEDIUM;
                    evidence.add(line(e, zone, minutes < 0 ? (-minutes) + " min ago" : "in " + minutes + " min") + " → MEDIUM");
                }
            } else if (e.type() == EventType.FNO_EXPIRY && isIndexDerivativeOf(instrument, e)) {
                contributed = EventRiskLevel.MEDIUM;
                evidence.add(line(e, zone, "expiry day for " + instrument.hejjeSymbol().format()) + " → MEDIUM");
            } else if ((e.type().isExDate() || e.type() == EventType.BOARD_MEETING) && e.scope() == EventScope.INSTRUMENT) {
                contributed = EventRiskLevel.MEDIUM;
                evidence.add(line(e, zone, e.type() == EventType.BOARD_MEETING ? "board meeting today" : "ex-date today") + " → MEDIUM");
            }
            if (contributed != null && (trigger == null || contributed.ordinal() > level.ordinal()
                    || (contributed == level && Math.max(0, minutes) < triggerMinutes))) {
                trigger = e;
                triggerMinutes = inProgress || minutes < 0 ? 0 : minutes;
            }
            if (contributed != null) {
                level = max(level, contributed);
            }
        }
        MarketEvent next = relevant.stream().filter(e -> e.endsAt() == null ? !e.startsAt().isBefore(now.minus(Duration.ofMinutes(rules.macroInProgressMinutes())))
                : !e.endsAt().isBefore(now)).min(Comparator.comparing(MarketEvent::startsAt)).orElse(null);
        Long minutesTo = next == null ? null : Math.max(0, Duration.between(now, next.startsAt()).toMinutes());
        if (evidence.isEmpty()) {
            evidence.add(next == null ? "No scheduled events for " + (instrument == null ? "the market" : instrument.hejjeSymbol().format()) + " in the horizon"
                    : "No event today; next: " + line(next, zone, null));
        }
        return new EventRisk(level, next, minutesTo, trigger, trigger == null ? null : triggerMinutes, evidence, true);
    }

    /** Market events always count; instrument events only for the instrument itself or, for derivatives, its underlying's symbol. */
    static boolean relevantTo(MarketEvent e, Instrument instrument) {
        if (e.scope() == EventScope.MARKET) {
            return true;
        }
        if (instrument == null) {
            return false;
        }
        if (instrument.id().equals(e.instrumentId())) {
            return true;
        }
        return e.symbol() != null && instrument.isDerivative() && instrument.underlying() != null
                && e.symbol().equalsIgnoreCase("NSE:" + instrument.underlying());
    }

    static boolean isIndexDerivativeOf(Instrument instrument, MarketEvent expiry) {
        return instrument != null && instrument.isDerivative() && instrument.underlying() != null && expiry.symbol() != null
                && expiry.symbol().equalsIgnoreCase(instrument.underlying());
    }

    private static EventRiskLevel max(EventRiskLevel a, EventRiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static String line(MarketEvent e, ZoneId zone, String detail) {
        String when = e.allDay() ? e.startsAt().atZone(zone).toLocalDate().toString()
                : String.format(Locale.ROOT, "%s %s", e.startsAt().atZone(zone).toLocalDate(), e.startsAt().atZone(zone).toLocalTime().toString().substring(0, 5));
        return e.title() + " (" + when + (detail == null ? "" : ", " + detail) + ")";
    }
}
