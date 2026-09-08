package money.hejje.market.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Formats an instant as a DuckDB TIMESTAMP literal in UTC. */
final class DuckIso {

    private DuckIso() {
    }

    static String of(Instant instant) {
        ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
        return String.format("%04d-%02d-%02d %02d:%02d:%02d", z.getYear(), z.getMonthValue(), z.getDayOfMonth(), z.getHour(), z.getMinute(), z.getSecond());
    }
}
