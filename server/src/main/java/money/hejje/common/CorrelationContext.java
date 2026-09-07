package money.hejje.common;

import java.util.Optional;
import org.slf4j.MDC;

/** Thread-bound correlation id, stored in the logging MDC so every log line carries it. */
public final class CorrelationContext {

    public static final String MDC_KEY = "correlationId";

    private CorrelationContext() {
    }

    public static void set(CorrelationId id) {
        MDC.put(MDC_KEY, id.toString());
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static Optional<CorrelationId> get() {
        String value = MDC.get(MDC_KEY);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(CorrelationId.of(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The bound correlation id, or a fresh one (also bound) when none is present. */
    public static CorrelationId current() {
        return get().orElseGet(() -> {
            CorrelationId fresh = CorrelationId.newId();
            set(fresh);
            return fresh;
        });
    }
}
