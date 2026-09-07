package money.hejje.system;

/** Result of comparing the server's public egress IP with the configured expected IPs. */
public enum EgressIpStatus {
    /** Egress IP is one of the expected IPs. */
    VERIFIED,
    /** Egress IP resolved but is not expected, resolvers disagree, or no expected IPs are configured. */
    MISMATCH,
    /** Every resolver failed; the egress IP is not known. */
    UNKNOWN,
    /** Verification disabled (dev/test). */
    SKIPPED
}
