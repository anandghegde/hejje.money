package money.hejje.ratings;

import java.time.Instant;
import java.util.UUID;

/** A saved screen: a named {@link ScreenRequest} without a date. {@code seeded} screens ship with Hejje (the M8.4 lists). */
public record SavedScreen(UUID id, String name, ScreenRequest definition, boolean seeded, Instant createdAt, Instant updatedAt) {
}
