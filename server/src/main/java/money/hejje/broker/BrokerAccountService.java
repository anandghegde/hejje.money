package money.hejje.broker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import money.hejje.broker.internal.BrokerAccountStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Broker accounts (plan M5.6): every login registers its account; the first becomes the active transactional
 * account; an admin switches the active one (one at a time, enforced in the database). Orders are only sent while the
 * connected account is the active one (the {@code brokerAccount} readiness check).
 */
@Service
public class BrokerAccountService {

    private final BrokerAccountStore store;
    private final AuditService audit;
    private final HejjeClock clock;

    BrokerAccountService(BrokerAccountStore store, AuditService audit, HejjeClock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    public List<BrokerAccount> list() {
        return store.list();
    }

    public Optional<BrokerAccount> active() {
        return store.active();
    }

    /** Registers the account a login connected (idempotent); the first account registered becomes the active one. */
    @Transactional
    public BrokerAccount register(String broker, String accountId) {
        Optional<BrokerAccount> existing = store.find(broker, accountId);
        if (existing.isPresent()) {
            return existing.get();
        }
        store.insert(Ids.newId(), broker, accountId, clock.now());
        BrokerAccount account = store.find(broker, accountId).orElseThrow();
        audit.record(AuditEvent.of(AuditEventType.BROKER_ACCOUNT_REGISTERED, ActorType.SYSTEM).withActorId("broker")
                .withPayload(Map.of("broker", broker, "accountId", accountId)));
        if (store.active().isEmpty()) {
            return activate(account.id(), "system");
        }
        return account;
    }

    /** Makes the account the active transactional one (the previous one is deactivated in the same transaction). */
    @Transactional
    public BrokerAccount activate(UUID id, String by) {
        BrokerAccount account = store.find(id).orElseThrow(() -> new NoSuchElementException("No broker account " + id));
        Optional<BrokerAccount> previous = store.active();
        store.deactivateAll(clock.now());
        store.activate(id, by, clock.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("broker", account.broker());
        payload.put("accountId", account.accountId());
        previous.filter(p -> !p.id().equals(id)).ifPresent(p -> payload.put("previous", p.broker() + "/" + p.accountId()));
        audit.record(AuditEvent.of(AuditEventType.BROKER_ACCOUNT_ACTIVATED, "system".equals(by) ? ActorType.SYSTEM : ActorType.USER).withActorId(by)
                .withPayload(payload));
        return store.find(id).orElseThrow();
    }
}
