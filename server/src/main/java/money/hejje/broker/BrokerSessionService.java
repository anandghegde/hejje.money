package money.hejje.broker;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.broker.internal.BrokerAuthRejected;
import money.hejje.broker.internal.BrokerSessionStore;
import money.hejje.common.ActorType;
import money.hejje.common.event.EventMeta;
import money.hejje.common.security.TokenCipher;
import money.hejje.common.time.HejjeClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Owns the broker session (PRD section 40): login URL, token exchange, encrypted persistence, restore on startup,
 * periodic validation, daily expiry and logout. State changes publish {@link BrokerSessionChanged} and are audited.
 */
@Service
public class BrokerSessionService {

    private static final Logger log = LoggerFactory.getLogger(BrokerSessionService.class);
    /** Kite invalidates tokens around 06:00 IST the next day; the daemon marks them expired at 06:15 IST. */
    static final LocalTime DAILY_EXPIRY = LocalTime.of(6, 0);
    private static final LocalTime VALIDATION_FROM = LocalTime.of(8, 30);
    private static final LocalTime VALIDATION_TO = LocalTime.of(15, 45);

    private final BrokerAdapter adapter;
    private final BrokerSessionStore store;
    private final TokenCipher cipher;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    private final BrokerAccountService accounts;

    BrokerSessionService(BrokerAdapter adapter, BrokerSessionStore store, TokenCipher cipher, HejjeClock clock,
            ApplicationEventPublisher events, AuditService audit, BrokerAccountService accounts) {
        this.accounts = accounts;
        this.adapter = adapter;
        this.store = store;
        this.cipher = cipher;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
    }

    public String broker() {
        return adapter.brokerCode();
    }

    public String loginUrl() {
        return adapter.loginUrl();
    }

    public boolean isConnected() {
        return adapter.sessionState() == BrokerSessionState.CONNECTED;
    }

    /** Exchanges the broker request token, persists the encrypted access token and marks the session CONNECTED. */
    public synchronized BrokerSessionStatus completeLogin(String requestToken) {
        BrokerSessionState previous = adapter.sessionState();
        BrokerSession session;
        try {
            session = adapter.authenticate(requestToken);
        } catch (BrokerException e) {
            store.transition(broker(), BrokerSessionState.ERROR, "login failed: " + e.brokerMessage(), clock.now());
            changed(previous, BrokerSessionState.ERROR, "login failed: " + e.brokerMessage(), AuditEventType.BROKER_LOGIN_FAILED);
            throw e;
        }
        Instant now = clock.now();
        Instant expiresAt = clock.today().plusDays(1).atTime(DAILY_EXPIRY).atZone(clock.zone()).toInstant();
        store.connected(broker(), session.brokerUserId(), cipher.encrypt(session.accessToken()), session.publicToken(),
                session.establishedAt() != null ? session.establishedAt() : now, expiresAt, now);
        changed(previous, BrokerSessionState.CONNECTED, "login as " + session.brokerUserId(), AuditEventType.BROKER_CONNECTED);
        if (session.brokerUserId() != null) {
            try {
                accounts.register(adapter.instrumentBrokerCode(), session.brokerUserId()); // M5.6: the first account becomes the active one
            } catch (RuntimeException e) {
                log.warn("Could not register broker account {}: {}", session.brokerUserId(), e.getMessage());
            }
        }
        return status();
    }

    /** Invalidates the session at the broker (best effort) and clears the stored token. */
    public synchronized BrokerSessionStatus logout() {
        BrokerSessionState previous = adapter.sessionState();
        try {
            adapter.logout();
        } catch (BrokerException e) {
            log.warn("Broker logout failed ({}); clearing the local session anyway", e.brokerMessage());
            adapter.clearSession();
        }
        store.transition(broker(), BrokerSessionState.DISCONNECTED, "logged out", clock.now());
        changed(previous, BrokerSessionState.DISCONNECTED, "logged out", AuditEventType.BROKER_LOGGED_OUT);
        return status();
    }

    /** Calls the broker to confirm the session is still valid; on an AUTH failure marks it DISCONNECTED. */
    public synchronized BrokerSessionStatus validate() {
        if (adapter.sessionState() != BrokerSessionState.CONNECTED) {
            return status();
        }
        try {
            adapter.getProfile();
            store.touchChecked(broker(), clock.now());
        } catch (BrokerException e) {
            if (e.kind() == BrokerException.Kind.AUTH) {
                if (adapter.sessionState() == BrokerSessionState.CONNECTED) {
                    markDisconnected("broker rejected the session: " + e.brokerMessage());
                }
            } else {
                log.warn("Broker session validation inconclusive ({}: {})", e.kind(), e.brokerMessage());
            }
        }
        return status();
    }

    /** Adapters raise this when the broker rejects the session mid-call. */
    @EventListener
    void onAuthRejected(BrokerAuthRejected rejected) {
        if (rejected.broker().equals(broker())) {
            markDisconnected(rejected.detail());
        }
    }

    /** Marks the session DISCONNECTED (broker rejected the token); persists, publishes and audits the change. */
    public synchronized void markDisconnected(String detail) {
        // Adapters flip their own state before signalling, so "previous" comes from the stored row; a missing row means
        // the process-default session (fake) was connected.
        BrokerSessionState previous = adapter.sessionState() == BrokerSessionState.CONNECTED ? BrokerSessionState.CONNECTED
                : store.find(broker()).map(BrokerSessionStore.Row::status).orElse(BrokerSessionState.CONNECTED);
        adapter.clearSession();
        store.transition(broker(), BrokerSessionState.DISCONNECTED, detail, clock.now());
        if (previous != BrokerSessionState.DISCONNECTED) {
            changed(previous, BrokerSessionState.DISCONNECTED, detail, AuditEventType.BROKER_DISCONNECTED);
        }
    }

    public BrokerSessionStatus status() {
        Optional<BrokerSessionStore.Row> row = store.find(broker());
        BrokerSessionState state = adapter.sessionState();
        String detail;
        if (row.isPresent() && row.get().status() == state && row.get().detail() != null) {
            detail = row.get().detail();
        } else if (state == BrokerSessionState.CONNECTED) {
            detail = "connected";
        } else {
            detail = row.map(BrokerSessionStore.Row::detail).filter(d -> d != null).orElse("no session; login required");
        }
        return new BrokerSessionStatus(broker(), state,
                state == BrokerSessionState.CONNECTED ? row.map(BrokerSessionStore.Row::brokerUserId).orElse(null) : null,
                row.map(BrokerSessionStore.Row::establishedAt).orElse(null),
                row.map(BrokerSessionStore.Row::expiresAt).orElse(null),
                row.map(BrokerSessionStore.Row::lastCheckedAt).orElse(null),
                detail, state == BrokerSessionState.CONNECTED);
    }

    @EventListener(ApplicationReadyEvent.class)
    void restoreOnStartup() {
        Optional<BrokerSessionStore.Row> row = store.find(broker());
        if (row.isEmpty() || row.get().status() != BrokerSessionState.CONNECTED || row.get().accessTokenEnc() == null) {
            log.info("Broker session for {}: {}", broker(), adapter.sessionState());
            return;
        }
        BrokerSessionStore.Row r = row.get();
        if (r.expiresAt() != null && !clock.now().isBefore(r.expiresAt())) {
            expire("stored session past its expiry");
            return;
        }
        try {
            adapter.restoreSession(cipher.decrypt(r.accessTokenEnc()));
        } catch (IllegalStateException e) {
            markDisconnected("stored token could not be decrypted (encryption key changed?)");
            return;
        }
        log.info("Restored broker session for {} user {}", broker(), r.brokerUserId());
        validate();
    }

    /** 06:15 IST daily: the broker has expired yesterday's token by now. */
    @Scheduled(cron = "0 15 6 * * *", zone = "Asia/Kolkata")
    void dailyExpiry() {
        if (adapter.sessionState() == BrokerSessionState.CONNECTED && store.find(broker()).map(BrokerSessionStore.Row::accessTokenEnc).isPresent()) {
            expire("daily token expiry");
        }
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    void periodicValidation() {
        LocalTime now = clock.nowIst().toLocalTime();
        if (clock.isTradingDay(clock.today()) && !now.isBefore(VALIDATION_FROM) && now.isBefore(VALIDATION_TO)) {
            validate();
        }
    }

    private synchronized void expire(String detail) {
        BrokerSessionState previous = adapter.sessionState();
        adapter.clearSession();
        store.transition(broker(), BrokerSessionState.EXPIRED, detail, clock.now());
        changed(previous, BrokerSessionState.EXPIRED, detail, AuditEventType.BROKER_SESSION_EXPIRED);
    }

    private void changed(BrokerSessionState previous, BrokerSessionState current, String detail, AuditEventType type) {
        log.info("Broker session {}: {} -> {} ({})", broker(), previous, current, detail);
        events.publishEvent(new BrokerSessionChanged(EventMeta.create(clock), broker(), previous, current, detail));
        audit.record(AuditEvent.of(type, ActorType.SYSTEM).withActorId("broker-session")
                .withPayload(Map.of("broker", broker(), "previous", previous.name(), "current", current.name(), "detail", detail)));
    }
}
