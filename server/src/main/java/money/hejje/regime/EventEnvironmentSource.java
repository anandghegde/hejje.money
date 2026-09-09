package money.hejje.regime;

import java.time.LocalDate;

/**
 * Where the event environment of a session comes from. The regime module ships a {@code NORMAL} default; the events
 * module (M3.3) provides the real one by exposing a higher-priority bean.
 */
public interface EventEnvironmentSource {

    EventEnvironment environment(LocalDate session);
}
