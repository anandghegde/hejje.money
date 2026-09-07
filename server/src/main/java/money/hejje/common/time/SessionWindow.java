package money.hejje.common.time;

import java.time.Instant;
import java.time.ZonedDateTime;

/** Regular trading session boundaries for one day. Open is inclusive, close is exclusive. */
public record SessionWindow(ZonedDateTime open, ZonedDateTime close) {

    public boolean contains(Instant instant) {
        return !instant.isBefore(open.toInstant()) && instant.isBefore(close.toInstant());
    }
}
