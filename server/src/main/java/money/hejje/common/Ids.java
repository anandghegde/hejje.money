package money.hejje.common;

import java.security.SecureRandom;
import java.util.UUID;

/** UUID version 7 generator (time-ordered, RFC 9562). */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(System.currentTimeMillis());
    }

    /** Builds a v7 UUID for the given Unix millisecond timestamp; random bits fill the rest. */
    public static UUID newId(long epochMillis) {
        long randA = RANDOM.nextLong() & 0x0FFFL;
        long randB = RANDOM.nextLong();
        long msb = (epochMillis << 16) | 0x7000L | randA;
        long lsb = (randB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    /** Extracts the millisecond timestamp embedded in a v7 UUID. */
    public static long timestampOf(UUID id) {
        if (id.version() != 7) {
            throw new IllegalArgumentException("Not a v7 UUID: " + id);
        }
        return id.getMostSignificantBits() >>> 16;
    }
}
