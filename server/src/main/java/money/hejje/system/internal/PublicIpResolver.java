package money.hejje.system.internal;

import java.util.Optional;

/** Resolves this host's public IP as seen from the internet. */
public interface PublicIpResolver {

    String name();

    /** Empty when the resolver failed or returned something that is not an IP address. */
    Optional<String> resolve();
}
