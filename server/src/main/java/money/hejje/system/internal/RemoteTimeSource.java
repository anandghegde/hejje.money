package money.hejje.system.internal;

import java.time.Instant;
import java.util.Optional;

/** Provides a trusted remote wall-clock reading. */
public interface RemoteTimeSource {

    Optional<Instant> remoteNow();
}
