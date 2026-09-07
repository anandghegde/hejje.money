package money.hejje.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Test clock whose instant can be set or advanced. */
public class MutableClock extends Clock {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock at(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant), IST);
    }

    /** Clock set to the given IST wall-clock time, for example {@code atIst("2026-09-08T09:15:00")}. */
    public static MutableClock atIst(String localDateTime) {
        return new MutableClock(LocalDateTime.parse(localDateTime).atZone(IST).toInstant(), IST);
    }

    public void set(Instant instant) {
        this.instant = instant;
    }

    public void setIst(String localDateTime) {
        set(LocalDateTime.parse(localDateTime).atZone(IST).toInstant());
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
