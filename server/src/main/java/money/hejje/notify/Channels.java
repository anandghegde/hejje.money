package money.hejje.notify;

import java.util.List;

/** The delivery channels known to the service (implemented in {@code notify.internal}). */
public interface Channels {

    List<Sender> all();

    /** One delivery channel; {@link #send} throws when the delivery failed. Messages never contain secrets. */
    interface Sender {

        NotificationType.Channel channel();

        boolean configured();

        String status();

        int perMinute();

        void send(Notification n) throws Exception;

        /** @param at the digest's creation time (the Hejje clock, simulation time in SIM) */
        void sendDigest(List<Notification> held, java.time.Instant at) throws Exception;
    }

    enum Decision { SEND, DIGEST, SKIP }

    /** SEND now, hold for the DIGEST (at the rate limit), or SKIP (channel not configured). */
    static Decision decide(boolean configured, int sentInLastMinute, int perMinute) {
        if (!configured) {
            return Decision.SKIP;
        }
        return sentInLastMinute >= perMinute ? Decision.DIGEST : Decision.SEND;
    }
}
