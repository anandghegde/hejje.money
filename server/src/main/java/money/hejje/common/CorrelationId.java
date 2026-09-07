package money.hejje.common;

import java.util.UUID;

/** Identifier that links every log line, audit event and domain event caused by one request or job. */
public record CorrelationId(UUID value) {

    public CorrelationId {
        if (value == null) {
            throw new IllegalArgumentException("CorrelationId is required");
        }
    }

    public static CorrelationId newId() {
        return new CorrelationId(Ids.newId());
    }

    public static CorrelationId of(String value) {
        return new CorrelationId(UUID.fromString(value));
    }

    public static CorrelationId of(UUID value) {
        return new CorrelationId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
