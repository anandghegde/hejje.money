package money.hejje.instruments;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.internal.InstrumentStore;
import money.hejje.instruments.internal.InstrumentSyncJob;
import org.springframework.stereotype.Service;

/** Public API of the instruments module. */
@Service
public class InstrumentService {

    private final InstrumentStore store;
    private final InstrumentSyncJob sync;
    private final HejjeClock clock;

    InstrumentService(InstrumentStore store, InstrumentSyncJob sync, HejjeClock clock) {
        this.store = store;
        this.sync = sync;
        this.clock = clock;
    }

    public Optional<Instrument> findById(UUID id) {
        return store.findById(id);
    }

    /**
     * Resolves a canonical Hejje symbol (see {@link HejjeSymbol}). As a convenience, {@code EXCHANGE:TRADINGSYMBOL}
     * with a broker trading symbol (for example {@code NFO:NIFTY26SEPFUT}) also resolves through the broker mapping.
     *
     * @throws IllegalArgumentException when the text is not a well-formed symbol
     */
    public Optional<Instrument> resolve(String symbol) {
        HejjeSymbol parsed = HejjeSymbol.parse(symbol);
        Optional<Instrument> direct = store.findByKey(parsed);
        if (direct.isPresent() || parsed.type() != InstrumentType.EQ) {
            return direct;
        }
        return store.findByTradingSymbol(parsed.exchange().name(), parsed.symbol());
    }

    public Optional<Instrument> resolve(HejjeSymbol symbol) {
        return store.findByKey(symbol);
    }

    /** Prefix/substring search over symbol, name and underlying. Active instruments first. */
    public List<Instrument> search(String q, Exchange exchange, InstrumentType type, int limit) {
        return store.search(q == null ? "" : q.trim(), exchange, type, Math.max(1, Math.min(limit, 200)));
    }

    /** The active future on {@code underlying} with the earliest expiry on or after today (IST). */
    public Optional<Instrument> nearestFuture(String underlying) {
        return nearestFuture(underlying, clock.today());
    }

    public Optional<Instrument> nearestFuture(String underlying, LocalDate asOf) {
        return store.nearestFuture(underlying.trim().toUpperCase(), asOf);
    }

    /** All active options on {@code underlying} for {@code expiry}, ordered by strike then CE before PE. */
    public List<Instrument> optionChain(String underlying, LocalDate expiry) {
        return store.optionChain(underlying.trim().toUpperCase(), expiry);
    }

    /** Distinct upcoming option expiries (weekly and monthly) for {@code underlying}, ascending from today. */
    public List<LocalDate> weeklyExpiries(String underlying) {
        return store.optionExpiries(underlying.trim().toUpperCase(), clock.today());
    }

    public Optional<BrokerInstrumentMapping> mapping(UUID instrumentId, String broker) {
        return store.findMapping(instrumentId, broker);
    }

    public Optional<Instrument> findByBrokerToken(String broker, String brokerToken) {
        return store.findByBrokerToken(broker, brokerToken);
    }

    public Optional<Instrument> findByTradingSymbol(String broker, String exchangeSegment, String tradingSymbol) {
        return store.findByTradingSymbol(broker, exchangeSegment, tradingSymbol);
    }

    /** Runs the instrument master sync now (also scheduled daily). */
    public InstrumentSyncResult sync() {
        return sync.run();
    }
}
