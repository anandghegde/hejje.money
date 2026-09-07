package money.hejje.common.event;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.CorrelationId;

/**
 * Base contract of every durable domain event. Events are immutable records published through Spring's
 * {@code ApplicationEventPublisher} and consumed with {@code @ApplicationModuleListener}. See docs/events.md.
 */
public interface HejjeEvent {

    UUID id();

    Instant occurredAt();

    CorrelationId correlationId();
}
