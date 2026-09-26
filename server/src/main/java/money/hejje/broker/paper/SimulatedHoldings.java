package money.hejje.broker.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import money.hejje.broker.BrokerHolding;
import money.hejje.broker.BrokerPosition;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.time.HejjeClock;

/**
 * The delivery (CNC) side of a simulated broker, shaped like Zerodha's (plan M11.1): a delivery fill is a CNC position on
 * its session; from the next session it is a holding, reported as {@code t1Quantity} on the first session after the buy
 * (T+1 settlement) and as settled {@code quantity} after that. Selling a holding shows as a negative CNC position that
 * day while the holding still lists the shares, as at the broker; the next session the holding is smaller. The fake and
 * the paper adapters share it; the paper adapter keeps it in a {@link PaperStateStore} so it survives a restart.
 */
public final class SimulatedHoldings {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<Scrip>> STATE = new TypeReference<>() { };

    /** One delivery fill on a session. */
    public record Fill(LocalDate date, Side side, int quantity, BigDecimal price) {}

    /** A scrip's delivery book: the settled lot (fills folded in once they are two sessions old) and the newer fills. */
    public record Scrip(UUID instrumentId, String tradingSymbol, String exchangeSegment, int settledQuantity, BigDecimal settledAverage, List<Fill> fills) {}

    private final HejjeClock clock;
    private final PaperStateStore store;
    private final String key;
    private final Map<UUID, Scrip> scrips = new LinkedHashMap<>();

    public SimulatedHoldings(HejjeClock clock, PaperStateStore store, String key) {
        this.clock = clock;
        this.store = store;
        this.key = key;
        store.load(key).ifPresent(this::restore);
    }

    private void restore(String json) {
        try {
            for (Scrip s : JSON.readValue(json, STATE)) {
                scrips.put(s.instrumentId(), s);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("simulated holdings '" + key + "' are unreadable", e);
        }
    }

    private void persist() {
        try {
            store.save(key, JSON.writeValueAsString(new ArrayList<>(scrips.values())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records a delivery fill on today's session. */
    public synchronized void onFill(UUID instrumentId, String tradingSymbol, String exchangeSegment, Side side, int quantity, BigDecimal price) {
        add(instrumentId, tradingSymbol, exchangeSegment, new Fill(clock.today(), side, quantity, price));
    }

    /** Test and dev hook: a holding bought on {@code date} (outside Hejje). */
    public synchronized void seed(UUID instrumentId, String tradingSymbol, String exchangeSegment, int quantity, BigDecimal averagePrice, LocalDate date) {
        add(instrumentId, tradingSymbol, exchangeSegment, new Fill(date, Side.BUY, quantity, averagePrice));
    }

    private void add(UUID instrumentId, String tradingSymbol, String exchangeSegment, Fill fill) {
        Scrip s = scrips.getOrDefault(instrumentId, new Scrip(instrumentId, tradingSymbol, exchangeSegment, 0, BigDecimal.ZERO.setScale(2), List.of()));
        List<Fill> fills = new ArrayList<>(s.fills());
        fills.add(fill);
        scrips.put(instrumentId, new Scrip(instrumentId, tradingSymbol, exchangeSegment, s.settledQuantity(), s.settledAverage(), fills));
        persist();
    }

    /** Holdings as of today's session: every delivery fill of an earlier session, the previous session's as T1. */
    public synchronized List<BrokerHolding> holdings(Function<UUID, BigDecimal> lastPrice) {
        LocalDate today = clock.today();
        LocalDate t1Day = clock.previousTradingDay(today);
        fold(t1Day);
        List<BrokerHolding> out = new ArrayList<>();
        for (Scrip s : scrips.values()) {
            int qty = s.settledQuantity();
            BigDecimal avg = s.settledAverage();
            int t1Net = 0;
            for (Fill f : s.fills()) {
                if (!f.date().isBefore(today)) {
                    continue;
                }
                if (f.side() == Side.BUY) {
                    avg = avg.multiply(BigDecimal.valueOf(qty)).add(f.price().multiply(BigDecimal.valueOf(f.quantity())))
                            .divide(BigDecimal.valueOf(qty + f.quantity()), 2, RoundingMode.HALF_UP);
                    qty += f.quantity();
                } else {
                    qty = Math.max(0, qty - f.quantity());
                }
                if (f.date().equals(t1Day)) {
                    t1Net += f.side() == Side.BUY ? f.quantity() : -f.quantity();
                }
            }
            if (qty <= 0) {
                continue;
            }
            int t1 = Math.max(0, Math.min(qty, t1Net));
            BigDecimal ltp = Optional.ofNullable(lastPrice.apply(s.instrumentId())).orElse(avg);
            out.add(new BrokerHolding(s.instrumentId(), s.tradingSymbol(), s.exchangeSegment(), null, qty - t1, t1, avg, ltp,
                    Map.of("simulated", true)));
        }
        return out;
    }

    /** Today's delivery positions (product CNC): the net of today's delivery fills per scrip. */
    public synchronized List<BrokerPosition> positions(Function<UUID, BigDecimal> lastPrice) {
        LocalDate today = clock.today();
        List<BrokerPosition> out = new ArrayList<>();
        for (Scrip s : scrips.values()) {
            int buyQty = 0;
            int sellQty = 0;
            BigDecimal buyValue = BigDecimal.ZERO;
            BigDecimal sellValue = BigDecimal.ZERO;
            for (Fill f : s.fills()) {
                if (!f.date().equals(today)) {
                    continue;
                }
                BigDecimal value = f.price().multiply(BigDecimal.valueOf(f.quantity()));
                if (f.side() == Side.BUY) {
                    buyQty += f.quantity();
                    buyValue = buyValue.add(value);
                } else {
                    sellQty += f.quantity();
                    sellValue = sellValue.add(value);
                }
            }
            if (buyQty == 0 && sellQty == 0) {
                continue;
            }
            int net = buyQty - sellQty;
            BigDecimal avg = net > 0 ? buyValue.divide(BigDecimal.valueOf(buyQty), 2, RoundingMode.HALF_UP)
                    : net < 0 ? sellValue.divide(BigDecimal.valueOf(sellQty), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
            BigDecimal ltp = Optional.ofNullable(lastPrice.apply(s.instrumentId())).orElse(avg);
            Money unrealized = net == 0 ? Money.ZERO : Money.of(ltp.subtract(avg).multiply(BigDecimal.valueOf(net)).setScale(2, RoundingMode.HALF_UP));
            out.add(new BrokerPosition(s.instrumentId(), s.tradingSymbol(), s.exchangeSegment(), Product.CNC, net, avg, buyQty, sellQty,
                    buyValue.setScale(2, RoundingMode.HALF_UP), sellValue.setScale(2, RoundingMode.HALF_UP), Money.ZERO, unrealized, ltp,
                    Map.of("simulated", true)));
        }
        return out;
    }

    public synchronized void clear() {
        scrips.clear();
        persist();
    }

    /** Folds fills older than {@code keepFrom} into the settled lot (average cost), dropping scrips that end flat. */
    private void fold(LocalDate keepFrom) {
        boolean changed = false;
        for (Scrip s : List.copyOf(scrips.values())) {
            if (s.fills().stream().noneMatch(f -> f.date().isBefore(keepFrom))) {
                continue;
            }
            int qty = s.settledQuantity();
            BigDecimal avg = s.settledAverage();
            List<Fill> keep = new ArrayList<>();
            for (Fill f : s.fills()) {
                if (!f.date().isBefore(keepFrom)) {
                    keep.add(f);
                } else if (f.side() == Side.BUY) {
                    avg = avg.multiply(BigDecimal.valueOf(qty)).add(f.price().multiply(BigDecimal.valueOf(f.quantity())))
                            .divide(BigDecimal.valueOf(qty + f.quantity()), 2, RoundingMode.HALF_UP);
                    qty += f.quantity();
                } else {
                    qty = Math.max(0, qty - f.quantity());
                }
            }
            if (qty == 0 && keep.isEmpty()) {
                scrips.remove(s.instrumentId());
            } else {
                scrips.put(s.instrumentId(), new Scrip(s.instrumentId(), s.tradingSymbol(), s.exchangeSegment(), qty,
                        qty == 0 ? BigDecimal.ZERO.setScale(2) : avg, keep));
            }
            changed = true;
        }
        if (changed) {
            persist();
        }
    }
}
