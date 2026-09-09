package money.hejje.events;

import java.util.List;

/**
 * PRD 18.3 proximity-based risk for one instrument (or the market when the instrument is null).
 *
 * @param nextEvent the next relevant event (today's, else the earliest upcoming within the horizon), or null
 * @param minutesTo minutes until {@code nextEvent} starts (0 while in progress or for an all-day event today), or null
 * @param available false when the event service is disabled or failed (level is then LOW)
 */
public record EventRisk(EventRiskLevel level, MarketEvent nextEvent, Long minutesTo, List<String> evidence, boolean available) {

    public EventRisk {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static EventRisk unavailable(String reason) {
        return new EventRisk(EventRiskLevel.LOW, null, null, List.of(reason), false);
    }

    /** "Q2 Results — Today 16:00" style line, or null. */
    public String nextEventLine(java.time.ZoneId zone, java.time.LocalDate today) {
        if (nextEvent == null) {
            return null;
        }
        java.time.ZonedDateTime at = nextEvent.startsAt().atZone(zone);
        String when = at.toLocalDate().equals(today) ? "Today" : at.toLocalDate().equals(today.plusDays(1)) ? "Tomorrow" : at.toLocalDate().toString();
        return nextEvent.title() + " — " + when + (nextEvent.allDay() ? "" : " " + at.toLocalTime().toString().substring(0, 5));
    }
}
