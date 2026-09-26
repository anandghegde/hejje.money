package money.hejje.broker.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.broker.BrokerException;
import money.hejje.broker.Gtt;
import money.hejje.common.time.HejjeClock;

/**
 * GTTs of a simulated broker (plan M11.2), shared by the fake and the paper adapters. A GTT fires on the first price that
 * meets a trigger: a live tick, or the first tick of a session that opened through it (the adapter then fills the leg's
 * order at that price, so a gap through a stop fills at the open). The paper adapter keeps them in a
 * {@link PaperStateStore} so they survive a restart, as they would at the broker.
 */
public final class SimulatedGtts {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /** A leg whose trigger was met: the adapter places its order. */
    public record Fired(String gttId, UUID instrumentId, Gtt.Leg leg) {}

    /** One stored GTT. */
    public record Entry(String id, UUID instrumentId, String tradingSymbol, Gtt.Type type, Gtt.Status status, List<BigDecimal> triggers,
            BigDecimal lastPrice, List<Gtt.Leg> legs, String triggeredOrderId, Instant createdAt, Instant updatedAt) {

        Entry with(Gtt.Status s, String orderId, Instant at) {
            return new Entry(id, instrumentId, tradingSymbol, type, s, triggers, lastPrice, legs, orderId, createdAt, at);
        }
    }

    /** The persisted state: the id sequence and every GTT. */
    public record State(long sequence, List<Entry> gtts) {}

    private final HejjeClock clock;
    private final PaperStateStore store;
    private final String key;
    private final Map<String, Entry> gtts = new LinkedHashMap<>();
    private long sequence = 1;

    public SimulatedGtts(HejjeClock clock, PaperStateStore store, String key) {
        this.clock = clock;
        this.store = store;
        this.key = key;
        store.load(key).ifPresent(json -> {
            try {
                State s = JSON.readValue(json, State.class);
                sequence = s.sequence();
                s.gtts().forEach(g -> gtts.put(g.id(), g));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("simulated GTTs '" + key + "' are unreadable", e);
            }
        });
    }

    private void persist() {
        try {
            store.save(key, JSON.writeValueAsString(new State(sequence, new ArrayList<>(gtts.values()))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized String place(Gtt.Request request, String tradingSymbol) {
        String id = "GTT" + sequence++;
        Instant now = clock.now();
        gtts.put(id, new Entry(id, request.instrumentId(), tradingSymbol, request.type(), Gtt.Status.ACTIVE, request.triggers(), request.lastPrice(),
                request.legs(), null, now, now));
        persist();
        return id;
    }

    public synchronized String modify(String id, Gtt.Request request) {
        Entry g = active(id);
        gtts.put(id, new Entry(id, g.instrumentId(), g.tradingSymbol(), request.type(), Gtt.Status.ACTIVE, request.triggers(), request.lastPrice(),
                request.legs(), null, g.createdAt(), clock.now()));
        persist();
        return id;
    }

    /** Deletes an active GTT (Kite reports a deleted GTT as {@code DELETED}). */
    public synchronized String cancel(String id) {
        Entry g = active(id);
        gtts.put(id, g.with(Gtt.Status.DELETED, null, clock.now()));
        persist();
        return id;
    }

    /** Test hook: the broker drops a GTT on its own (for example on a corporate action). */
    public synchronized void disable(String id) {
        Entry g = gtts.get(id);
        if (g != null) {
            gtts.put(id, g.with(Gtt.Status.DISABLED, g.triggeredOrderId(), clock.now()));
            persist();
        }
    }

    public synchronized List<Gtt.Snapshot> list() {
        return gtts.values().stream().map(g -> new Gtt.Snapshot(g.id(), g.instrumentId(), g.tradingSymbol(), g.type(), g.status(), g.triggers(), g.legs(),
                g.triggeredOrderId(), g.createdAt(), g.updatedAt(), Map.of("simulated", true))).toList();
    }

    /** The legs whose trigger {@code price} meets; their GTTs are TRIGGERED from now on. */
    public synchronized List<Fired> onPrice(UUID instrumentId, BigDecimal price) {
        List<Fired> fired = new ArrayList<>();
        for (Entry g : List.copyOf(gtts.values())) {
            if (g.status() != Gtt.Status.ACTIVE || !g.instrumentId().equals(instrumentId)) {
                continue;
            }
            Gtt.Leg leg = null;
            if (g.type() == Gtt.Type.OCO) {
                if (price.compareTo(g.triggers().get(0)) <= 0) {
                    leg = g.legs().get(0);
                } else if (price.compareTo(g.triggers().get(1)) >= 0) {
                    leg = g.legs().get(1);
                }
            } else {
                BigDecimal trigger = g.triggers().get(0);
                boolean below = g.lastPrice() == null || trigger.compareTo(g.lastPrice()) < 0;
                if (below ? price.compareTo(trigger) <= 0 : price.compareTo(trigger) >= 0) {
                    leg = g.legs().get(0);
                }
            }
            if (leg != null) {
                gtts.put(g.id(), g.with(Gtt.Status.TRIGGERED, null, clock.now()));
                fired.add(new Fired(g.id(), instrumentId, leg));
            }
        }
        if (!fired.isEmpty()) {
            persist();
        }
        return fired;
    }

    /** Records the order a trigger placed. */
    public synchronized void triggeredOrder(String id, String brokerOrderId) {
        Entry g = gtts.get(id);
        if (g != null) {
            gtts.put(id, g.with(g.status(), brokerOrderId, g.updatedAt()));
            persist();
        }
    }

    public synchronized void clear() {
        gtts.clear();
        persist();
    }

    private Entry active(String id) {
        Entry g = gtts.get(id);
        if (g == null) {
            throw new BrokerException(BrokerException.Kind.INPUT, "no such GTT " + id, false, null);
        }
        if (g.status() != Gtt.Status.ACTIVE) {
            throw new BrokerException(BrokerException.Kind.REJECTED, "GTT " + id + " is " + g.status(), false, null);
        }
        return g;
    }
}
